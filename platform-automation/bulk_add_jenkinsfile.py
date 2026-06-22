#!/usr/bin/env python3
"""
Bulk-add the Veracode 2-line Jenkinsfile across one or more GitHub orgs.

Idempotent and resumable. Default mode opens a PR per repo (never commits to the
default branch directly). An opt-in --mode direct commits straight to the default
branch but RESPECTS branch protection and rulesets: if a write is blocked, it is
reported and skipped. This tool never disables branch protection or org/repo
rulesets. If a protected branch must be written, grant the rollout identity a
ruleset bypass actor through the owning team rather than disabling controls.

Stdlib only, no pip installs.

Usage:
    export GITHUB_TOKEN=ghp_xxx
    # dry run first, always:
    python3 bulk_add_jenkinsfile.py --orgs acme-corp acme-labs --lib-version v1 --dry-run
    # then execute (multi-org / non-dry-run requires --yes as a blast-radius gate):
    python3 bulk_add_jenkinsfile.py --orgs acme-corp acme-labs --lib-version v1 --yes
    # direct commit (respects protection), with audit log:
    python3 bulk_add_jenkinsfile.py --orgs acme-corp --lib-version v1 --mode direct --yes --audit-log rollout.jsonl
    # scope / skips:
    python3 bulk_add_jenkinsfile.py --orgs acme-corp --lib-version v1 --include repo-a repo-b --skip-archived --skip-forks --dry-run

For GitHub Enterprise, pass --api-base https://ghe.example.com/api/v3
"""

import argparse
import base64
import json
import os
import random
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BRANCH = "chore/add-veracode-pipeline"
PATH = "Jenkinsfile"
COMMIT_MSG = "ci: add Veracode security pipeline (shared library)"
PR_TITLE = "Add Veracode security pipeline"

REQUEST_TIMEOUT = 30          # seconds per HTTP call
MAX_RETRIES = 4               # transient failures per call
WRITE_PAUSE = 0.3             # gentle pacing between write calls

JENKINSFILE_TEMPLATE = """\
// Veracode security scans (SCA + IaC/secrets every build, SAST/Policy on
// the default branch after a merge). Logic lives in the shared library; this only pins it.
@Library('veracode-pipeline@{lib}') _

veracodePipeline()
"""

PR_BODY = (
    "Adds a 2-line `Jenkinsfile` that invokes the central `veracode-pipeline` "
    "shared library. All scan logic lives in the library; this file only pins "
    "the version. No build behavior of this repo is changed."
)


DELETE_BRANCH    = "chore/remove-veracode-pipeline"
DELETE_COMMIT    = "ci: remove Veracode security pipeline"
DELETE_PR_TITLE  = "Remove Veracode security pipeline"
DELETE_PR_BODY   = (
    "Removes the `Jenkinsfile` that invoked the central `veracode-pipeline` "
    "shared library. After merging, Veracode scanning will no longer run on "
    "this repo."
)


