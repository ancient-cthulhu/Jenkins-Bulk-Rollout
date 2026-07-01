#!/usr/bin/env python3
"""
rollout.py -- One-shot Veracode + Jenkins platform setup.

This file (rollout.py) is the safe template: placeholder values only, no
real credentials, fine to commit as-is.

Before running it for real: copy it to rollout.example.py (already covered
by .gitignore, so it can never be committed with real values in it), fill in
the CONFIG block in that copy, then run:

    python3 rollout.example.py

Editing and running rollout.py directly also works, but then be deliberate
about not committing it once the CONFIG block holds real org names, tokens,
or URLs. See platform-automation/README.md for the shell (rollout.sh) and
PowerShell (rollout.ps1) equivalents, same convention.
"""

import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

# ==============================================================================
# CONFIGURE EVERYTHING HERE
# ==============================================================================

# --- Platform org (where veracode-pipeline and jenkins-platform repos live) ---
# This can be the same org you are scanning, or a dedicated platform org.
# Example: "my-company-platform" or "my-company-products"
PLATFORM_ORG = "your-github-org"

# --- Orgs to scan ---
# GitHub orgs that Jenkins will create Organization Folders for and scan.
# Can include PLATFORM_ORG or be entirely different orgs.
# To add an org later: add it here and re-run. Nothing else changes.
SCAN_ORGS = [
    "your-github-org",
    # "another-org-to-scan",
]

# --- Jenkins folder (optional) ---
# If specified, Jenkins organization folders and credentials will be created
# within this folder path instead of at the top level.
# Leave empty for top-level creation. Example: "veracode" or "veracode/github"
JENKINS_FOLDER = ""

# --- Library version ---
# Tag applied to the veracode-pipeline repo. Must match what Jenkinsfiles
# reference. Bump this (e.g. "v2") when you want to ship a breaking change
# and roll out to orgs gradually.
LIBRARY_VERSION = "v1"

# --- GitHub ---
# PAT used for:
#   - Creating and pushing the two platform repos (needs: repo scope)
#   - The scm-readonly credential stored in Jenkins for org discovery
#     and library fetch (needs: repo + read:org scopes)
# Read from env var GITHUB_TOKEN (or GH_PAT / GH_TOKEN as fallbacks).
# Your GitHub username or service account name.
GITHUB_USERNAME = "your-github-username"

# --- Veracode ---
# API ID and Key from platform.veracode.com -> API Credentials.
# Used by Jenkins to run SAST/IaC uploads and by veracode-onboard.groovy
# to mint per-org SCA workspace tokens.
# Read from env vars VC_API_ID and VC_API_KEY (or VERACODE_API_KEY_ID /
# VERACODE_API_KEY_SECRET as fallbacks).

# --- Jenkins ---
# URL and admin credentials for the Jenkins controller.
# The admin user needs permission to manage credentials, system config,
# and run scripts from the Script Console.
JENKINS_URL  = "http://your-jenkins-host:8080"
JENKINS_USER = "admin"
# Read from env var JENKINS_TOKEN; falls back to JENKINS_USER value so
# "admin" / "admin" works out of the box for a fresh local install.

# --- GitHub API base ---
# Change to your GitHub Enterprise URL if not using github.com.
# Example: "https://github.example.com/api/v3"
GITHUB_API = "https://api.github.com"

# --- Repo visibility ---
# Set to False to create public repos (useful for open-source orgs).
REPOS_PRIVATE = True

# ==============================================================================
# END OF CONFIG -- nothing below should need editing for a standard rollout
# ==============================================================================


# --- Resolve credentials from env vars with clear error messages ---

def _require_env(*names, label):
    for n in names:
        v = os.environ.get(n, "").strip()
        if v:
            return v
    print(f"ERROR: {label} not set. Export one of: {', '.join(names)}")
    sys.exit(1)

GITHUB_TOKEN   = _require_env("GITHUB_TOKEN", "GH_PAT", "GH_TOKEN",
                               label="GitHub PAT (GITHUB_TOKEN)")
VERACODE_API_ID  = _require_env("VC_API_ID", "VERACODE_API_KEY_ID",
                                 label="Veracode API ID (VC_API_ID)")
VERACODE_API_KEY = _require_env("VC_API_KEY", "VERACODE_API_KEY_SECRET",
                                 label="Veracode API Key (VC_API_KEY)")
JENKINS_TOKEN  = os.environ.get("JENKINS_TOKEN", JENKINS_USER).strip()

