// =============================================================================
// Job DSL seed  -  one Organization Folder per org (standalone Jenkins)
// =============================================================================
// LEGACY -- superseded by veracode-onboard.groovy. Kept for reference only.
//
// This seed predates the current ad-hoc-only design: it assumes org-level
// GitHub webhooks are configured and sets a daily PeriodicFolderTrigger as a
// fallback. That contradicts the "no webhook, no periodic poll" model used
// everywhere else in this repo. Do not run this against a deployment that is
// meant to be ad hoc only -- use veracode-onboard.groovy instead, which does
// not set any recurring trigger.
//
// Adding an org = add its name to ORGS below. Re-running the seed is idempotent.
//
// Repos carry a 2-line Jenkinsfile (committed by bulk_add_jenkinsfile.py), so the
// default "Pipeline Jenkinsfile" recognizer is used. No recognizer plugin.
//
// The only per-org binding is the SCA token (the SCA workspace is per org). It is
// attached as a folder-scoped credential with id 'srcclr-api-token' on each folder,
// not set here, since it is a secret. Per-repo settings (source dir, library
// version, branch handling) live in each repo's Jenkinsfile via veracodePipeline().
// =============================================================================

def ORGS = [
    'acme-corp',
    'acme-labs',
    // 'next-org',
]

ORGS.each { orgName ->
    organizationFolder("veracode/${orgName}") {
        description("Veracode scanning for all ${orgName} repositories")

        organizations {
            github {
                repoOwner(orgName)
                apiUri('https://api.github.com')
                // Shared read-only scan account. Planned hardening: swap for a
                // per-org GitHub App credential (folder-scoped) later.
                credentialsId('scm-readonly')
            }
        }

        // Default recognizer: build repos that contain a Jenkinsfile.
        // The bulk-PR script ensures each repo has the 2-line Jenkinsfile.

        // Discovery: rely on org-level webhooks; periodic indexing is a safety net.
        triggers {
            periodicFolderTrigger { interval('1d') }
        }

        orphanedItemStrategy {
            discardOldItems {
                daysToKeep(7)
                numToKeep(50)
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Notes:
// - Per-org SCA token: bound by bind-sca-tokens.groovy (a system Groovy script
//   run after this seed), which upserts a folder-scoped credential with id
//   'srcclr-api-token' on each org folder from a SRCCLR_TOKEN_<ORG> env var. The
//   seed cannot create credentials itself (sandboxed, and secrets need the
//   Credentials API), so binding lives in that script. The library reads the
//   fixed id 'srcclr-api-token', so SCA results land in the org's workspace.
// - Per-repo config lives in the repo, not here:
//     library version -> @Library('veracode-pipeline@v1') in the Jenkinsfile
//     source dir       -> veracodePipeline(sourceDir: 'app') in the Jenkinsfile
//     branch handling  -> normally automatic via BRANCH_IS_PRIMARY; override per
//                         repo with veracodePipeline(topLevelBranches: '...') only
//                         if ever needed.
// - Need an org-wide default for one of those? Add it back as a folder env var in
//   a properties { folderProperties { ... } } block; the library reads
//   VERACODE_SOURCE_DIR and TOP_LEVEL_BRANCHES if present. Rarely worth it.
// -----------------------------------------------------------------------------
