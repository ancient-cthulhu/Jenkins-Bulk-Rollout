#!/usr/bin/env bash
#
# rollout.sh -- Bash port of rollout.py. Same steps, same Jenkins/GitHub REST
# calls, no Python required.
#
# Requires: bash, curl, jq, git. Nothing else.
#
# Copy this file, fill in the CONFIG block below, and run it:
#   chmod +x rollout.sh
#   ./rollout.sh
#
set -euo pipefail

# ==============================================================================
# CONFIGURE EVERYTHING HERE
# ==============================================================================

# --- Platform org (where veracode-pipeline and jenkins-platform repos live) ---
PLATFORM_ORG="your-github-org"

# --- Orgs to scan ---
SCAN_ORGS=(
  "your-github-org"
  # "another-org-to-scan"
)

# --- Jenkins folder (optional) ---
# Leave empty for top-level creation. Example: "veracode" or "veracode/github"
JENKINS_FOLDER=""

# --- Library version ---
LIBRARY_VERSION="v1"

# --- GitHub ---
# PAT read from env var GITHUB_TOKEN (or GH_PAT / GH_TOKEN as fallbacks).
GITHUB_USERNAME="your-github-username"

# --- Veracode ---
# Read from env vars VC_API_ID and VC_API_KEY (or VERACODE_API_KEY_ID /
# VERACODE_API_KEY_SECRET as fallbacks).

# --- Jenkins ---
JENKINS_URL="http://your-jenkins-host:8080"
JENKINS_USER="admin"
# Read from env var JENKINS_TOKEN; falls back to JENKINS_USER value.

# --- GitHub API base ---
# Change for GitHub Enterprise, e.g. "https://github.example.com/api/v3"
GITHUB_API="https://api.github.com"

# --- Repo visibility ---
REPOS_PRIVATE=true

# ==============================================================================
# END OF CONFIG -- nothing below should need editing for a standard rollout
# ==============================================================================

require_env() {
  local label="$1"; shift
  local name val
  for name in "$@"; do
    val="${!name:-}"
    if [ -n "$val" ]; then
      echo "$val"
      return 0
    fi
  done
  echo "ERROR: ${label} not set. Export one of: $*" >&2
  exit 1
}

for bin in curl jq git; do
  command -v "$bin" >/dev/null 2>&1 || { echo "ERROR: '$bin' is required but not installed." >&2; exit 1; }
done

GITHUB_TOKEN=$(require_env "GitHub PAT (GITHUB_TOKEN)" GITHUB_TOKEN GH_PAT GH_TOKEN)
VERACODE_API_ID=$(require_env "Veracode API ID (VC_API_ID)" VC_API_ID VERACODE_API_KEY_ID)
VERACODE_API_KEY=$(require_env "Veracode API Key (VC_API_KEY)" VC_API_KEY VERACODE_API_KEY_SECRET)
JENKINS_TOKEN="${JENKINS_TOKEN:-$JENKINS_USER}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LIBRARY_DIR="$BASE_DIR/library-repo"
PLATFORM_DIR="$BASE_DIR/platform-automation"

COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

HTTP_STATUS=""

# ==============================================================================
# GITHUB HELPERS
# ==============================================================================

