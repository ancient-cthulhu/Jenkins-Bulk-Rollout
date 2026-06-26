# Veracode + Jenkins Integration Solution Document

Open-source Jenkins, many orgs. Veracode SCA, IaC/secrets, and SAST run automatically on every repo, without modifying or risking any team's existing build.

---

## 1. Summary

We add Veracode scanning as a separate pipeline that runs beside each team's build, triggered by the same Git events. All logic lives in one shared library; each repo gets a 2-line `Jenkinsfile` that calls it. Jenkins Organization Folders auto-discover every repo per org.

- **SCA** (dependencies) and **IaC/secrets**: every build, on the checked-out source. No build needed, so they cover every repo immediately.
- **SAST**: the repo's default branch only, after a merge (never on PRs). Needs a build, so it is phased in per org.
- **SCM auth**: a single read-only GitHub PAT (service account) lets Jenkins discover and check out repos across the orgs. This is the fast path for now; per-org GitHub Apps are a planned hardening step (see the note in section 4).


---

## 2. Architecture

<p align="center">
  <img src="architecture.svg" alt="Veracode + Jenkins Architecture" width="1000">
  <br>
  <em>Reference architecture for centralized Jenkins-managed Veracode scanning across GitHub organizations.</em>
</p>

Connection legend:
- **C1** Controller discovers repos/branches/PRs in each org (read), using the shared scan account.
- **C2** SCM webhook (push/PR) triggers indexing and builds.
- **C3** Controller fetches the shared library at build time.
- **C4-C6** Agents download the Veracode CLI, SCA agent, and Java API wrapper.
- **C7** SCA results upload to the org's Veracode workspace.
- **C8** SAST/Policy upload to the per-repo Veracode app profile.
- **C9** Controller dispatches builds to the static agents.
- **C10** One-time per org: PRs add the 2-line `Jenkinsfile` (separate from the controller).

---

## 3. What we create, and where

| # | New artifact | Location | Purpose |
|---|--------------|----------|---------|
| 1 | `veracode-pipeline` repo | GitHub org (see below) | Shared library `vars/veracodePipeline.groovy`, tagged `v1` |
| 2 | `jenkins-platform` repo | GitHub org (see below) | JCasC, the `veracode-onboard` script, bulk-PR script |
| 3 | Library registration | Jenkins controller | Points Jenkins at repo 1, default version `v1` |
| 4 | Root credentials | Jenkins controller (root) | `veracode-api-id`, `veracode-api-key`, `scm-readonly` |
| 5 | Org folder per org | Jenkins controller | Created by `veracode-onboard`; discovers and scans each org's repos |
| 6 | Per-org SCA token | Each org folder | `srcclr-api-token`, minted from Veracode and bound by `veracode-onboard` |
| 7 | 2-line `Jenkinsfile` | Each existing repo | Calls the library (added by PR) |
| 8 | App profiles + workspaces | Veracode platform | One profile per repo; one workspace per org |

Only repos 1 and 2 are new. Existing product repos change by one file each.

### Where do repos 1 and 2 live?

Repos `veracode-pipeline` and `jenkins-platform` must live in a GitHub org that
the `scm-readonly` PAT has access to. 

**Two valid layouts:**

**Option A - Dedicated platform org (recommended for production)**

A separate org (e.g. `your-company-platform`) owns the two repos. The `scm-readonly`
PAT is a member of that org plus every product org being scanned. Clean separation
between platform infrastructure and product code.

**Option B - Same org as the scanned repos (simplest)**

The two repos live inside the org being scanned. One PAT, one org. Fine for a
pilot or single-org deployment.

In either case, set `PLATFORM_ORG` in `rollout.example.py` to whichever org
hosts these two repos, and set `SCAN_ORGS` to the list of orgs Jenkins will scan.
They can be the same value or different.

### The `scm-readonly` credential

This is a GitHub PAT stored in Jenkins as a **username + token** credential
(id: `scm-readonly`). It needs `repo` and `read:org` scopes and must be a member
of every org it touches. Jenkins uses it for three things:
1. Fetching the shared library (`veracode-pipeline`) at build time
2. Discovering repos in each scanned org (Organization Folder)
3. Checking out source code on agents

---

## 4. Requirements

**Jenkins plugins:** `pipeline-groovy-lib`, `workflow-aggregator`, `workflow-multibranch`, `pipeline-model-definition`, `cloudbees-folder`, `github-branch-source`, `credentials`, `credentials-binding`, `plain-credentials`, `configuration-as-code`, `ws-cleanup`, `timestamper`.
> The agents attach over SSH or JNLP, so the `kubernetes` plugin is not needed.

**Credentials:**

| ID | Type | Stored at | Used for |
|----|------|-----------|----------|
| `veracode-api-id` | Secret text | Jenkins root | SAST/Policy + IaC upload (HMAC) |
| `veracode-api-key` | Secret text | Jenkins root | same |
| `srcclr-api-token` | Secret text | each org folder | SCA upload to that org's workspace (minted + bound by `veracode-onboard`) |
| `scm-readonly` | Username + token | Jenkins root | GitHub org discovery + library fetch |

