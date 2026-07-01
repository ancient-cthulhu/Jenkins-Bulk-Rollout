#!/usr/bin/env groovy
// =============================================================================
// veracodePipeline  -  Veracode Security Pipeline as a Jenkins Shared Library
// =============================================================================
// Single, tenant-wide pipeline template. Works on Linux (bash) and Windows
// (PowerShell) agents by switching on isUnix() at runtime.
//
// Scans:
//   Agent-Based SCA          default branch + PRs (token-gated, skips if absent)
//   Container/IaC/Secrets     default branch + PRs (directory scan of source)
//   Policy Scan (SAST)        repo default branch only, post-merge
//
// SAST packaging replicates what the Veracode GitHub Actions workflow does:
//   - Auto-detects language from repo files (pom.xml, *.csproj, package.json, etc.)
//   - Pulls the appropriate Docker image and runs the Veracode autopackager inside it
//   - Falls back to the bare agent if Docker is not available
//   - Override the image explicitly with sastImage or VERACODE_SAST_IMAGE
//
// Call from a 2-line Jenkinsfile:
//   @Library('veracode-pipeline@v1') _
//   veracodePipeline()
//
// Common config (all also settable as folder/job env vars in [brackets]):
//   appName              'org/repo'                    [VERACODE_APP_NAME]
//   sourceDir            'app'                         [VERACODE_SOURCE_DIR]
//   sastImage            'maven:3.9-eclipse-temurin-21'[VERACODE_SAST_IMAGE]
//     -- Docker image used to compile and package for SAST. Auto-detected from
//        repo files if not set. Set to 'none' to disable containerized builds
//        and always run on the bare agent.
//   sastAgentLabel       'sast-node'                   [VERACODE_SAST_AGENT_LABEL]
//     -- Optional. Routes SAST to a specific agent label. Without it SAST runs
//        on any available agent (Docker handles the toolchain, not the agent).
//   cliVersion           '2.x.y'                       [VERACODE_CLI_VERSION]
//   cliSha256            '<hex>'                        [VERACODE_CLI_SHA256]
//   wrapperVersion       '24.x.y'                      [VERACODE_WRAPPER_VERSION]
//   scanFeatureBranches  false                          [VERACODE_SCAN_FEATURE_BRANCHES]
//   gateSca/gateIac/gatePolicy  false/false/true       [VERACODE_GATE_SCA/IAC/POLICY]
//   archiveIacFindings   false                         [VERACODE_ARCHIVE_IAC]
//   topLevelBranches     'main'                        [TOP_LEVEL_BRANCHES]  (fallback only)
//   buildSteps           { ... }                       closure; custom build, see README
//
// Auto-detected language -> Docker image mapping:
//   pom.xml                       -> maven:3.9-eclipse-temurin-21
//   build.gradle / build.gradle.kts -> gradle:8-eclipse-temurin-21
//   *.csproj / *.sln              -> mcr.microsoft.com/dotnet/sdk:8.0
//   package.json                  -> node:20
//   requirements.txt / setup.py / pyproject.toml -> python:3.12
//   go.mod                        -> golang:1.22
//   Gemfile                       -> ruby:3.3
//   composer.json                 -> php:8.3-cli
//   Cargo.toml                    -> rust:1.77
//
// Required credentials, resolved by id through the folder hierarchy:
//   veracode-api-id   (Secret text, required for SAST/IaC-upload)
//   veracode-api-key  (Secret text, required for SAST/IaC-upload)
//   srcclr-api-token  (Secret text, optional - SCA skips cleanly if absent)
//
// Required Jenkins plugins (in addition to standard pipeline plugins):
//   docker-workflow  -- enables docker.image().inside() for containerized builds
// =============================================================================

