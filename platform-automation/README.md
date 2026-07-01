# Platform Automation

What the CI/platform team applies to the controller and runs once per org.

---

## Prerequisites

| What | Where to get it |
|------|----------------|
| Veracode API ID + Key | platform.veracode.com → API Credentials |
| GitHub PAT (`scm-readonly`) | GitHub → Settings → Developer Settings → Personal access tokens. Scopes: `repo`, `read:org`. Must be a member of every org being scanned. |
| GitHub PAT (push token, `GITHUB_TOKEN`) | Same or separate PAT with `repo` scope. Used only for the one-time bulk-PR rollout, never stored in Jenkins. |
| Jenkins URL + admin credentials | Needed by `rollout.py` to configure the controller via REST API |

---

## Quickstart - one-shot setup with rollout.py

`rollout.py` is the single entry point (`rollout.sh` / `rollout.ps1` are equivalent, for teams without Python -- same steps, same CONFIG block, see "Files in this directory" below).

The committed file has placeholder values only and is safe to commit as-is. Before running it for real, copy it locally to a `rollout.example.*` filename (already covered by `.gitignore`, so it can never be committed with real values in it), fill in the CONFIG block in that copy, and run the copy:

```bash
cp rollout.py rollout.example.py
# edit rollout.example.py: fill in PLATFORM_ORG, SCAN_ORGS, credentials, JENKINS_URL
python3 rollout.example.py
```

**What it does in one run:**
1. Creates the `veracode-pipeline` repo in your platform org, pushes the shared library, tags it `v1`
2. Creates the `jenkins-platform` repo and pushes all platform automation
3. Upserts `veracode-api-id`, `veracode-api-key`, and `scm-readonly` credentials in Jenkins
4. Configures the GitHub Server entry in Jenkins (no webhook registration and no polling -- this deployment is ad hoc only, so Jenkins never receives inbound calls from GitHub and never reaches out on its own schedule either)
5. Registers the `veracode-pipeline` shared library on the controller pointing at your org
6. Runs `veracode-onboard.groovy` via the Script Console - creates one Organization Folder per org, mints each org's Veracode SCA workspace token, binds it as `srcclr-api-token`

Re-running is safe: existing repos are skipped, credentials are upserted, the onboarding script is idempotent.

---

## Step-by-step (manual alternative to rollout.py)

### Step 1 - Create the two platform repos in GitHub

| Repo | Contents |
|------|----------|
| `veracode-pipeline` | The `library-repo/` directory from this repo |
| `jenkins-platform` | The `platform-automation/` directory from this repo |

```bash
# 1a. Create and push veracode-pipeline
cd library-repo
git init && git add -A
git commit -m "change: initial library commit"
git remote add origin https://github.com/<YOUR-ORG>/veracode-pipeline.git
git push -u origin HEAD:main
git tag v1 && git push origin v1

# 1b. Create and push jenkins-platform
cd ../platform-automation
git init && git add -A
git commit -m "change: initial platform commit"
git remote add origin https://github.com/<YOUR-ORG>/jenkins-platform.git
git push -u origin HEAD:main
```

### Step 2 - Add credentials to Jenkins

**Manage Jenkins → Credentials → System → Global**, add:

| ID | Type | Value |
|----|------|-------|
| `veracode-api-id` | Secret text | Your Veracode API ID |
| `veracode-api-key` | Secret text | Your Veracode API Key |
| `scm-readonly` | Username with password | Username: GitHub service account. Password: PAT with `repo` + `read:org` scopes |

`srcclr-api-token` is NOT added here - it is minted per org by `veracode-onboard.groovy` in Step 4.

### Step 3 - Register the shared library

**Manage Jenkins → System → Global Pipeline Libraries**, add:

| Field | Value |
|-------|-------|
| Name | `veracode-pipeline` |
| Default version | `v1` |
| Load implicitly | off |
| Allow default version override | on |
| Retrieval method | Modern SCM → Git |
| Repository URL | `https://github.com/<YOUR-ORG>/veracode-pipeline.git` |
| Credentials | `scm-readonly` |

Alternatively apply `jenkins.casc.yaml` via the Configuration as Code plugin after setting:

```bash
export VERACODE_API_ID=<your-api-id>
export VERACODE_API_KEY=<your-api-key>
export SCM_SCAN_USER=<github-username>
export SCM_SCAN_TOKEN=<github-pat>
export VERACODE_LIBRARY_REPO_URL=https://github.com/<YOUR-ORG>/veracode-pipeline.git
```

### Step 4 - Run veracode-onboard.groovy

