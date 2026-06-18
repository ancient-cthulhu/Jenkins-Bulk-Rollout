# Platform Automation

What the CI/platform team applies to the controller and runs once per org. Lives in your CI-admin repo, not in product repos.

## Files

| File | Purpose |
|------|---------|
| `jenkins.casc.yaml` | JCasC: registers the shared library and root credentials (Veracode API id/key, `scm-readonly`) |
| `veracode-onboard.groovy` | System Groovy (one run): creates one Organization Folder per org, mints each org's Veracode SCA token, and binds it as the `srcclr-api-token` folder credential. Add an org by adding a line to `ORGS` |
| `bulk_add_jenkinsfile.py` | One-time-per-org: opens a PR adding the 2-line Jenkinsfile to every repo |

## Apply order

1. Set the env vars the JCasC reads (`VERACODE_API_ID`, `VERACODE_API_KEY`, `SCM_SCAN_USER`, `SCM_SCAN_TOKEN`). These populate the Jenkins credential store at apply time; no external secrets store is used. No per-org SCA token env vars are needed: the onboarding script mints them.
2. Apply `jenkins.casc.yaml` (Manage Jenkins > Configuration as Code > Apply, or `CASC_JENKINS_CONFIG`). This registers the library and the root credentials.
3. Run `veracode-onboard.groovy` as a trusted system script (Manage Jenkins > Script Console for a one-off, or a freestyle admin job with an "Execute system Groovy script" step to repeat or schedule). For every org in its `ORGS` list it creates the org folder, finds/creates that org's Veracode workspace, mints a fresh Jenkins SCA token (agent `<org>-jenkins`), and writes it as the folder credential `srcclr-api-token`. It reads `veracode-api-id`/`veracode-api-key` from the store and needs egress to `api.veracode.com`. Re-running is safe (folders/credentials converge; the Jenkins token is rotated each run by design).
4. For each org, run the bulk-PR script and merge the PRs:

   ```bash
   export GITHUB_TOKEN=...        # repo + PR scope on the org
   python3 bulk_add_jenkinsfile.py --org acme-corp --lib-version v1 --dry-run
   python3 bulk_add_jenkinsfile.py --org acme-corp --lib-version v1 --skip-archived --skip-forks
   ```

   The script is idempotent: it skips repos that already have a Jenkinsfile or the integration branch, so re-running is safe.

5. Once the Jenkinsfiles merge, the org folder discovers each repo and starts scanning on the next push or scheduled re-index.

## Adding a new org later

Add a line to `ORGS` in `veracode-onboard.groovy` and re-run it (creates the folder, mints the token, binds the credential), then run the bulk-PR script for the org. The scan account must be a member of the new org. Nothing else.

## Notes

- Org folders use the default Jenkinsfile recognizer, which picks up the committed Jenkinsfile.
- Drive discovery with org-level GitHub webhooks; the daily periodic trigger is a safety net.
- The onboarding script never touches the GitHub rollout's agent (`<org>-agt`); it only manages `<org>-jenkins`, so GitHub-side scans keep their token.
- The bulk-PR script targets the GitHub REST API.