class GitHub:
    def __init__(self, token, api_base):
        self.token = token
        self.api = api_base.rstrip("/")

    # ----- transport with retries, timeout, rate-limit handling ----- #
    def _req(self, method, path, body=None):
        url = path if path.startswith("http") else f"{self.api}{path}"
        data = json.dumps(body).encode() if body is not None else None

        for attempt in range(1, MAX_RETRIES + 1):
            req = urllib.request.Request(url, data=data, method=method)
            req.add_header("Authorization", f"Bearer {self.token}")
            req.add_header("Accept", "application/vnd.github+json")
            req.add_header("X-GitHub-Api-Version", "2022-11-28")
            if data:
                req.add_header("Content-Type", "application/json")
            try:
                with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as resp:
                    raw = resp.read()
                    return resp.status, (json.loads(raw) if raw else {}), resp.headers
            except urllib.error.HTTPError as e:
                raw = e.read()
                try:
                    payload = json.loads(raw) if raw else {}
                except ValueError:
                    payload = {"message": raw.decode("utf-8", "replace")}
                if attempt < MAX_RETRIES and self._retryable(e.code, payload, e.headers):
                    self._backoff(e.code, e.headers, attempt, payload)
                    continue
                return e.code, payload, e.headers
            except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:
                if attempt < MAX_RETRIES:
                    self._backoff(None, None, attempt, {"message": str(e)})
                    continue
                return 0, {"message": f"network error: {e}"}, {}
        return 0, {"message": "exhausted retries"}, {}

    @staticmethod
    def _retryable(code, payload, headers):
        if code in (500, 502, 503, 504):
            return True
        if code in (403, 429):
            msg = str(payload).lower()
            if "secondary rate limit" in msg or "abuse" in msg:
                return True
            if headers and headers.get("Retry-After"):
                return True
            if headers and headers.get("X-RateLimit-Remaining") == "0":
                return True
        return False

    @staticmethod
    def _backoff(code, headers, attempt, payload):
        wait = None
        if headers:
            ra = headers.get("Retry-After")
            if ra:
                try:
                    wait = int(ra)
                except ValueError:
                    wait = None
            if wait is None and headers.get("X-RateLimit-Remaining") == "0":
                reset = headers.get("X-RateLimit-Reset")
                if reset:
                    wait = max(5, int(reset) - int(time.time()))
        if wait is None:
            wait = min(60, (2 ** attempt)) + random.uniform(0, 1.5)
        reason = str(payload.get("message", code))[:80]
        print(f"  transient ({reason}); sleeping {wait:.1f}s "
              f"[attempt {attempt}/{MAX_RETRIES}]", file=sys.stderr)
        time.sleep(wait)

    def paginate(self, path):
        url = f"{self.api}{path}"
        while url:
            status, payload, headers = self._req("GET", url)
            if status >= 400:
                raise RuntimeError(f"GET {url} -> {status}: {payload}")
            for item in payload:
                yield item
            url = self._next_link(headers.get("Link"))

    @staticmethod
    def _next_link(link_header):
        if not link_header:
            return None
        for part in link_header.split(","):
            seg = part.split(";")
            if len(seg) >= 2 and 'rel="next"' in seg[1]:
                return seg[0].strip().strip("<>")
        return None


def run(args):
    gh = GitHub(args.token, args.api_base)
    include = set(args.include or [])
    # Repos that are themselves Veracode integrations or platform tooling --
    # not product repos and should never receive a Jenkinsfile PR.
    DEFAULT_EXCLUDE = {
        "veracode",           # Veracode GitHub integration app repo
        "veracode-pipeline",  # shared library (this integration)
        "jenkins-platform",   # platform automation (this integration)
    }
    exclude = DEFAULT_EXCLUDE | set(args.exclude or [])
    audit = open(args.audit_log, "a", encoding="utf-8") if args.audit_log else None

    grand = {"created": [], "skipped": [], "failed": []}
    try:
        for org in args.orgs:
            print(f"\n==== org: {org} ====")
            per = {"created": [], "skipped": [], "failed": []}
            try:
                process_org(gh, org, args, include, exclude, per, audit)
            except Exception as e:  # org-level failure must not abort other orgs
                per["failed"].append((org, f"org-level error: {e}"))
                print(f"  {org}: ORG FAILED {e}", file=sys.stderr)
            summarize(org, per)
            for k in grand:
                grand[k].extend(per[k])
    finally:
        if audit:
            audit.close()

    print("\n==== grand total ====")
    print(f"changed / would change: {len(grand['created'])}")
    print(f"skipped: {len(grand['skipped'])}")
    print(f"failed:  {len(grand['failed'])}")
    return 0 if not grand["failed"] else 1