**Manage Jenkins → Script Console**, paste `veracode-onboard.groovy`, set `ORGS` at the top:

```groovy
@Field List<String> ORGS = [
    'your-github-org',
]
```

This script:
1. Creates a `veracode` parent folder and one Organization Folder per org
2. Finds or creates that org's Veracode SCA workspace
3. Mints a fresh Jenkins SCA agent token from Veracode
4. Binds it as the `srcclr-api-token` folder credential
5. Applies a discovery trigger policy: `Scan Organization` auto-builds only `main`/`master` on first discovery - PR branches and feature branches are registered as jobs but never auto-queued
Re-running is safe - folders and credentials converge, the SCA token is rotated on each run.

**Adding a new org later:** add a line to `ORGS` and re-run. Nothing else.

### Step 5 - Open Jenkinsfile PRs across each org

```bash
export GITHUB_TOKEN=<push-pat-with-repo-scope>

# Dry run first
python3 bulk_add_jenkinsfile.py --orgs <YOUR-ORG> --lib-version v1 --dry-run

# Execute
python3 bulk_add_jenkinsfile.py --orgs <YOUR-ORG> --lib-version v1 \
    --skip-archived --skip-forks --yes
```

Review and merge the PRs. Once merged, trigger a scan (Jenkins UI or `trigger-scan.sh`/`.ps1`) to discover each repo and start scanning; nothing runs automatically.

**To remove Jenkinsfiles later** (offboard an org):
```bash
python3 bulk_add_jenkinsfile.py --orgs <YOUR-ORG> --lib-version v1 --delete --dry-run
python3 bulk_add_jenkinsfile.py --orgs <YOUR-ORG> --lib-version v1 --delete --yes
```

---

## Jenkins UI -- how the buttons work

| Button | What it does | Script equivalent |
|--------|-------------|--------------------|
| **Scan Organization** | Indexes the org, discovers repos with a Jenkinsfile, registers them as pipeline jobs, and triggers a build on the default branch of any newly discovered repo | `trigger-scan.sh --org <org>` |
| **Scan Repository Now** | Same as above for one repo | `trigger-scan.sh --org <org> --repo <repo>` |
| **Build Now** (on a branch job) | Triggers a scan on that specific branch immediately | `trigger-scan.sh --org <org> --repo <repo> --branch <branch>` |

`trigger-scan.ps1` is the PowerShell equivalent. Both need the same `JENKINS_URL` reachability as `rollout.py`/`.sh`/`.ps1`.

---

## Agent requirements for SAST

SAST compiles the repo's source code via Docker automatically - detects the language and pulls the right image. Same approach as the Veracode GitHub Actions workflow on `ubuntu-latest`.

**Requirements:**
- Docker installed on the Jenkins agent
- `docker-workflow` plugin installed on the controller
- Agent can reach Docker Hub (or your internal registry)

If Jenkins itself runs in Docker, mount the host socket:
```yaml
volumes:
  - /var/run/docker.sock:/var/run/docker.sock
```

If Docker is not available, install the language toolchain directly on the agent. See `library-repo/README.md` for the full language-to-image map and override options.

---

## Files in this directory

| File | Purpose |
|------|---------|
| `rollout.py` | Safe template with dummy values - commit this. Copy locally to `rollout.example.py` (gitignored), fill in real values, run the copy |
| `rollout.sh` | Bash equivalent of `rollout.py`, same convention (copy to `rollout.example.sh`) -- for teams without Python |
| `rollout.ps1` | PowerShell equivalent of `rollout.py`, same convention (copy to `rollout.example.ps1`) -- for teams without Python |
| `jenkins.casc.yaml` | JCasC: registers the shared library and root credentials (alternative to rollout.py steps 2-3). Requires `VERACODE_LIBRARY_REPO_URL` set as an env var before applying |
| `veracode-onboard.groovy` | System Groovy script: creates org folders, mints + binds SCA tokens. No automatic trigger is configured -- ad hoc only |
| `bulk_add_jenkinsfile.py` | Opens PRs adding the 2-line Jenkinsfile to every repo in an org. `--delete` to reverse |
| `trigger-scan.sh` | Ad hoc scan trigger (org / repo / branch) from a terminal, same effect as the Jenkins UI buttons |
| `trigger-scan.ps1` | PowerShell equivalent of `trigger-scan.sh` |
| `bind-sca-tokens.groovy` | Legacy helper (superseded by `veracode-onboard.groovy`) |
| `orgfolders.jobdsl.groovy` | Legacy Job DSL seed (superseded by `veracode-onboard.groovy`) |