# Derived paths
BASE = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
LIBRARY_DIR  = os.path.join(BASE, "library-repo")
PLATFORM_DIR = os.path.join(BASE, "platform-automation")


# ==============================================================================
# JENKINS FOLDER HELPERS
# ==============================================================================

def _get_credential_store_path():
    """
    Return the Jenkins API path for the credential store.
    If JENKINS_FOLDER is set, returns the folder-scoped store path.
    Otherwise returns the global system store.
    """
    if not JENKINS_FOLDER or JENKINS_FOLDER.strip() == "":
        return "/credentials/store/system/domain/_"
    _ensure_jenkins_folder()
    folder_path = JENKINS_FOLDER.strip("/").replace("/", "/job/")
    return f"/job/{folder_path}/credentials/store/folder/domain/_"


def _ensure_jenkins_folder():
    """Create Jenkins folder(s) if they don't exist (handles nested paths)."""
    if not JENKINS_FOLDER or JENKINS_FOLDER.strip() == "":
        return
    folders = [f.strip() for f in JENKINS_FOLDER.split("/") if f.strip()]
    current_path = ""
    for folder in folders:
        current_path = f"{current_path}/{folder}" if current_path else folder
        _create_folder_if_needed(current_path, folder)


def _create_folder_if_needed(folder_path, folder_name):
    """Create a single Jenkins folder if it does not already exist."""
    field, crumb, opener = _jenkins_crumb()
    headers = {**_jenkins_auth()}
    if field:
        headers[field] = crumb

    folder_api_path = folder_path.replace("/", "/job/")
    check_url = f"{JENKINS_URL}/job/{folder_api_path}/api/json"
    req = urllib.request.Request(check_url, headers=headers)
    try:
        with opener.open(req, timeout=15):
            return  # already exists
    except urllib.error.HTTPError as e:
        if e.code != 404:
            return

    folder_config = """<?xml version='1.0' encoding='UTF-8'?>
<com.cloudbees.hudson.plugins.folder.Folder plugin="cloudbees-folder">
  <description></description>
  <properties/>
</com.cloudbees.hudson.plugins.folder.Folder>"""

    headers_xml = {**headers, "Content-Type": "application/xml"}
    create_url = f"{JENKINS_URL}/createItem?name={urllib.parse.quote(folder_name)}"
    req = urllib.request.Request(
        create_url, data=folder_config.encode(),
        headers=headers_xml, method="POST")
    try:
        with opener.open(req, timeout=30):
            print(f"  Created Jenkins folder: {folder_path}")
    except urllib.error.HTTPError as e:
        if e.code != 400:  # 400 can mean already exists
            print(f"  WARNING creating folder {folder_path}: HTTP {e.code}")


# ==============================================================================
# GITHUB HELPERS
# ==============================================================================

GH_HEADERS = {
    "Authorization": f"token {GITHUB_TOKEN}",
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
}