def process_org(gh, org, args, include, exclude, per, audit):
    for repo in gh.paginate(f"/orgs/{org}/repos?per_page=100&type=all"):
        name = repo["name"]
        full = repo["full_name"]
        if include and name not in include:
            continue
        if name in exclude:
            per["skipped"].append((full, "excluded"))
            continue
        if args.skip_archived and repo.get("archived"):
            per["skipped"].append((full, "archived"))
            continue
        if args.skip_forks and repo.get("fork"):
            per["skipped"].append((full, "fork"))
            continue
        if repo.get("size", 0) == 0:
            per["skipped"].append((full, "empty repo"))
            continue

        default_branch = repo.get("default_branch") or "main"

        if args.delete:
            # --delete mode: open a PR to remove the Jenkinsfile
            status, _, _ = gh._req("GET", f"/repos/{full}/contents/{PATH}?ref={default_branch}")
            if status != 200:
                per["skipped"].append((full, "no Jenkinsfile -- nothing to delete"))
                continue
            if args.dry_run:
                per["created"].append((full, "DRY-RUN would open delete PR"))
                _audit(audit, full, "delete", "dry-run", default_branch)
                continue
            try:
                ensure_delete_pr(gh, full, default_branch)
                per["created"].append((full, "delete PR open"))
                _audit(audit, full, "delete", "pr-open", default_branch)
                print(f"  {full}: delete PR open")
            except Blocked as e:
                per["skipped"].append((full, f"blocked: {e}"))
                _audit(audit, full, "delete", f"blocked: {e}", default_branch)
                print(f"  {full}: blocked by protection ({e})", file=sys.stderr)
            except Exception as e:
                per["failed"].append((full, str(e)))
                _audit(audit, full, "delete", f"failed: {e}", default_branch)
                print(f"  {full}: FAILED {e}", file=sys.stderr)
            time.sleep(WRITE_PAUSE)
            continue

        # Normal add mode
        status, _, _ = gh._req("GET", f"/repos/{full}/contents/{PATH}?ref={default_branch}")
        if status == 200:
            per["skipped"].append((full, "Jenkinsfile exists"))
            continue

        if args.dry_run:
            per["created"].append((full, f"DRY-RUN would {args.mode}"))
            _audit(audit, full, args.mode, "dry-run", default_branch)
            continue

        try:
            if args.mode == "direct":
                direct_commit(gh, full, default_branch, args.lib_version)
                per["created"].append((full, "committed to default branch"))
                _audit(audit, full, "direct", "committed", default_branch)
                print(f"  {full}: committed")
            else:
                ensure_pr(gh, full, default_branch, args.lib_version)
                per["created"].append((full, "PR open"))
                _audit(audit, full, "pr", "pr-open", default_branch)
                print(f"  {full}: PR open")
        except Blocked as e:
            per["skipped"].append((full, f"blocked: {e}"))
            _audit(audit, full, args.mode, f"blocked: {e}", default_branch)
            print(f"  {full}: blocked by protection ({e})", file=sys.stderr)
        except Exception as e:  # noqa: BLE001 - report and continue
            per["failed"].append((full, str(e)))
            _audit(audit, full, args.mode, f"failed: {e}", default_branch)
            print(f"  {full}: FAILED {e}", file=sys.stderr)
        time.sleep(WRITE_PAUSE)


class Blocked(Exception):
    """Write rejected by branch protection / rulesets. Not disabled, by design."""


def _blocked(status, payload):
    msg = str(payload).lower()
    return status in (409, 422) and (
        "protected" in msg or "ruleset" in msg or "required status" in msg
        or "not allowed to" in msg or "review" in msg
    )