All of the above live in the Jenkins credential store (encrypted at rest with the controller key). No external secrets manager is used. The SCA token is folder-scoped per org; the others are root. The `veracode-onboard` script reads `veracode-api-id`/`veracode-api-key`, mints each org's Jenkins SCA token from the Veracode API, and writes the per-org `srcclr-api-token`; wherever it runs (controller or a small admin agent) needs egress to `api.veracode.com`.

**SCM permissions (GitHub PAT, service account):**
- *Scan account* (`scm-readonly`, in Jenkins): GitHub classic PAT with `repo` and `read:org`, plus `admin:org_hook` only if Jenkins auto-registers the org webhook (omit it and create the webhook manually otherwise). The account must be a member of each org it scans, and is reused to fetch the shared library.
- *Push token* (rollout script only, via `GITHUB_TOKEN`, not stored in Jenkins): GitHub PAT with `repo`.

> Planned hardening (revisit after go-live): replace the shared PAT with one GitHub App per org, stored folder-scoped, for per-org isolation, short-lived auto-rotated tokens, app-level webhooks (drops `admin:org_hook`), and no shared account or seat.

**Agents:** Use labels so SAST jobs land on agents that carry each language's build toolchain (SAST autopackaging compiles); SCA + IaC are light and can run on any agent. Throttle first-run indexing and roll out in waves so the org folders do not saturate the pool. Optional optimization (not required now): pre-cache the Veracode CLI, the SCA agent, and the Java API wrapper on the agents to cut egress and build time. The library already prefers an on-PATH `veracode` binary, so a cached install is picked up automatically; pin the Java wrapper to a cached jar instead of resolving the latest each run.

**Network egress from agents:** `tools.veracode.com`, `sca-downloads.veracode.com`, `repo1.maven.org`, your Veracode region API host (`api.veracode.com` / `analysiscenter.veracode.com`, or EU/Federal), your GitHub host (`github.com` or GitHub Enterprise), and the language package mirrors the SAST build resolves. The Veracode tool downloads (CLI, SCA agent, API wrapper) can be cached or pre-installed on the agents to avoid re-fetching every build (optional, see Agents).

**Inbound webhook (one per org):** GitHub `/github-webhook/` to the controller.

---

## 5. Rollout plan (in order)

### Phase 0: Pre-reqs
- Confirm the SAST worker pool carries each language's build toolchain.
- Have ready: Veracode API id/key; a GitHub PAT for the scan account (`scm-readonly`,
  scopes: `repo`, `read:org`) and a push token for the one-time repo rollout (scope: `repo`).
- Install the plugins listed in section 4 on the controller.
- Stand up agents (label SAST-capable ones); confirm egress to the URLs in section 4.

### Phase 1: One-shot setup (rollout.py)

Copy `platform-automation/rollout.example.py` to `rollout.py` (gitignored -- safe to put real values in), fill in the CONFIG block at the top, then run:

```bash
python3 rollout.py
```

The CONFIG block covers:
- `PLATFORM_ORG` -- the GitHub org that will host `veracode-pipeline` and `jenkins-platform`
- `SCAN_ORGS` -- list of orgs Jenkins will scan (can be the same as `PLATFORM_ORG`)
- `GITHUB_TOKEN` -- PAT with `repo` scope for creating the two platform repos
- `VC_API_ID` / `VC_API_KEY` -- Veracode API credentials
- `SCM_USER` / `SCM_TOKEN` -- the `scm-readonly` GitHub PAT (stored in Jenkins)
- `JENKINS_URL` / `JENKINS_USER` / `JENKINS_TOKEN` -- Jenkins admin access

In a single run this script:
1. Creates the `veracode-pipeline` repo in your platform org, pushes the shared library, and tags it `v1`
2. Creates the `jenkins-platform` repo and pushes all platform automation
3. Upserts `veracode-api-id`, `veracode-api-key`, and `scm-readonly` credentials in Jenkins
4. Configures the GitHub Server entry in Jenkins (enables webhook auto-registration)
5. Registers the `veracode-pipeline` shared library on the controller
6. Runs `veracode-onboard.groovy` via the Script Console, which creates one Organization Folder per org, mints each org's Veracode SCA workspace token, and binds it as `srcclr-api-token`

Re-running is safe: existing repos are skipped, credentials are upserted, the onboarding script is idempotent.

### Phase 2: Deliver Jenkinsfiles to repos

Per org, run the bulk-PR script (dry-run first), then merge:

```bash
export GITHUB_TOKEN=<push-token>
python3 bulk_add_jenkinsfile.py --orgs <YOUR-ORG> --lib-version v1 --dry-run
python3 bulk_add_jenkinsfile.py --orgs <YOUR-ORG> --lib-version v1 --skip-archived --skip-forks --yes
```

The script is idempotent (skips repos that already have the file or branch).

