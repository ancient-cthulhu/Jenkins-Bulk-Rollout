# Veracode Security Pipeline for Jenkins

Automated Veracode security scanning across GitHub organizations, delivered as a Jenkins Shared Library. Drop a 2-line `Jenkinsfile` into any repo and it gets SCA, IaC/secrets, and SAST on every push, beside the team's existing build.

<p align="center">
  <img src="architecture.svg" alt="Veracode + Jenkins Architecture" width="1000">
</p>

---

## What it does

Every repo that opts in gets three scans running automatically:

| Scan | When | What it covers |
|------|------|----------------|
| **SCA** (Software Composition Analysis) | Every push, every branch | Open source dependencies and license risk |
| **IaC + Secrets** | Every push, every branch | Infrastructure misconfigurations and hardcoded secrets |
| **SAST + Policy** | Default branch only (post-merge) | First-party code, compiled and scanned against your Veracode policy |

Scans run on a dedicated pipeline beside each team's build. No changes to existing CI. No risk to existing deployments. The only thing added to a product repo is a 2-line `Jenkinsfile`.

---

## How it works

```
GitHub org
  └── repo (any language)
        └── Jenkinsfile          ← 2 lines, added by PR
              │
              ▼
     Jenkins Organization Folder  ← auto-discovers repos via GitHub API
              │
              ▼
     veracode-pipeline library    ← all logic lives here, versioned by Git tag
              │
         ┌────┴────────────────┐
         ▼                     ▼
    SCA + IaC/Secrets        SAST (default branch)
    (every build)            Docker container auto-detects
                             language, compiles, packages,
                             uploads to Veracode platform
```

The shared library handles everything: Veracode CLI install, SCA agent download, Docker-based autopackaging, Java wrapper upload, GitHub commit status reporting. Repos stay clean.

---

## Rollout in 5 steps

1. **Run `rollout.py`** - creates the two platform repos, registers the library in Jenkins, configures credentials, runs onboarding
2. **Scan Organization** in Jenkins - discovers all repos in the org
3. **Merge Jenkinsfile PRs** - `bulk_add_jenkinsfile.py` opens them, teams merge them
4. Scanning starts automatically on the next push
5. Results appear in [platform.veracode.com](https://platform.veracode.com)

The entire rollout touches no existing build pipelines and is reversible: `bulk_add_jenkinsfile.py --delete` opens PRs to remove the `Jenkinsfile` from every repo.

---

## Repository layout

```
library-repo/               → push as "veracode-pipeline" repo, tag v1
  vars/veracodePipeline.groovy    full pipeline: SCA, IaC, SAST (Linux + Windows)
  README.md                       usage, overrides, versioning, agent setup

platform-automation/        → push as "jenkins-platform" repo
  rollout.py                      one-shot setup script (dummy values, safe to commit)
  veracode-onboard.groovy         creates org folders, mints SCA tokens
  bulk_add_jenkinsfile.py         bulk PR rollout across orgs (--delete to reverse)
  jenkins.casc.yaml               JCasC alternative to rollout.py
  README.md                       step-by-step manual guide

consumer-repo-files/        → added to each scanned repo by the bulk-PR script
  Jenkinsfile                     2 lines
  .veracode.yml                   optional per-repo scan tuning
```

---

## What changes in your environment

| | Before | After |
|---|---|---|
| Product repos | Unchanged | +1 `Jenkinsfile` (2 lines) |
| Jenkins | Unchanged | +1 shared library, +1 org folder per scanned org |
| GitHub | Unchanged | +2 platform repos, +1 org webhook per scanned org |
| Veracode | Unchanged | +1 app profile per repo, +1 SCA workspace per org |

No agents are replaced. No existing pipelines are modified. No credentials are stored outside Jenkins.

---

## Requirements

- Jenkins with the Pipeline, GitHub Branch Source, Credentials Binding, and Docker Workflow plugins
- A GitHub PAT with `repo` and `read:org` scopes for the scan service account
- Veracode API credentials (ID + Key)
- Docker on Jenkins agents for SAST autopackaging (or pre-installed language toolchains)

See `SOLUTION.md` for the full architecture, credential scoping, agent requirements, and phased rollout plan.