def ensure_pr(gh, full, base_branch, lib_version):
    """Resumable: reuse an existing branch/file, ensure exactly one open PR."""
    owner = full.split("/", 1)[0]

    status, ref, _ = gh._req("GET", f"/repos/{full}/git/ref/heads/{base_branch}")
    if status != 200:
        raise RuntimeError(f"cannot read base ref: {status} {ref}")
    base_sha = ref["object"]["sha"]

    status, _, _ = gh._req("GET", f"/repos/{full}/git/ref/heads/{BRANCH}")
    if status != 200:
        status, payload, _ = gh._req(
            "POST", f"/repos/{full}/git/refs",
            {"ref": f"refs/heads/{BRANCH}", "sha": base_sha},
        )
        if status not in (200, 201):
            raise RuntimeError(f"branch create failed: {status} {payload}")

    status, _, _ = gh._req("GET", f"/repos/{full}/contents/{PATH}?ref={BRANCH}")
    if status != 200:
        content = JENKINSFILE_TEMPLATE.format(lib=lib_version)
        status, payload, _ = gh._req(
            "PUT", f"/repos/{full}/contents/{urllib.parse.quote(PATH)}",
            {"message": COMMIT_MSG,
             "content": base64.b64encode(content.encode()).decode(),
             "branch": BRANCH},
        )
        if status not in (200, 201):
            raise RuntimeError(f"file create failed: {status} {payload}")

    q = urllib.parse.quote(f"{owner}:{BRANCH}")
    status, prs, _ = gh._req("GET", f"/repos/{full}/pulls?state=open&head={q}")
    if status == 200 and isinstance(prs, list) and prs:
        return  # PR already open

    status, payload, _ = gh._req(
        "POST", f"/repos/{full}/pulls",
        {"title": PR_TITLE, "head": BRANCH, "base": base_branch, "body": PR_BODY},
    )
    if status not in (200, 201):
        raise RuntimeError(f"PR create failed: {status} {payload}")


def ensure_delete_pr(gh, full, base_branch):
    """Open a PR that removes the Jenkinsfile. Resumable: reuses existing branch/PR."""
    owner = full.split("/", 1)[0]

    # Get base SHA
    status, ref, _ = gh._req("GET", f"/repos/{full}/git/ref/heads/{base_branch}")
    if status != 200:
        raise RuntimeError(f"cannot read base ref: {status} {ref}")
    base_sha = ref["object"]["sha"]

    # Create delete branch if it does not exist
    status, _, _ = gh._req("GET", f"/repos/{full}/git/ref/heads/{DELETE_BRANCH}")
    if status != 200:
        status, payload, _ = gh._req(
            "POST", f"/repos/{full}/git/refs",
            {"ref": f"refs/heads/{DELETE_BRANCH}", "sha": base_sha},
        )
        if status not in (200, 201):
            raise RuntimeError(f"branch create failed: {status} {payload}")

    # Delete the file on the branch if it is still there
    status, file_info, _ = gh._req("GET", f"/repos/{full}/contents/{PATH}?ref={DELETE_BRANCH}")
    if status == 200:
        file_sha = file_info["sha"]
        status, payload, _ = gh._req(
            "DELETE", f"/repos/{full}/contents/{urllib.parse.quote(PATH)}",
            {"message": DELETE_COMMIT, "sha": file_sha, "branch": DELETE_BRANCH},
        )
        if _blocked(status, payload):
            raise Blocked(payload.get("message", "protected branch"))
        if status not in (200, 201, 204):
            raise RuntimeError(f"file delete failed: {status} {payload}")

    # Ensure an open PR exists
    q = urllib.parse.quote(f"{owner}:{DELETE_BRANCH}")
    status, prs, _ = gh._req("GET", f"/repos/{full}/pulls?state=open&head={q}")
    if status == 200 and isinstance(prs, list) and prs:
        return  # PR already open

    status, payload, _ = gh._req(
        "POST", f"/repos/{full}/pulls",
        {"title": DELETE_PR_TITLE, "head": DELETE_BRANCH,
         "base": base_branch, "body": DELETE_PR_BODY},
    )
    if status not in (200, 201):
        raise RuntimeError(f"PR create failed: {status} {payload}")



