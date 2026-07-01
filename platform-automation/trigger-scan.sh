#!/usr/bin/env bash
#
# trigger-scan.sh -- On-demand Veracode/Jenkins scan trigger.
#
# No webhook and no periodic poll are configured (see SOLUTION.md). Scans
# only run when explicitly triggered. Use this script to trigger one from a
# terminal instead of the Jenkins UI.
# Must be run from somewhere that can reach JENKINS_URL (same constraint as
# rollout.sh: the Jenkins controller, or a bastion on the same network).
#
# Requires: bash, curl, jq.
#
# Usage:
#   ./trigger-scan.sh --org my-org
#       Rescan the whole org: discover new/renamed/deleted repos, trigger
#       builds on anything changed since the last scan. Same as clicking
#       "Scan Organization Now" in the Jenkins UI.
#
#   ./trigger-scan.sh --org my-org --repo my-repo
#       Rescan just that repo: discover new/changed branches and PRs,
#       trigger builds on anything changed. Same as "Scan Repository Now".
#       Faster than a full org rescan.
#
#   ./trigger-scan.sh --org my-org --repo my-repo --branch main
#       Skip scanning entirely and trigger an immediate build of that one
#       branch job. Fastest option if you already know what you want built.
#
set -euo pipefail

# ==============================================================================
# CONFIGURE
# ==============================================================================
JENKINS_URL="http://your-jenkins-host:8080"
JENKINS_USER="admin"
JENKINS_FOLDER=""   # must match JENKINS_FOLDER used in rollout.sh, if any
# JENKINS_TOKEN read from env var; falls back to JENKINS_USER.
# ==============================================================================

JENKINS_TOKEN="${JENKINS_TOKEN:-$JENKINS_USER}"

for bin in curl jq; do
  command -v "$bin" >/dev/null 2>&1 || { echo "ERROR: '$bin' is required but not installed." >&2; exit 1; }
done

ORG=""
REPO=""
BRANCH=""

while [ $# -gt 0 ]; do
  case "$1" in
    --org)    ORG="$2"; shift 2 ;;
    --repo)   REPO="$2"; shift 2 ;;
    --branch) BRANCH="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,29p' "$0"
      exit 0
      ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

[ -n "$ORG" ] || { echo "ERROR: --org is required." >&2; exit 1; }
[ -z "$BRANCH" ] || [ -n "$REPO" ] || { echo "ERROR: --branch requires --repo." >&2; exit 1; }

COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

jenkins_crumb() {
  local resp field crumb
  resp=$(curl -sS -c "$COOKIE_JAR" -u "${JENKINS_USER}:${JENKINS_TOKEN}" \
    "${JENKINS_URL}/crumbIssuer/api/json" || true)
  field=$(echo "$resp" | jq -r '.crumbRequestField // empty' 2>/dev/null || true)
  crumb=$(echo "$resp" | jq -r '.crumb // empty' 2>/dev/null || true)
  [ -n "$field" ] && [ -n "$crumb" ] && echo "${field}:${crumb}"
  return 0
}

folder_prefix=""
if [ -n "$JENKINS_FOLDER" ]; then
  folder_path="${JENKINS_FOLDER%/}"
  folder_path="${folder_path#/}"
  folder_prefix="/job/${folder_path//\//\/job\/}"
fi

if [ -n "$BRANCH" ]; then
  target_url="${JENKINS_URL}${folder_prefix}/job/${ORG}/job/${REPO}/job/${BRANCH}/build"
  desc="build of ${ORG}/${REPO}@${BRANCH}"
elif [ -n "$REPO" ]; then
  target_url="${JENKINS_URL}${folder_prefix}/job/${ORG}/job/${REPO}/build"
  desc="repository scan of ${ORG}/${REPO}"
else
  target_url="${JENKINS_URL}${folder_prefix}/job/${ORG}/build"
  desc="organization scan of ${ORG}"
fi

crumb_pair=$(jenkins_crumb)
crumb_args=()
if [ -n "$crumb_pair" ]; then
  field="${crumb_pair%%:*}"
  crumb="${crumb_pair#*:}"
  crumb_args=(-H "${field}: ${crumb}")
fi

echo "Triggering ${desc} ..."
status=$(curl -sS -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" \
  -u "${JENKINS_USER}:${JENKINS_TOKEN}" "${crumb_args[@]}" \
  -X POST "$target_url")

case "$status" in
  200|201|302) echo "  Triggered (HTTP ${status})." ;;
  404)
    echo "  ERROR: HTTP 404. Check the org/repo/branch names and JENKINS_FOLDER, or that" >&2
    echo "  the org has already been discovered by at least one scan before." >&2
    exit 1
    ;;
  *)
    echo "  ERROR: HTTP ${status} from ${target_url}" >&2
    exit 1
    ;;
esac