gh_get() {
  local path="$1" resp
  resp=$(curl -sS -w '\n%{http_code}' \
    -H "Authorization: token ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "${GITHUB_API}${path}")
  HTTP_STATUS=$(echo "$resp" | tail -n1)
  echo "$resp" | sed '$d'
}

gh_post() {
  local path="$1" body="$2" resp
  resp=$(curl -sS -w '\n%{http_code}' -X POST \
    -H "Authorization: token ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -H "Content-Type: application/json" \
    -d "$body" \
    "${GITHUB_API}${path}")
  HTTP_STATUS=$(echo "$resp" | tail -n1)
  echo "$resp" | sed '$d'
}

create_github_repo() {
  # $1=name $2=description -- prints clone_url on stdout, logs go to stderr
  local name="$1" description="$2" out html_url payload
  out=$(gh_get "/repos/${PLATFORM_ORG}/${name}")
  if [ "$HTTP_STATUS" = "200" ]; then
    html_url=$(echo "$out" | jq -r '.html_url')
    echo "  ${name}: already exists at ${html_url}" >&2
    echo "$out" | jq -r '.clone_url'
    return 0
  fi

  echo "  Creating ${PLATFORM_ORG}/${name} ..." >&2
  payload=$(jq -n --arg name "$name" --arg desc "$description" --argjson priv "$REPOS_PRIVATE" \
    '{name:$name, description:$desc, private:$priv, auto_init:false}')
  out=$(gh_post "/orgs/${PLATFORM_ORG}/repos" "$payload")
  if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "201" ]; then
    echo "  ERROR creating repo: ${HTTP_STATUS} ${out}" >&2
    exit 1
  fi
  echo "  Created: $(echo "$out" | jq -r '.html_url')" >&2
  echo "$out" | jq -r '.clone_url'
}

push_directory() {
  # $1=src_dir $2=clone_url $3=tag(optional)
  local src_dir="$1" clone_url="$2" tag="${3:-}"
  local authed_url="${clone_url/https:\/\//https:\/\/${GITHUB_TOKEN}@}"

  (
    cd "$src_dir"
    export GIT_TERMINAL_PROMPT=0
    [ -d .git ] || git init -q
    git config user.email "ci@veracode-rollout"
    git config user.name  "Veracode Rollout"
    git add -A
    if ! git diff --cached --quiet; then
      git commit -q -m "change: rollout commit"
    fi
    git remote remove origin >/dev/null 2>&1 || true
    git remote add origin "$authed_url"
    git push -u origin HEAD:main
    if [ -n "$tag" ]; then
      git tag -f "$tag"
      git push origin "refs/tags/${tag}" -f
      echo "  Tagged ${tag} and pushed."
    fi
  )
}

# ==============================================================================
# JENKINS HELPERS
# ==============================================================================

# Populates the cookie jar and prints "field:crumb" (empty if CSRF disabled).
jenkins_crumb() {
  local resp field crumb
  resp=$(curl -sS -c "$COOKIE_JAR" -u "${JENKINS_USER}:${JENKINS_TOKEN}" \
    "${JENKINS_URL}/crumbIssuer/api/json" || true)
  field=$(echo "$resp" | jq -r '.crumbRequestField // empty' 2>/dev/null || true)
  crumb=$(echo "$resp" | jq -r '.crumb // empty' 2>/dev/null || true)
  [ -n "$field" ] && [ -n "$crumb" ] && echo "${field}:${crumb}"
  return 0
}

# Builds a -H array for the crumb header, if any. Usage:
#   crumb_args=(); load_crumb_args crumb_args
load_crumb_args() {
  local -n out_ref=$1
  local pair field crumb
  pair=$(jenkins_crumb)
  out_ref=()
  if [ -n "$pair" ]; then
    field="${pair%%:*}"
    crumb="${pair#*:}"
    out_ref=(-H "${field}: ${crumb}")
  fi
}

jenkins_script() {
  # $1 = groovy code -- prints output, sets HTTP_STATUS
  local groovy="$1" resp
  local crumb_args=(); load_crumb_args crumb_args
  resp=$(curl -sS -w '\n%{http_code}' -b "$COOKIE_JAR" \
    -u "${JENKINS_USER}:${JENKINS_TOKEN}" \
    "${crumb_args[@]}" \
    --data-urlencode "script=${groovy}" \
    "${JENKINS_URL}/scriptText")
  HTTP_STATUS=$(echo "$resp" | tail -n1)
  echo "$resp" | sed '$d'
}

get_credential_store_path() {
  if [ -z "$JENKINS_FOLDER" ]; then
    echo "/credentials/store/system/domain/_"
    return 0
  fi
  ensure_jenkins_folder >&2
  local folder_path="${JENKINS_FOLDER#/}"
  folder_path="${folder_path%/}"
  folder_path="${folder_path//\//\/job\/}"
  echo "/job/${folder_path}/credentials/store/folder/domain/_"
}

ensure_jenkins_folder() {
  [ -z "$JENKINS_FOLDER" ] && return 0
  local current="" folder
  IFS='/' read -ra parts <<< "$JENKINS_FOLDER"
  for folder in "${parts[@]}"; do
    [ -z "$folder" ] && continue
    if [ -z "$current" ]; then current="$folder"; else current="${current}/${folder}"; fi
    create_folder_if_needed "$current" "$folder"
  done
}

create_folder_if_needed() {
  local folder_path="$1" folder_name="$2" status folder_api_path encoded_name
  local crumb_args=(); load_crumb_args crumb_args

  folder_api_path="${folder_path//\//\/job\/}"
  status=$(curl -sS -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" \
    -u "${JENKINS_USER}:${JENKINS_TOKEN}" \
    "${JENKINS_URL}/job/${folder_api_path}/api/json")
  [ "$status" = "200" ] && return 0

  local folder_config='<?xml version="1.0" encoding="UTF-8"?>
<com.cloudbees.hudson.plugins.folder.Folder plugin="cloudbees-folder">
  <description></description>
  <properties/>
</com.cloudbees.hudson.plugins.folder.Folder>'

  encoded_name=$(jq -rn --arg v "$folder_name" '$v|@uri')
  status=$(curl -sS -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" \
    -u "${JENKINS_USER}:${JENKINS_TOKEN}" "${crumb_args[@]}" \
    -H "Content-Type: application/xml" \
    -X POST --data "$folder_config" \
    "${JENKINS_URL}/createItem?name=${encoded_name}")

  if [ "$status" = "200" ]; then
    echo "  Created Jenkins folder: ${folder_path}"
  elif [ "$status" != "400" ]; then
    echo "  WARNING creating folder ${folder_path}: HTTP ${status}"
  fi
}

jenkins_upsert_credential() {
  # $1 = credential xml, $2 = credential id
  local cred_xml="$1" cred_id="$2" store_path status
  local crumb_args=(); load_crumb_args crumb_args
  store_path=$(get_credential_store_path)

  status=$(curl -sS -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" \
    -u "${JENKINS_USER}:${JENKINS_TOKEN}" "${crumb_args[@]}" \
    -H "Content-Type: application/xml" \
    -X POST --data "$cred_xml" \
    "${JENKINS_URL}${store_path}/credential/${cred_id}/config.xml")

  if [ "$status" = "200" ]; then
    echo "  Updated credential: ${cred_id} (HTTP ${status})"
    return 0
  fi

  status=$(curl -sS -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" \
    -u "${JENKINS_USER}:${JENKINS_TOKEN}" "${crumb_args[@]}" \
    -H "Content-Type: application/xml" \
    -X POST --data "$cred_xml" \
    "${JENKINS_URL}${store_path}/createCredentials")

  if [ "$status" = "200" ]; then
    echo "  Created credential: ${cred_id} (HTTP ${status})"
  else
    echo "  ERROR creating ${cred_id}: HTTP ${status}"
  fi
}

# ==============================================================================
# STEP 1: GitHub repos
# ==============================================================================

step_github_repos() {
  echo
  echo "=== Step 1: Create GitHub repos ==="

  echo
  echo "  veracode-pipeline (shared library):"
  local url
  url=$(create_github_repo "veracode-pipeline" \
    "Veracode Jenkins shared pipeline library -- SCA, IaC/secrets, SAST/Policy")
  push_directory "$LIBRARY_DIR" "$url" "$LIBRARY_VERSION"

  echo
  echo "  jenkins-platform (platform automation):"
  url=$(create_github_repo "jenkins-platform" \
    "Veracode Jenkins platform automation -- JCasC, onboarding, bulk-PR")
  push_directory "$PLATFORM_DIR" "$url"

  echo
  echo "  veracode-pipeline: https://github.com/${PLATFORM_ORG}/veracode-pipeline"
  echo "  jenkins-platform:  https://github.com/${PLATFORM_ORG}/jenkins-platform"
}

# ==============================================================================
# STEP 2: Jenkins credentials
# ==============================================================================

step_jenkins_credentials() {
  echo
  echo "=== Step 2: Configure Jenkins credentials ==="
  [ -n "$JENKINS_FOLDER" ] && echo "  Using Jenkins folder: ${JENKINS_FOLDER}"

  local xml

  xml=$(cat <<EOF
<org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>
  <scope>GLOBAL</scope>
  <id>veracode-api-id</id>
  <description>Veracode API ID</description>
  <secret>${VERACODE_API_ID}</secret>
</org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>
EOF
)
  jenkins_upsert_credential "$xml" "veracode-api-id"

  xml=$(cat <<EOF
<org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>
  <scope>GLOBAL</scope>
  <id>veracode-api-key</id>
  <description>Veracode API Key</description>
  <secret>${VERACODE_API_KEY}</secret>
</org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>
EOF
)
  jenkins_upsert_credential "$xml" "veracode-api-key"

  xml=$(cat <<EOF
<com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl>
  <scope>GLOBAL</scope>
  <id>scm-readonly</id>
  <description>GitHub read-only scan account (PAT). Used for org discovery and library fetch.</description>
  <username>${GITHUB_USERNAME}</username>
  <password>${GITHUB_TOKEN}</password>
</com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl>
EOF
)
  jenkins_upsert_credential "$xml" "scm-readonly"

  echo "  Done. Credentials: veracode-api-id, veracode-api-key, scm-readonly"
}

# ==============================================================================
# STEP 3: Configure GitHub Server (enables webhook auto-registration)
# ==============================================================================

step_github_server() {
  echo
  echo "=== Step 3: Configure GitHub Server ==="

  local groovy status output
  groovy=$(cat <<EOF
import jenkins.model.Jenkins
import org.jenkinsci.plugins.github.config.GitHubServerConfig
import org.jenkinsci.plugins.github.config.GitHubPluginConfig

def config = Jenkins.get().getDescriptor(GitHubPluginConfig.class)
def servers = new ArrayList(config.configs)
servers.removeIf { it.apiUrl == '${GITHUB_API}' }

def server = new GitHubServerConfig('scm-readonly')
server.apiUrl          = '${GITHUB_API}'
server.manageHooks     = true
server.clientCacheSize = 20
servers.add(server)

config.configs = servers
config.save()
println "GitHub Server registered: ${GITHUB_API} (manageHooks=true, credential=scm-readonly)"
EOF
)
  output=$(jenkins_script "$groovy")
  status="$HTTP_STATUS"
  if [ "$status" = "200" ] && echo "$output" | grep -q "GitHub Server registered"; then
    echo "  ${output}"
  else
    echo "  ERROR: ${status}"
    echo "  ${output:0:300}"
    exit 1
  fi
}

# ==============================================================================
# STEP 4: Register the shared library
# ==============================================================================

step_register_library() {
  echo
  echo "=== Step 4: Register shared library in Jenkins ==="

  local library_url="https://github.com/${PLATFORM_ORG}/veracode-pipeline.git"
  local groovy status output
  groovy=$(cat <<EOF
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.libs.*
import jenkins.plugins.git.GitSCMSource

def jenkins   = Jenkins.get()
def globalLib = jenkins.getDescriptor(GlobalLibraries.class)

globalLib.libraries.removeIf { it.name == 'veracode-pipeline' }

def source = new GitSCMSource('${library_url}')
source.credentialsId = 'scm-readonly'

def retriever = new SCMSourceRetriever(source)
def lib = new LibraryConfiguration('veracode-pipeline', retriever)
lib.defaultVersion     = '${LIBRARY_VERSION}'
lib.implicit           = false
lib.allowVersionOverride = true
lib.includeInChangesets  = false

globalLib.libraries.add(lib)
globalLib.save()
println "Library registered: veracode-pipeline @ ${LIBRARY_VERSION} -> ${library_url}"
EOF
)
  output=$(jenkins_script "$groovy")
  status="$HTTP_STATUS"
  if [ "$status" = "200" ]; then
    echo "  ${output}"
  else
    echo "  ERROR registering library: HTTP ${status}"
    echo "  ${output:0:300}"
    exit 1
  fi
}

# ==============================================================================
# STEP 5: Run veracode-onboard.groovy
# ==============================================================================

step_onboard_orgs() {
  echo
  echo "=== Step 5: Run veracode-onboard.groovy ==="

  local onboard_path="${PLATFORM_DIR}/veracode-onboard.groovy"
  local script orgs_block status output

  # Build the replacement ORGS block.
  orgs_block="@Field List<String> ORGS = ["
  local o
  for o in "${SCAN_ORGS[@]}"; do
    orgs_block+=$'\n'"    '${o}',"
  done
  orgs_block+=$'\n'"]"

  # Replace the (possibly multi-line) ORGS declaration.
  script=$(awk -v block="$orgs_block" '
    BEGIN { skip=0 }
    /@Field[ \t]+List<String>[ \t]+ORGS[ \t]*=/ {
      print block
      skip = ($0 ~ /\]/) ? 0 : 1
      next
    }
    skip==1 { if ($0 ~ /\]/) skip=0; next }
    { print }
  ' "$onboard_path")

  if [ -n "$JENKINS_FOLDER" ]; then
    script=$(echo "$script" | awk -v folder="$JENKINS_FOLDER" '
      /@Field[ \t]+final[ \t]+String[ \t]+PARENT_FOLDER/ {
        print "@Field final String PARENT_FOLDER = \x27" folder "\x27"
        next
      }
      { print }
    ')
    echo "  Jenkins folder: ${JENKINS_FOLDER}"
  fi

  echo "  Scanning orgs: ${SCAN_ORGS[*]}"
  output=$(jenkins_script "$script")
  status="$HTTP_STATUS"

  while IFS= read -r line; do
    echo "  ${line}"
  done <<< "$output"

  if [ "$status" != "200" ]; then
    echo "  ERROR: Script Console returned HTTP ${status}"
    exit 1
  fi
  if echo "$output" | grep -qi "FAILED"; then
    echo "  WARNING: one or more orgs reported failures (see above)"
  fi
}

# ==============================================================================
# MAIN
# ==============================================================================

main() {
  echo "============================================================"
  echo "  Veracode + Jenkins rollout"
  echo "  Platform org   : ${PLATFORM_ORG}"
  echo "  Scan orgs      : ${SCAN_ORGS[*]}"
  [ -n "$JENKINS_FOLDER" ] && echo "  Jenkins folder : ${JENKINS_FOLDER}"
  echo "  Library ver    : ${LIBRARY_VERSION}"
  echo "  Jenkins        : ${JENKINS_URL}"
  echo "============================================================"

  step_github_repos
  step_jenkins_credentials
  step_github_server
  step_register_library
  step_onboard_orgs

  cat <<EOF

=== Rollout complete ===

  veracode-pipeline : https://github.com/${PLATFORM_ORG}/veracode-pipeline (tag ${LIBRARY_VERSION})
  jenkins-platform  : https://github.com/${PLATFORM_ORG}/jenkins-platform
  Jenkins           : ${JENKINS_URL}

Next step -- open Jenkinsfile PRs across each org:

  python3 bulk_add_jenkinsfile.py --orgs ${SCAN_ORGS[*]} --lib-version ${LIBRARY_VERSION} --dry-run
  python3 bulk_add_jenkinsfile.py --orgs ${SCAN_ORGS[*]} --lib-version ${LIBRARY_VERSION} --skip-archived --skip-forks --yes

(Ask for a bulk_add_jenkinsfile.sh / .ps1 port if this client also needs
 the bulk-PR step without Python.)

Review and merge the PRs. Jenkins will start scanning on the next push.

EOF
}

main "$@"
