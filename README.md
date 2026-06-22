# Veracode + Jenkins Integration

Jenkins bulk rollout for scanning multiple orgs in GitHub with Veracode SCA, IaC/secrets, and SAST that run automatically on every repo, beside each team's build.

<p align="center">
  <img src="architecture.svg" alt="Veracode + Jenkins Integration Architecture" width="1000">
  <br>
  <em>Reference architecture for centralized Jenkins-managed Veracode scanning across GitHub organizations.</em>
</p>

---

**Start here: `SOLUTION.md`**

The full implementation plan covering architecture, requirements, credentials and SCM scopes, rollout order, scan behavior, and risk.

## What's included in this repository

```text
SOLUTION.md            the one document to read/present
architecture.svg       architecture diagram

library-repo/          push as the new Git repo "veracode-pipeline", tag v1
  vars/veracodePipeline.groovy   the entire pipeline (Linux + Windows, Docker SAST)
  README.md                      library usage, agent requirements, versioning

consumer-repo-files/   committed into each scanned repo (by the bulk-PR script)
  Jenkinsfile                    2 lines: calls the library
  .veracode.yml                  optional per-repo IaC/secrets tuning

platform-automation/   push as the new Git repo "jenkins-platform"; applied to the controller
  rollout.example.py          one-shot setup script (copy to rollout.py, fill in config, run once)
  jenkins.casc.yaml           register library + root credentials (alternative to rollout.py)
  veracode-onboard.groovy     one run: create org folders, mint + bind each org's SCA token, create scan trigger jobs
  bulk_add_jenkinsfile.py     opens PRs adding the Jenkinsfile across an org (--delete to reverse)
  README.md                   apply order and configuration
```

## Repository Impact

- **New repositories**
  - `veracode-pipeline`
  - `jenkins-platform`

- **Changes to existing product repositories**
  - Add a minimal `Jenkinsfile`
  - Optionally add `.veracode.yml`

Only `library-repo` and `platform-automation` are new repos. Existing product repos change by one file each.