def gh_get(path):
    req = urllib.request.Request(f"{GITHUB_API}{path}", headers=GH_HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")

def gh_post(path, body):
    data = json.dumps(body).encode()
    req = urllib.request.Request(
        f"{GITHUB_API}{path}", data=data,
        headers={**GH_HEADERS, "Content-Type": "application/json"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def create_github_repo(name, description):
    """Create a repo in PLATFORM_ORG if it does not already exist."""
    status, data = gh_get(f"/repos/{PLATFORM_ORG}/{name}")
    if status == 200:
        print(f"  {name}: already exists at {data['html_url']}")
        return data["clone_url"]
    print(f"  Creating {PLATFORM_ORG}/{name} ...")
    status, data = gh_post(f"/orgs/{PLATFORM_ORG}/repos", {
        "name": name,
        "description": description,
        "private": REPOS_PRIVATE,
        "auto_init": False,
    })
    if status not in (200, 201):
        print(f"  ERROR creating repo: {status} {data}")
        sys.exit(1)
    print(f"  Created: {data['html_url']}")
    return data["clone_url"]


def push_directory(src_dir, clone_url, tag=None, force=False):
    """
    Init src_dir as a git repo (if needed), commit any changes, and
    push to clone_url. Embeds the PAT in the remote URL so no
    interactive prompt appears.
    """
    authed_url = clone_url.replace("https://", f"https://{GITHUB_TOKEN}@")
    env = {**os.environ, "GIT_TERMINAL_PROMPT": "0"}

    def git(*args, check=True, ignore_error=False):
        r = subprocess.run(["git"] + list(args), cwd=src_dir,
                           env=env, capture_output=True, text=True)
        if check and r.returncode != 0 and not ignore_error:
            # Print stderr only if it is not just a harmless warning
            if r.stderr.strip():
                print(f"    git {' '.join(args)}: {r.stderr.strip()}")
        return r

    if not os.path.isdir(os.path.join(src_dir, ".git")):
        git("init")

    git("config", "user.email", "ci@veracode-rollout")
    git("config", "user.name",  "Veracode Rollout")
    git("add", "-A")

    # Only commit if there is something staged
    diff = git("diff", "--cached", "--quiet", check=False)
    if diff.returncode != 0:
        git("commit", "-m", "change: rollout commit")

    # Set remote (remove first to avoid duplicate-remote errors)
    git("remote", "remove", "origin", check=False, ignore_error=True)
    git("remote", "add", "origin", authed_url)

    push_args = ["push", "-u", "origin", "HEAD:main"]
    if force:
        push_args.append("--force")
    git(*push_args)

    if tag:
        git("tag", "-f", tag)
        git("push", "origin", f"refs/tags/{tag}", "-f")
        print(f"  Tagged {tag} and pushed.")


# ==============================================================================
# JENKINS HELPERS
# ==============================================================================

def _jenkins_auth():
    creds = base64.b64encode(f"{JENKINS_USER}:{JENKINS_TOKEN}".encode()).decode()
    return {"Authorization": f"Basic {creds}"}

def _jenkins_crumb():
    """Fetch a CSRF crumb using a persistent session cookie."""
    # Use a cookie jar so the crumb and the request share the same session
    import http.cookiejar
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    req = urllib.request.Request(
        f"{JENKINS_URL}/crumbIssuer/api/json",
        headers=_jenkins_auth())
    try:
        with opener.open(req, timeout=15) as r:
            d = json.loads(r.read())
            return d["crumbRequestField"], d["crumb"], opener
    except Exception:
        # Some Jenkins setups disable CSRF; return empty and fall through
        return None, None, opener

def jenkins_script(groovy_code):
    """Run a Groovy script in the Jenkins Script Console and return output."""
    field, crumb, opener = _jenkins_crumb()
    headers = {**_jenkins_auth(), "Content-Type": "application/x-www-form-urlencoded"}
    if field:
        headers[field] = crumb
    body = urllib.parse.urlencode({"script": groovy_code}).encode()
    req = urllib.request.Request(
        f"{JENKINS_URL}/scriptText",
        data=body, headers=headers, method="POST")
    try:
        with opener.open(req, timeout=120) as r:
            return r.status, r.read().decode(errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")

def jenkins_upsert_credential(cred_xml):
    """
    Create or update a Jenkins credential at the configured scope (global or folder).
    Uses the XML credential API.
    """
    import xml.etree.ElementTree as ET
    cred_id = ET.fromstring(cred_xml).findtext("id")
    field, crumb, opener = _jenkins_crumb()
    headers = {**_jenkins_auth(), "Content-Type": "application/xml"}
    if field:
        headers[field] = crumb

    cred_store_path = _get_credential_store_path()

    # Try update first; if 404 fall through to create
    update_url = (f"{JENKINS_URL}{cred_store_path}/"
                  f"credential/{cred_id}/config.xml")
    req = urllib.request.Request(
        update_url, data=cred_xml.encode(), headers=headers, method="POST")
    try:
        with opener.open(req, timeout=30) as r:
            print(f"  Updated credential: {cred_id} (HTTP {r.status})")
            return
    except urllib.error.HTTPError as e:
        if e.code != 404:
            print(f"  WARNING updating {cred_id}: {e.code} {e.read().decode()[:200]}")

    # Create
    create_url = (f"{JENKINS_URL}{cred_store_path}/"
                  f"createCredentials")
    req = urllib.request.Request(
        create_url, data=cred_xml.encode(), headers=headers, method="POST")
    try:
        with opener.open(req, timeout=30) as r:
            print(f"  Created credential: {cred_id} (HTTP {r.status})")
    except urllib.error.HTTPError as e:
        print(f"  ERROR creating {cred_id}: {e.code} {e.read().decode()[:200]}")


# ==============================================================================
# STEP 1 + 2: GitHub repos
# ==============================================================================

def step_github_repos():
    print("\n=== Step 1: Create GitHub repos ===")

    print("\n  veracode-pipeline (shared library):")
    url = create_github_repo(
        "veracode-pipeline",
        "Veracode Jenkins shared pipeline library -- SCA, IaC/secrets, SAST/Policy")
    push_directory(LIBRARY_DIR, url, tag=LIBRARY_VERSION)

    print("\n  jenkins-platform (platform automation):")
    url = create_github_repo(
        "jenkins-platform",
        "Veracode Jenkins platform automation -- JCasC, onboarding, bulk-PR")
    push_directory(PLATFORM_DIR, url)

    print(f"\n  veracode-pipeline: https://github.com/{PLATFORM_ORG}/veracode-pipeline")
    print(f"  jenkins-platform:  https://github.com/{PLATFORM_ORG}/jenkins-platform")


# ==============================================================================
# STEP 3: Jenkins credentials
# ==============================================================================

def step_jenkins_credentials():
    print("\n=== Step 2: Configure Jenkins credentials ===")

    if JENKINS_FOLDER:
        print(f"  Using Jenkins folder: {JENKINS_FOLDER}")

    # Veracode API ID (secret text)
    jenkins_upsert_credential(f"""
<org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>
  <scope>GLOBAL</scope>
  <id>veracode-api-id</id>
  <description>Veracode API ID</description>
  <secret>{VERACODE_API_ID}</secret>
</org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>
""".strip())

    # Veracode API Key (secret text)
    jenkins_upsert_credential(f"""
<org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>
  <scope>GLOBAL</scope>
  <id>veracode-api-key</id>
  <description>Veracode API Key</description>
  <secret>{VERACODE_API_KEY}</secret>
</org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>
""".strip())

    # GitHub PAT as scm-readonly (username + password / token)
    jenkins_upsert_credential(f"""
<com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl>
  <scope>GLOBAL</scope>
  <id>scm-readonly</id>
  <description>GitHub read-only scan account (PAT). Used for org discovery and library fetch.</description>
  <username>{GITHUB_USERNAME}</username>
  <password>{GITHUB_TOKEN}</password>
</com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl>
""".strip())

    print("  Done. Credentials: veracode-api-id, veracode-api-key, scm-readonly")


# ==============================================================================
# STEP 4: Configure GitHub Server (API rate-limit registration only)
# ==============================================================================
#
# NOTE: manageHooks is intentionally False. This deployment is egress-only --
# Jenkins must never receive inbound calls from GitHub, and this deployment
# has no automatic trigger at all: no webhook, no periodic poll. Every scan
# is ad hoc (Jenkins UI or trigger-scan.sh/.ps1). This step still registers
# the GitHub Server entry because it is how the GitHub Branch Source plugin
# tracks API rate-limit usage against the scm-readonly credential; it does
# not open any inbound path and does not schedule anything on its own.

def step_github_server():
    print("\n=== Step 4: Configure GitHub Server (no webhook registration) ===")

    api_url = GITHUB_API if GITHUB_API != "https://api.github.com" else "https://api.github.com"

    groovy = f"""
import jenkins.model.Jenkins
import org.jenkinsci.plugins.github.config.GitHubServerConfig
import org.jenkinsci.plugins.github.config.GitHubPluginConfig

def config = Jenkins.get().getDescriptor(GitHubPluginConfig.class)

// configs returns an unmodifiable list -- copy to mutable, modify, set back
def servers = new ArrayList(config.configs)
servers.removeIf {{ it.apiUrl == '{api_url}' }}

def server = new GitHubServerConfig('scm-readonly')
server.apiUrl          = '{api_url}'
server.manageHooks     = false
server.clientCacheSize = 20
servers.add(server)

config.configs = servers
config.save()
println "GitHub Server registered: {api_url} (manageHooks=false, no inbound webhook, credential=scm-readonly)"
"""
    status, output = jenkins_script(groovy)
    if status == 200 and "GitHub Server registered" in output:
        print(f"  {output.strip()}")
    else:
        print(f"  ERROR: {status}")
        print(f"  {output[:300]}")
        sys.exit(1)


# ==============================================================================
# STEP 5: Register the shared library
# ==============================================================================

def step_register_library():
    print("\n=== Step 5: Register shared library in Jenkins ===")

    library_url = f"https://github.com/{PLATFORM_ORG}/veracode-pipeline.git"

    # Register via Groovy in the Script Console -- more reliable than
    # fighting the REST API for GlobalLibraries config.
    groovy = f"""
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.libs.*
import jenkins.plugins.git.GitSCMSource

def jenkins   = Jenkins.get()
def globalLib = jenkins.getDescriptor(GlobalLibraries.class)

// Remove any existing registration with the same name so we can re-apply
globalLib.libraries.removeIf {{ it.name == 'veracode-pipeline' }}

def source = new GitSCMSource('{library_url}')
source.credentialsId = 'scm-readonly'

def retriever = new SCMSourceRetriever(source)
def lib = new LibraryConfiguration('veracode-pipeline', retriever)
lib.defaultVersion     = '{LIBRARY_VERSION}'
lib.implicit           = false
lib.allowVersionOverride = true
lib.includeInChangesets  = false

globalLib.libraries.add(lib)
globalLib.save()
println "Library registered: veracode-pipeline @ {LIBRARY_VERSION} -> {library_url}"
"""
    status, output = jenkins_script(groovy)
    if status == 200:
        print(f"  {output.strip()}")
    else:
        print(f"  ERROR registering library: HTTP {status}")
        print(f"  {output[:300]}")
        sys.exit(1)


# ==============================================================================
# STEP 5: Run veracode-onboard.groovy
# ==============================================================================

def step_onboard_orgs():
    print("\n=== Step 5: Run veracode-onboard.groovy ===")

    onboard_path = os.path.join(PLATFORM_DIR, "veracode-onboard.groovy")
    with open(onboard_path, encoding="utf-8") as f:
        script = f.read()

    # Dynamically replace the ORGS list so this script drives it,
    # not whatever is hardcoded in the .groovy file.
    orgs_literal = "[\n" + "".join(f"    '{o}',\n" for o in SCAN_ORGS) + "]"
    script = re.sub(
        r"@Field\s+List<String>\s+ORGS\s*=\s*\[.*?\]",
        f"@Field List<String> ORGS = {orgs_literal}",
        script, flags=re.DOTALL)

    # Inject JENKINS_FOLDER if specified so the Groovy script uses the same folder
    if JENKINS_FOLDER:
        folder_literal = f"'{JENKINS_FOLDER}'"
        if re.search(r"@Field\s+final\s+String\s+PARENT_FOLDER", script):
            script = re.sub(
                r"(@Field\s+final\s+String\s+PARENT_FOLDER\s*=\s*)['\"].*?['\"]",
                f"\\1{folder_literal}",
                script)
        print(f"  Jenkins folder: {JENKINS_FOLDER}")

    print(f"  Scanning orgs: {SCAN_ORGS}")
    status, output = jenkins_script(script)

    for line in output.strip().splitlines():
        print(f"  {line}")

    if status != 200:
        print(f"  ERROR: Script Console returned HTTP {status}")
        sys.exit(1)
    if "FAILED" in output.upper():
        print("  WARNING: one or more orgs reported failures (see above)")


# ==============================================================================
# MAIN
# ==============================================================================

def main():
    print("=" * 60)
    print("  Veracode + Jenkins rollout")
    print(f"  Platform org   : {PLATFORM_ORG}")
    print(f"  Scan orgs      : {SCAN_ORGS}")
    if JENKINS_FOLDER:
        print(f"  Jenkins folder : {JENKINS_FOLDER}")
    print(f"  Library ver    : {LIBRARY_VERSION}")
    print(f"  Jenkins        : {JENKINS_URL}")
    print("=" * 60)

    step_github_repos()
    step_jenkins_credentials()
    step_github_server()
    step_register_library()
    step_onboard_orgs()

    print(f"""
=== Rollout complete ===

  veracode-pipeline : https://github.com/{PLATFORM_ORG}/veracode-pipeline (tag {LIBRARY_VERSION})
  jenkins-platform  : https://github.com/{PLATFORM_ORG}/jenkins-platform
  Jenkins           : {JENKINS_URL}

Next step -- open Jenkinsfile PRs across each org:

  python3 bulk_add_jenkinsfile.py --orgs {" ".join(SCAN_ORGS)} --lib-version {LIBRARY_VERSION} --dry-run
  python3 bulk_add_jenkinsfile.py --orgs {" ".join(SCAN_ORGS)} --lib-version {LIBRARY_VERSION} --skip-archived --skip-forks --yes

Review and merge the PRs. Jenkins will start scanning on the next push.
""")


if __name__ == "__main__":
    main()