def direct_commit(gh, full, base_branch, lib_version):
    """Commit to the default branch. Respects protection: blocked -> Blocked."""
    content = JENKINSFILE_TEMPLATE.format(lib=lib_version)
    status, payload, _ = gh._req(
        "PUT", f"/repos/{full}/contents/{urllib.parse.quote(PATH)}",
        {"message": COMMIT_MSG,
         "content": base64.b64encode(content.encode()).decode(),
         "branch": base_branch},
    )
    if _blocked(status, payload):
        raise Blocked(payload.get("message", "protected branch"))
    if status not in (200, 201):
        raise RuntimeError(f"direct commit failed: {status} {payload}")


def _audit(audit, repo, mode, result, branch):
    if not audit:
        return
    audit.write(json.dumps({
        "ts": int(time.time()), "repo": repo, "mode": mode,
        "branch": branch, "result": result,
    }) + "\n")
    audit.flush()


def summarize(org, per):
    print(f"-- summary {org}: changed {len(per['created'])}, "
          f"skipped {len(per['skipped'])}, failed {len(per['failed'])}")
    for f, why in per["created"]:
        print(f"  + {f}  ({why})")
    for f, why in per["skipped"]:
        print(f"  - {f}  ({why})")
    for f, why in per["failed"]:
        print(f"  ! {f}  ({why})")


def resolve_orgs(orgs_flag, orgs_file, parser):
    """Combine --orgs and --orgs-file into a deduped, order-preserving list."""
    orgs = list(orgs_flag or [])
    if orgs_file:
        try:
            with open(orgs_file, encoding="utf-8") as fh:
                for raw in fh:
                    line = raw.strip()
                    if not line or line.startswith("#"):
                        continue
                    orgs.append(line)
        except OSError as exc:
            parser.error(f"cannot read --orgs-file {orgs_file}: {exc}")

    seen, deduped = set(), []
    for o in orgs:
        if o not in seen:
            seen.add(o)
            deduped.append(o)
    if not deduped:
        parser.error("provide --orgs and/or a non-empty --orgs-file")
    return deduped


def parse_args(argv):
    p = argparse.ArgumentParser(description="Bulk-add Veracode Jenkinsfile across GitHub orgs")
    p.add_argument("--orgs", nargs="+", help="one or more org names")
    p.add_argument("--orgs-file", help="path to a file with one org per line "
                                       "(blank lines and # comments ignored)")
    p.add_argument("--lib-version", required=True, help="shared library tag, e.g. v1")
    p.add_argument("--mode", choices=["pr", "direct"], default="pr",
                   help="pr (default, opens a PR) or direct (commit to default "
                        "branch; protected branches are skipped, never disabled)")
    p.add_argument("--token", default=None, help="defaults to $GITHUB_TOKEN")
    p.add_argument("--api-base", default="https://api.github.com")
    p.add_argument("--include", nargs="*", help="only these repo names (matched across all orgs)")
    p.add_argument("--exclude", nargs="*", help="skip these repo names")
    p.add_argument("--skip-archived", action="store_true")
    p.add_argument("--skip-forks", action="store_true")
    p.add_argument("--delete", action="store_true",
                   help="open a PR to REMOVE the Jenkinsfile from each repo "
                        "that has one (reverse of the default add operation). "
                        "Use --dry-run first to see what would be affected.")
    p.add_argument("--audit-log", help="append per-repo JSONL audit records to this file")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--yes", action="store_true",
                   help="confirm a non-dry-run write across the listed orgs")
    args = p.parse_args(argv)

    args.orgs = resolve_orgs(args.orgs, args.orgs_file, p)

    if not args.token:
        args.token = os.environ.get("GITHUB_TOKEN")
    if not args.token:
        p.error("provide --token or set GITHUB_TOKEN")
    if not args.dry_run and not args.yes:
        p.error("refusing to write without --yes. Run --dry-run first, then add --yes.")
    return args


if __name__ == "__main__":
    sys.exit(run(parse_args(sys.argv[1:])))