def call(Map config = [:]) {

    pipeline {
        agent any

        options {
            timestamps()
            timeout(time: 2, unit: 'HOURS')
            buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
            disableConcurrentBuilds()
        }

        stages {

            stage('Init & Checkout') {
                steps {
                    checkout scm
                    script {
                        boolean isCR        = (env.CHANGE_ID != null)
                        boolean isDefault   = resolveDefaultBranch(config)
                        boolean topLevel    = isDefault && !isCR
                        boolean scanFeature = asBool(config.scanFeatureBranches, env.VERACODE_SCAN_FEATURE_BRANCHES, false)

                        boolean shouldScan  = isDefault || isCR || scanFeature

                        env.VC_IS_PR        = isCR.toString()
                        env.VC_IS_DEFAULT   = isDefault.toString()
                        env.VC_IS_TOP_LEVEL = topLevel.toString()
                        env.VC_SHOULD_SCAN  = shouldScan.toString()

                        def parts = (env.JOB_NAME ?: '').split('/').findAll { it }
                        String orgRepo
                        if (parts.size() >= 3)       orgRepo = "${parts[-3]}/${parts[-2]}"
                        else if (parts.size() == 2)  orgRepo = parts[-2]
                        else                         orgRepo = (env.JOB_NAME ?: '')

                        env.VC_APP_NAME    = ((config.appName    ?: env.VERACODE_APP_NAME?.trim())    ?: orgRepo)
                        env.VC_SRC         = ((config.sourceDir  ?: env.VERACODE_SOURCE_DIR?.trim())  ?: '.')
                        env.VC_SAST_LABEL  = (config.sastAgentLabel ?: env.VERACODE_SAST_AGENT_LABEL ?: '').trim()
                        env.VC_SAST_IMAGE  = (config.sastImage   ?: env.VERACODE_SAST_IMAGE   ?: '').trim()
                        env.VC_CLI_VERSION = (config.cliVersion  ?: env.VERACODE_CLI_VERSION  ?: '').trim()
                        env.VC_CLI_SHA256  = (config.cliSha256   ?: env.VERACODE_CLI_SHA256   ?: '').trim()
                        env.VC_WRAPPER_VER = (config.wrapperVersion ?: env.VERACODE_WRAPPER_VERSION ?: '').trim()

                        env.VC_GATE_SCA    = asBool(config.gateSca,    env.VERACODE_GATE_SCA,    false).toString()
                        env.VC_GATE_IAC    = asBool(config.gateIac,    env.VERACODE_GATE_IAC,    false).toString()
                        env.VC_GATE_POLICY = asBool(config.gatePolicy, env.VERACODE_GATE_POLICY, true).toString()
                        env.VC_ARCHIVE_IAC = asBool(config.archiveIacFindings, env.VERACODE_ARCHIVE_IAC, false).toString()

                        echo "Branch: ${env.BRANCH_NAME} | PR: ${isCR} | default: ${isDefault} | " +
                             "SAST eligible: ${topLevel} | scan this build: ${shouldScan} | " +
                             "app: ${env.VC_APP_NAME} | source: ${env.VC_SRC}"
                    }
                }
            }

            // -----------------------------------------------------------------
            // Light source scans: SCA + IaC/secrets. Default branch and PRs.
            // Run on the general agent -- no toolchain needed.
            // -----------------------------------------------------------------
            stage('Source Scans') {
                when { expression { env.VC_SHOULD_SCAN == 'true' } }
                steps {
                    script { installVeracodeCli() }
                }
                post { success { echo 'Source scans stage complete.' } }
            }

            stage('SCA + IaC') {
                when { expression { env.VC_SHOULD_SCAN == 'true' } }
                parallel {

                    stage('Agent-Based SCA') {
                        steps {
                            script {
                                boolean hasToken = false
                                try {
                                    withCredentials([string(credentialsId: 'srcclr-api-token', variable: 'SRCCLR_API_TOKEN')]) {
                                        hasToken = true
                                        int rc
                                        if (isUnix()) {
                                            rc = sh(returnStatus: true, script: '''
                                                set -o pipefail
                                                echo "Running Agent-Based SCA scan..."
                                                if command -v srcclr >/dev/null 2>&1; then
                                                    srcclr scan --recursive --update-advisor --allow-dirty
                                                else
                                                    curl -sSL https://sca-downloads.veracode.com/ci.sh \
                                                        | sh -s -- scan --recursive --update-advisor --allow-dirty
                                                fi
                                            ''')
                                        } else {
                                            rc = powershell(returnStatus: true, script: '''
                                                Set-ExecutionPolicy AllSigned -Scope Process -Force
                                                $ProgressPreference = "silentlyContinue"
                                                if (Get-Command srcclr -ErrorAction SilentlyContinue) {
                                                    srcclr scan --recursive --update-advisor --allow-dirty
                                                } else {
                                                    $client = New-Object System.Net.WebClient
                                                    $sca = $client.DownloadString("https://sca-downloads.veracode.com/ci.ps1")
                                                    Invoke-Command -ScriptBlock ([scriptblock]::Create($sca)) `
                                                        -ArgumentList @("scan","--recursive","--update-advisor","--allow-dirty")
                                                }
                                                exit $LASTEXITCODE
                                            ''')
                                        }
                                        if (rc != 0) {
                                            handleScanResult('SCA', rc, env.VC_GATE_SCA == 'true')
                                        }
                                    }
                                } catch (org.jenkinsci.plugins.credentialsbinding.impl.CredentialNotFoundException nf) {
                                    echo "Agent-Based SCA skipped: srcclr-api-token not configured for this folder."
                                } catch (err) {
                                    if (hasToken) { throw err }
                                    echo "Agent-Based SCA skipped: ${err}"
                                }
                            }
                        }
                    }

                    stage('Container/IaC/Secrets') {
                        steps {
                            script {
                                boolean toPlatform = (env.VC_IS_DEFAULT == 'true')
                                if (toPlatform) {
                                    withCredentials([string(credentialsId: 'veracode-api-id', variable: 'VERACODE_API_ID'),
                                                     string(credentialsId: 'veracode-api-key', variable: 'VERACODE_API_KEY')]) {
                                        runIacScan(true)
                                    }
                                } else {
                                    runIacScan(false)
                                }
                            }
                        }
                        post {
                            always {
                                script {
                                    if (env.VC_ARCHIVE_IAC == 'true') {
                                        archiveArtifacts artifacts: 'container_iac_secrets.json', allowEmptyArchive: true
                                    } else {
                                        echo 'IaC/secrets findings sent to scan output only; raw JSON not archived.'
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // -----------------------------------------------------------------
            // SAST (Package + Policy): default branch only.
            //
            // Replicates the Veracode GitHub Actions workflow behavior:
            //   1. Detects the repo language from well-known build files.
            //   2. Pulls the matching Docker image (same toolchains as a
            //      GitHub Actions ubuntu-latest runner).
            //   3. Runs the Veracode autopackager inside the container so it
            //      can compile the source -- no toolchain install needed on the
            //      Jenkins agent itself.
            //   4. Falls back to the bare agent if Docker is unavailable or
            //      sastImage is set to 'none'.
            //
            // Override the image per-repo via:
            //   veracodePipeline(sastImage: 'maven:3.9-eclipse-temurin-21')
            // Or via the VERACODE_SAST_IMAGE env var on the org folder.
            // -----------------------------------------------------------------
            stage('SAST') {
                when {
                    beforeAgent true
                    expression { env.VC_IS_TOP_LEVEL == 'true' }
                }
                agent { label (env.VC_SAST_LABEL ?: '') }
                stages {
                    stage('Package Artifacts') {
                        steps {
                            script {
                                checkout scm
                                if (config.buildSteps) {
                                    // Repo supplies its own build -- runs on bare agent.
                                    // buildSteps must leave scannable artifacts in verascan/.
                                    echo 'Package: running repo-supplied buildSteps.'
                                    installVeracodeCli()
                                    config.buildSteps.call()
                                    ensureVerascanNonEmpty()
                                } else {
                                    // Auto-detect language and package inside the right container.
                                    packageWithAutodetect()
                                }
                            }
                        }
                    }
                    stage('Policy Scan') {
                        steps {
                            script { runPolicyScan() }
                        }
                    }
                }
                post { always { cleanWs(deleteDirs: true, notFailBuild: true) } }
            }
        }

        post {
            always {
                echo "Build finished with status: ${currentBuild.currentResult}"
                cleanWs(deleteDirs: true, notFailBuild: true)
                script {
                    def scanTypes = []
                    if (env.VC_IS_DEFAULT == 'true') scanTypes << 'SCA' << 'IaC/Secrets' << 'SAST'
                    else                              scanTypes << 'SCA' << 'IaC/Secrets'
                    def scanDesc = scanTypes.join(' + ')
                    def statusMsg = ''
                    def ghStatus = 'SUCCESS'
                    // Check if a scan was already in progress and we deferred
                    def scanQueued = fileExists('/tmp/vc_scan_queued')
                    if (scanQueued) {
                        sh 'rm -f /tmp/vc_scan_queued'
                        statusMsg = "Veracode SAST scan already in progress on platform -- re-trigger once complete"
                        ghStatus = 'PENDING'
                    } else {
                        switch (currentBuild.currentResult) {
                            case 'SUCCESS':
                                statusMsg = "Veracode ${scanDesc} scan passed"
                                ghStatus  = 'SUCCESS'
                                break
                            case 'UNSTABLE':
                                statusMsg = "Veracode ${scanDesc} scan completed with warnings -- review results in platform.veracode.com"
                                ghStatus  = 'PENDING'
                                break
                            case 'FAILURE':
                                statusMsg = "Veracode ${scanDesc} scan failed -- review stage logs for details"
                                ghStatus  = 'FAILURE'
                                break
                            default:
                                statusMsg = "Veracode ${scanDesc} scan finished: ${currentBuild.currentResult}"
                                ghStatus  = 'PENDING'
                        }
                    }
                    try {
                        // Use the Checks API (publishChecks) to report scan results
                        // back to GitHub with a meaningful description.
                        def conclusion = ghStatus == 'SUCCESS' ? 'SUCCESS'
                                       : ghStatus == 'FAILURE' ? 'FAILURE'
                                       : 'NEUTRAL'
                        publishChecks(
                            name: 'Veracode Security Scan',
                            title: statusMsg,
                            summary: statusMsg,
                            status: 'COMPLETED',
                            conclusion: conclusion
                        )
                    } catch (ignored) {
                        // publishChecks is only available when triggered by a
                        // GitHub event. Suppress on manual/scheduled runs.
                    }
                }
            }
            failure { echo 'Veracode pipeline failed. Review the stage logs.' }
        }
    }
}

// =============================================================================
// Helpers
// =============================================================================

private boolean asBool(Object cfg, Object envVal, boolean dflt) {
    def truthy = ['true', '1', 'yes', 'on']
    if (cfg != null)                return truthy.contains(cfg.toString().trim().toLowerCase())
    if (envVal?.toString()?.trim()) return truthy.contains(envVal.toString().trim().toLowerCase())
    return dflt
}

private boolean resolveDefaultBranch(Map config) {
    String override = (config.topLevelBranches ?: env.TOP_LEVEL_BRANCHES?.trim())
    if (env.BRANCH_IS_PRIMARY != null) {
        return env.BRANCH_IS_PRIMARY == 'true'
    } else if (override) {
        echo 'BRANCH_IS_PRIMARY not set; falling back to TOP_LEVEL_BRANCHES regex.'
        return (env.BRANCH_NAME ==~ /(${override})/)
    }
    echo 'WARNING: BRANCH_IS_PRIMARY not set and no TOP_LEVEL_BRANCHES override; SAST/Policy will be skipped.'
    return false
}

// Prefer a pre-staged on-PATH veracode binary; download if absent.
private void installVeracodeCli() {
    if (isUnix()) {
        sh '''
            set -e
            if command -v veracode >/dev/null 2>&1; then
                echo "Using pre-staged Veracode CLI: $(command -v veracode)"
                veracode version || true
                exit 0
            fi
            echo "No pre-staged Veracode CLI found; downloading."
            curl -fsS https://tools.veracode.com/veracode-cli/install -o install_cli.sh
            if [ -n "${VC_CLI_SHA256:-}" ]; then
                echo "${VC_CLI_SHA256}  install_cli.sh" | sha256sum -c -
            fi
            sh install_cli.sh
            # Move to /usr/local/bin if writable so subsequent stages on the
            # same agent find it on PATH without re-downloading.
            # Use WORKSPACE explicitly -- durable shell CWD is not the workspace.
            if [ -w /usr/local/bin ] && [ -f "${WORKSPACE}/veracode" ]; then
                mv "${WORKSPACE}/veracode" /usr/local/bin/veracode
                echo "Veracode CLI installed to /usr/local/bin/veracode"
            fi
            # Find and run wherever it ended up
            if command -v veracode >/dev/null 2>&1; then
                veracode version
            else
                "${WORKSPACE}/veracode" version
            fi
        '''
    } else {
        powershell '''
            $ProgressPreference = "silentlyContinue"
            if (Get-Command veracode -ErrorAction SilentlyContinue) {
                Write-Host "Using pre-staged Veracode CLI"
                veracode version
                exit 0
            }
            Write-Host "No pre-staged Veracode CLI; downloading."
            Invoke-WebRequest -Uri "https://tools.veracode.com/veracode-cli/install.ps1" -OutFile "install.ps1"
            if ($env:VC_CLI_SHA256) {
                $h = (Get-FileHash -Algorithm SHA256 install.ps1).Hash.ToLower()
                if ($h -ne $env:VC_CLI_SHA256.ToLower()) { Write-Error "CLI checksum mismatch"; exit 1 }
            }
            powershell -NoProfile -ExecutionPolicy Bypass -File ".\\install.ps1"
            $veracodeExe = Join-Path $env:USERPROFILE ".veracode-cli\\veracode.exe"
            if (!(Test-Path $veracodeExe)) { $veracodeExe = "veracode" }
            & $veracodeExe version
        '''
    }
}

// Detect language from repo files and return the matching Docker image.
// Mirrors the toolchain matrix of a GitHub Actions ubuntu-latest runner.
// Returns empty string if no match found.
private String detectBuildImage() {
    if (!isUnix()) {
        echo 'Language auto-detection is Linux only; skipping on Windows agent.'
        return ''
    }

    def checks = [
        // indicator file/glob              image
        ['test -f pom.xml',                                         'maven:3.9-eclipse-temurin-21',             'Java/Maven'],
        ['test -f build.gradle || test -f build.gradle.kts',        'gradle:8-eclipse-temurin-21',              'Java/Gradle'],
        ['find . -maxdepth 4 -name "*.csproj" | grep -q .',         'mcr.microsoft.com/dotnet/sdk:8.0',         '.NET/C#'],
        ['find . -maxdepth 4 -name "*.sln"    | grep -q .',         'mcr.microsoft.com/dotnet/sdk:8.0',         '.NET/C# (solution)'],
        ['test -f package.json',                                     'node:20',                                  'Node.js'],
        ['test -f requirements.txt || test -f setup.py || test -f pyproject.toml', 'python:3.12',               'Python'],
        ['test -f go.mod',                                          'golang:1.22',                               'Go'],
        ['test -f Gemfile',                                         'ruby:3.3',                                  'Ruby'],
        ['test -f composer.json',                                   'php:8.3-cli',                               'PHP'],
        ['test -f Cargo.toml',                                      'rust:1.77',                                 'Rust'],
    ]

    for (def row : checks) {
        int rc = sh(returnStatus: true, script: row[0])
        if (rc == 0) {
            echo "Language detected: ${row[2]} -> image: ${row[1]}"
            return row[1]
        }
    }

    echo 'WARNING: could not detect language from repo files. ' +
         'Set sastImage in your Jenkinsfile or VERACODE_SAST_IMAGE on the org folder.'
    return ''
}

// Check whether Docker is usable on this agent.
private boolean dockerAvailable() {
    if (!isUnix()) return false
    int rc = sh(returnStatus: true, script: 'docker info > /dev/null 2>&1')
    if (rc != 0) {
        echo 'Docker not available on this agent (docker info failed). ' +
             'Running autopackager on bare agent -- ensure the language toolchain is installed.'
    }
    return rc == 0
}

// Core of the SAST packaging path.
// Replicates the Veracode GitHub Actions workflow:
//   1. Use VC_SAST_IMAGE if explicitly set (or 'none' to force bare agent).
//   2. Otherwise auto-detect from repo files.
//   3. If Docker available + image resolved: compile and package inside container.
//   4. If not: fall back to bare agent with clear error guidance on failure.
private void packageWithAutodetect() {
    String image = env.VC_SAST_IMAGE?.trim() ?: ''

    if (image.toLowerCase() == 'none') {
        echo 'sastImage=none: running autopackager on bare agent (Docker disabled by config).'
        installVeracodeCli()
        runAutopackager()
        return
    }

    if (!image) {
        echo 'No sastImage configured -- auto-detecting language from repo files...'
        image = detectBuildImage()
    }

    if (image && dockerAvailable()) {
        echo "Packaging inside Docker container: ${image}"
        echo "This replicates the toolchain environment of a GitHub Actions ubuntu-latest runner."
        // Run as root inside the container so package managers can install dependencies.
        // The workspace is bind-mounted automatically by docker.image().inside().
        docker.image(image).inside('--user root') {
            installVeracodeCli()
            runAutopackager()
        }
    } else {
        // No Docker or no image -- try the bare agent anyway.
        // If it fails, give the admin a clear path to fix it.
        echo 'Containerized build not available; running autopackager on bare agent.'
        installVeracodeCli()
        try {
            runAutopackager()
        } catch (err) {
            error(
                "SAST autopackager failed for ${env.VC_APP_NAME} on agent '${env.NODE_NAME}'.\n" +
                "The agent likely lacks the language build toolchain (Maven, MSBuild, npm, etc.).\n" +
                "\n" +
                "How to fix (pick one):\n" +
                "  A. Enable Docker on the agent and add the docker-workflow Jenkins plugin.\n" +
                "     The pipeline will then auto-detect the language and pull the right container.\n" +
                "     This replicates the GitHub Actions runner behavior with no extra config.\n" +
                "\n" +
                "  B. Set sastImage explicitly in the Jenkinsfile:\n" +
                "       veracodePipeline(sastImage: 'maven:3.9-eclipse-temurin-21')\n" +
                "     Or set VERACODE_SAST_IMAGE on the org folder in Jenkins.\n" +
                "     Language-to-image map is in the library header comment.\n" +
                "\n" +
                "  C. Supply a buildSteps closure to run the repo's own build:\n" +
                "       veracodePipeline(buildSteps: { sh 'mvn package'; sh 'cp target/*.jar verascan/' })\n" +
                "\n" +
                "  D. Install the required toolchain directly on agent '${env.NODE_NAME}'.\n" +
                "\n" +
                "Original error: ${err.getMessage()}"
            )
        }
    }
}

private void runIacScan(boolean withCreds) {
    if (isUnix()) {
        sh """
            ${withCreds ? 'export VERACODE_API_KEY_ID="\$VERACODE_API_ID"; export VERACODE_API_KEY_SECRET="\$VERACODE_API_KEY"' : 'echo "Local IaC/secrets scan (no platform credentials on this build)."'}
            SRC="\$VC_SRC"
            VERACODE_BIN="veracode"
            if ! command -v veracode >/dev/null 2>&1; then
                VERACODE_BIN="\${WORKSPACE}/veracode"
            fi
            "\$VERACODE_BIN" scan --type directory --source "\$SRC" --format json \\
                --output container_iac_secrets.json \\
                || echo "IaC/secrets scan reported findings or errored (non-gating unless gateIac)."
        """
    } else {
        powershell """
            \$ProgressPreference = "silentlyContinue"
            \$PSNativeCommandUseErrorActionPreference = \$false
            ${withCreds ? '\$env:VERACODE_API_KEY_ID = \$env:VERACODE_API_ID; \$env:VERACODE_API_KEY_SECRET = \$env:VERACODE_API_KEY' : 'Write-Host "Local IaC/secrets scan (no platform credentials)."'}
            \$src = \$env:VC_SRC
            \$veracodeExe = Join-Path \$env:USERPROFILE ".veracode-cli\\veracode.exe"
            if (!(Test-Path \$veracodeExe)) { \$veracodeExe = "veracode" }
            & \$veracodeExe scan --type directory --source "\$src" --format json --output container_iac_secrets.json
            if (\$LASTEXITCODE -ne 0) { Write-Host "IaC/secrets scan findings or error (non-gating unless gateIac). Exit: \$LASTEXITCODE" }
        """
    }
    if (env.VC_GATE_IAC == 'true') {
        int findings = isUnix()
            ? sh(returnStatus: true, script: 'grep -q \\"severity\\" container_iac_secrets.json 2>/dev/null')
            : powershell(returnStatus: true, script: 'if (Select-String -Quiet -Path container_iac_secrets.json -Pattern "severity") { exit 0 } else { exit 1 }')
        if (findings == 0) { error 'IaC/secrets gate: findings present and gateIac is enabled.' }
    }
}

private void ensureVerascanNonEmpty() {
    if (isUnix()) {
        sh 'test -n "$(find verascan -type f 2>/dev/null)" || { echo "buildSteps left verascan/ empty" >&2; exit 1; }'
    } else {
        powershell 'if (-not (Get-ChildItem -Recurse -File verascan -ErrorAction SilentlyContinue)) { Write-Error "buildSteps left verascan/ empty"; exit 1 }'
    }
}

private void runAutopackager() {
    if (isUnix()) {
        sh '''
            set -e
            SRC="$VC_SRC"
            VERACODE_BIN="veracode"
            if ! command -v veracode >/dev/null 2>&1; then
                VERACODE_BIN="${WORKSPACE}/veracode"
            fi
            echo "Running Veracode autopackager on: $SRC"
            rm -rf verascan && mkdir -p verascan
            "$VERACODE_BIN" package --source "$SRC" --output verascan --trust
            find verascan -type f | tee artifact_list.txt
            [ -s artifact_list.txt ] || { echo "No packaged artifacts found" >&2; exit 1; }
            echo "Total artifacts: $(wc -l < artifact_list.txt)"
        '''
    } else {
        powershell '''
            $ErrorActionPreference = "Stop"
            $src = $env:VC_SRC
            $veracodeExe = Join-Path $env:USERPROFILE ".veracode-cli\\veracode.exe"
            if (!(Test-Path $veracodeExe)) { $veracodeExe = "veracode" }
            Write-Host "Running Veracode autopackager on: $src"
            Remove-Item -Recurse -Force verascan -ErrorAction SilentlyContinue
            New-Item -ItemType Directory -Force -Path verascan | Out-Null
            & $veracodeExe package --source "$src" --output verascan --trust
            $artifacts = Get-ChildItem -Path verascan -Recurse -File
            if (!$artifacts) { Write-Error "No packaged artifacts found"; exit 1 }
            $artifacts.FullName | Out-File -FilePath artifact_list.txt -Encoding utf8
            Write-Host "Total artifacts: $($artifacts.Count)"
        '''
    }
    archiveArtifacts artifacts: 'artifact_list.txt', allowEmptyArchive: true
}

// Policy/SAST upload via the Java wrapper. Credentials come from a 0600
// ~/.veracode/credentials file written from masked env vars (shell expansion,
// not Groovy interpolation) and removed after the run.
private void runPolicyScan() {
    withCredentials([string(credentialsId: 'veracode-api-id', variable: 'VERACODE_API_ID'),
                     string(credentialsId: 'veracode-api-key', variable: 'VERACODE_API_KEY')]) {
        if (isUnix()) {
            sh '''
                set -e
                BASE="https://repo1.maven.org/maven2/com/veracode/vosp/api/wrappers/vosp-api-wrappers-java"
                if [ -n "${VC_WRAPPER_VER:-}" ]; then
                    VERSION="$VC_WRAPPER_VER"
                else
                    echo "WARNING: VC_WRAPPER_VER unset; resolving latest. Pin wrapperVersion for reproducibility."
                    VERSION=$(curl -fsSL "$BASE/maven-metadata.xml" | grep -oP '(?<=<latest>)[^<]+')
                fi
                [ -n "$VERSION" ] || { echo "No wrapper version" >&2; exit 1; }
                echo "Java API Wrapper version: $VERSION"

                rm -rf .veracode_jar && mkdir -p .veracode_jar
                curl -fsSL -o .veracode_jar/dist.zip "$BASE/$VERSION/vosp-api-wrappers-java-$VERSION-dist.zip"
                unzip -o -q .veracode_jar/dist.zip -d .veracode_jar
                JAR=$(find .veracode_jar -name 'VeracodeJavaAPI*.jar' | head -n 1)
                [ -n "$JAR" ] || { echo "wrapper jar not found" >&2; exit 1; }

                umask 077
                mkdir -p "$HOME/.veracode"
                cleanup() { rm -f "$HOME/.veracode/credentials"; }
                trap cleanup EXIT
                {
                    echo "[default]"
                    echo "veracode_api_key_id = $VERACODE_API_ID"
                    echo "veracode_api_key_secret = $VERACODE_API_KEY"
                } > "$HOME/.veracode/credentials"

                echo "Uploading to Veracode Policy Scan as: $VC_APP_NAME"
                java -jar "$JAR" \
                    -action UploadAndScan \
                    -appname "$VC_APP_NAME" \
                    -createprofile true \
                    -autoscan true \
                    -deleteincompletescan 1 \
                    -toplevel true \
                    -filepath "verascan" \
                    -version "$BRANCH_NAME $BUILD_NUMBER"
                RC=$?
                # Exit code 2 means the wrapper found an existing scan on the
                # platform it would not delete and could not proceed past.
                # -deleteincompletescan 1 (set above) only auto-deletes scans
                # that are incomplete, failed, canceled, or have no modules
                # defined -- it deliberately does NOT delete a scan that is
                # actively in progress (Pre-Scan Success or later). That is
                # by design: with ad hoc triggering, two people can kick off
                # overlapping scans on the same app profile, and we do not
                # want one build silently deleting another's in-progress work.
                # Treat it as non-fatal -- the platform already has the artifacts.
                if [ "$RC" -eq 2 ]; then
                    echo "WARNING: A scan is already in progress on the platform for $VC_APP_NAME."
                    echo "The current build's artifacts have NOT been uploaded. Wait for the"
                    echo "in-progress scan to complete, then re-trigger this build."
                    # Mark unstable rather than failed so GitHub gets a non-blocking signal.
                    touch /tmp/vc_scan_queued
                    exit 0
                fi
                if [ "${VC_GATE_POLICY:-true}" = "true" ] && [ "$RC" -ne 0 ]; then
                    echo "Policy gate: wrapper returned $RC." >&2
                    exit "$RC"
                fi
            '''
        } else {
            powershell '''
                $ErrorActionPreference = "Stop"
                $base = "https://repo1.maven.org/maven2/com/veracode/vosp/api/wrappers/vosp-api-wrappers-java"
                if ($env:VC_WRAPPER_VER) { $version = $env:VC_WRAPPER_VER }
                else {
                    Write-Host "WARNING: VC_WRAPPER_VER unset; resolving latest. Pin wrapperVersion."
                    [xml]$m = (Invoke-WebRequest -Uri "$base/maven-metadata.xml").Content
                    $version = $m.metadata.versioning.latest
                }
                if (!$version) { Write-Error "No wrapper version" }
                New-Item -ItemType Directory -Force -Path ".veracode_jar" | Out-Null
                Invoke-WebRequest -Uri "$base/$version/vosp-api-wrappers-java-$version-dist.zip" -OutFile ".veracode_jar\\dist.zip"
                Expand-Archive -Path ".veracode_jar\\dist.zip" -DestinationPath ".veracode_jar" -Force
                $jar = Get-ChildItem -Path ".veracode_jar" -Recurse -File -Filter "VeracodeJavaAPI*.jar" | Select-Object -First 1
                if (!$jar) { Write-Error "wrapper jar not found" }

                $vcDir = Join-Path $env:USERPROFILE ".veracode"
                New-Item -ItemType Directory -Force -Path $vcDir | Out-Null
                $credFile = Join-Path $vcDir "credentials"
                try {
                    Set-Content -Path $credFile -Value @(
                        "[default]",
                        "veracode_api_key_id = $env:VERACODE_API_ID",
                        "veracode_api_key_secret = $env:VERACODE_API_KEY"
                    )
                    Write-Host "Uploading to Veracode Policy Scan as: $env:VC_APP_NAME"
                    & java -jar $jar.FullName `
                        -action UploadAndScan `
                        -appname "$env:VC_APP_NAME" `
                        -createprofile true `
                        -autoscan true `
                        -deleteincompletescan 1 `
                        -toplevel true `
                        -filepath "verascan" `
                        -version "$env:BRANCH_NAME $env:BUILD_NUMBER"
                    $rc = $LASTEXITCODE
                    if ($env:VC_GATE_POLICY -ne "false" -and $rc -ne 0) {
                        Write-Error "Policy gate: wrapper returned $rc."
                        exit $rc
                    }
                } finally {
                    Remove-Item -Force $credFile -ErrorAction SilentlyContinue
                }
            '''
        }
    }
}

private void handleScanResult(String name, int rc, boolean gate) {
    if (gate) { error "${name} gate: scan returned ${rc} and gate is enabled." }
    unstable("${name} scan returned ${rc} (non-gating).")
}