### Phase 3: Pilot
- Point one org at non-prod repos, pinned to `@veracode-pipeline@main` (canary).
- Verify on a feature branch / PR: SCA + IaC run; no SAST.
- Verify on a default-branch merge: SAST/Policy runs; results appear in the platform;
  SCA lands in the right workspace; IaC JSON archived.

### Phase 4: Roll out

This phase happens after the pilot is confirmed green on one org.

**Enable SCA + IaC across all orgs (immediate, no toolchain dependency)**

SCA and IaC/secrets run directly on the checked-out source -- they do not compile
the code, so no build toolchain is needed on the agent. They work on every repo
immediately after the Jenkinsfile PR is merged. Run the bulk-PR script for each
remaining org and merge the PRs. Scanning starts on the next push.

**Enable SAST org by org as the toolchains on the SAST pool are confirmed**

SAST requires the source code to be compiled. The pipeline handles this
automatically using Docker -- it detects the language and pulls the right container
image (Maven for Java, .NET SDK for C#, etc.). Before enabling SAST on an org,
confirm that:
- Docker is available on the Jenkins agent (see library README -- Agent requirements)
- The `docker-workflow` plugin is installed on the controller
- The agent can reach Docker Hub (or your internal registry) to pull images

If Docker is not available, the agent must have the language toolchain installed
directly, or repos must supply their own `buildSteps` in the Jenkinsfile.

Enable SAST for one org first, watch the first few builds in Jenkins and in the
Veracode platform, confirm artifacts are being uploaded and scans are completing,
then move to the next org.

**Promote all orgs from canary to the pinned `@v1`**

During the pilot, the test org's Jenkinsfile was pinned to `@main` (the canary
branch) so you could iterate on the library without tagging. Once everything is
confirmed working, change all Jenkinsfiles to point at the stable tag:

```groovy
@Library('veracode-pipeline@v1') _
veracodePipeline()
```

The bulk-PR script already uses `v1` by default (`--lib-version v1`). Any repo
still pointing at `@main` should be updated. From this point on, library changes
go through a proper tag cycle: develop on a branch, canary one org with
`@veracode-pipeline@<branch>`, tag as `v2` when confirmed, promote orgs in waves.
Roll back any org by re-pinning its Jenkinsfile to `@v1` -- the old tag is always
intact.


### Phase 5: Operate
- Ship library changes as new tags (`v2`), promote orgs in waves; roll back by re-pinning.
- Monitor org-folder scan health, agent saturation, and Veracode API throttling on
  first-run waves.
- Rotate the shared scan PAT on a schedule.
- Treat the Jenkins controller as the secret custodian: restrict admin access,
  encrypt backups, protect `$JENKINS_HOME/secrets`.

**Add a new org later:** add it to `SCAN_ORGS` in `rollout.py` and re-run it
(creates the folder, mints the token, binds the credential), then run the bulk-PR
script for the new org. The scan PAT must be a member of the new org. Nothing else.

---

## 6. Scan behavior (what runs, when)

| Trigger | SCA | IaC/secrets | SAST/Policy |
|---------|-----|-------------|-------------|
| PR / feature branch | yes | yes | no |
| Merge to default branch | yes | yes | yes |

- SCA and IaC/secrets are non-gating by default (report, do not fail the build).
- SAST runs only on the default branch, detected via `BRANCH_IS_PRIMARY`; PRs are always excluded.
- App profile per repo = `org/repo`. SCA results land in the per-org workspace selected by that org's token.
- SAST autopackages by detecting the language and pulling a Docker container image matching the toolchain (Maven, .NET SDK, Node, etc.) -- same approach as the Veracode GitHub Actions workflow on `ubuntu-latest`. Requires Docker on the agent. See `library-repo/README.md` for the full language-to-image map.
- Incomplete or stuck SAST scans on the platform are cleared automatically via `-deleteincompletescan 1` on each upload.

### Jenkins UI buttons

| Button | Where | What it does |
|--------|-------|-------------|
| **Scan Organization** | Org folder | Indexes the org, discovers repos with a Jenkinsfile, registers them as pipeline jobs, and triggers a build on the default branch of newly discovered repos |
| **Scan Repository Now** | Repo inside org folder | Same as above for one repo |
| **Build Now** | Branch job (e.g. `main`) | Triggers a scan on that branch immediately |

---

## 7. Notes

- Runs as its own pipeline beside each team's build, never inside it, so it cannot break their builds.
- The only change to a product repo is one reviewed 2-line file, trivially reversible.
- Centrally versioned: pilot a change on one org, roll out in waves, roll back by re-pinning a tag.
- Standard open-source Jenkins. No commercial platform introduced.
- Secrets stay in the Jenkins credential store, encrypted at rest, with SCA tokens folder-scoped per org; no third-party secret service is introduced.
- For now SCM access uses one shared read-only PAT. It is read-only and reviewed, but it is a single broad credential, so moving to per-org GitHub Apps is the planned next step.
