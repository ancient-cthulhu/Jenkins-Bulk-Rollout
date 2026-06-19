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
//   Policy Scan (SAST)        repo default branch only, post-merge, on a
//                             label-restricted toolchain agent
//
// Call from a 2-line Jenkinsfile:
//   @Library('veracode-pipeline@v1') _
//   veracodePipeline()
//
// Common config (all also settable as folder/job env vars in [brackets]):
//   appName            'org/repo'                 [VERACODE_APP_NAME]
//   sourceDir          'app'                      [VERACODE_SOURCE_DIR]
//   sastAgentLabel     'veracode-sast-linux'      [VERACODE_SAST_AGENT_LABEL]   (required to run SAST)
//   cliVersion         '2.x.y'                    [VERACODE_CLI_VERSION]
//   cliSha256          '<hex>'                    [VERACODE_CLI_SHA256]
//   wrapperVersion     '24.x.y'                   [VERACODE_WRAPPER_VERSION]
//   scanFeatureBranches  false                    [VERACODE_SCAN_FEATURE_BRANCHES]
//   gateSca/gateIac/gatePolicy  false/false/true  [VERACODE_GATE_SCA/IAC/POLICY]
//   archiveIacFindings false                      [VERACODE_ARCHIVE_IAC]
//   topLevelBranches   'main'                     [TOP_LEVEL_BRANCHES]  (fallback only)
//   buildSteps         { ... }                    closure; see README "Complex builds"
//
// Required credentials, resolved by id through the folder hierarchy:
//   veracode-api-id   (Secret text, required for SAST/IaC-upload)
//   veracode-api-key  (Secret text, required for SAST/IaC-upload)
//   srcclr-api-token  (Secret text, optional - SCA skips cleanly if absent)
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

                        // Only the default branch and PRs scan by default.
                        boolean shouldScan  = isDefault || isCR || scanFeature

                        env.VC_IS_PR        = isCR.toString()
                        env.VC_IS_DEFAULT   = isDefault.toString()
                        env.VC_IS_TOP_LEVEL = topLevel.toString()
                        env.VC_SHOULD_SCAN  = shouldScan.toString()

                        // App profile is always org/repo, independent of branch.
                        String jobPath = env.JOB_NAME
                        String orgRepo = jobPath?.contains('/') ? jobPath.substring(0, jobPath.lastIndexOf('/')) : jobPath
                        env.VC_APP_NAME = ((config.appName ?: env.VERACODE_APP_NAME?.trim()) ?: orgRepo)
                        env.VC_SRC      = ((config.sourceDir ?: env.VERACODE_SOURCE_DIR?.trim()) ?: '.')

                        env.VC_SAST_LABEL  = (config.sastAgentLabel ?: env.VERACODE_SAST_AGENT_LABEL ?: '').trim()
                        env.VC_CLI_VERSION = (config.cliVersion ?: env.VERACODE_CLI_VERSION ?: '').trim()
                        env.VC_CLI_SHA256  = (config.cliSha256 ?: env.VERACODE_CLI_SHA256 ?: '').trim()
                        env.VC_WRAPPER_VER = (config.wrapperVersion ?: env.VERACODE_WRAPPER_VERSION ?: '').trim()

                        env.VC_GATE_SCA    = asBool(config.gateSca, env.VERACODE_GATE_SCA, false).toString()
                        env.VC_GATE_IAC    = asBool(config.gateIac, env.VERACODE_GATE_IAC, false).toString()
                        env.VC_GATE_POLICY = asBool(config.gatePolicy, env.VERACODE_GATE_POLICY, true).toString()
                        env.VC_ARCHIVE_IAC = asBool(config.archiveIacFindings, env.VERACODE_ARCHIVE_IAC, false).toString()

                        echo "Branch: ${env.BRANCH_NAME} | PR: ${isCR} | default: ${isDefault} | " +
                             "SAST eligible: ${topLevel} | scan this build: ${shouldScan} | " +
                             "app: ${env.VC_APP_NAME} | source: ${env.VC_SRC}"
                    }
                }
            }

            // -----------------------------------------------------------------
            // Light source scans: SCA + IaC/secrets. Default branch and PRs only.
            // Run on the general agent. The HMAC key is bound only on the default
            // branch and only around the IaC upload, never on PR builds.
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

                    // Agent-Based SCA: skip if no token; surface real failures.
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
                                                # Prefer a pre-staged agent; else download.
                                                if command -v srcclr >/dev/null 2>&1; then
                                                    srcclr scan --recursive --update-advisor
                                                else
                                                    curl -sSL https://sca-downloads.veracode.com/ci.sh \\
                                                        | sh -s -- scan --recursive --update-advisor
                                                fi
                                            ''')
                                        } else {
                                            rc = powershell(returnStatus: true, script: '''
                                                Set-ExecutionPolicy AllSigned -Scope Process -Force
                                                $ProgressPreference = "silentlyContinue"
                                                if (Get-Command srcclr -ErrorAction SilentlyContinue) {
                                                    srcclr scan --recursive --update-advisor
                                                } else {
                                                    $client = New-Object System.Net.WebClient
                                                    $sca = $client.DownloadString("https://sca-downloads.veracode.com/ci.ps1")
                                                    Invoke-Command -ScriptBlock ([scriptblock]::Create($sca)) `
                                                        -ArgumentList @("scan","--recursive","--update-advisor")
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
                                    if (hasToken) { throw err }   // a real binding/scan error must not be hidden
                                    echo "Agent-Based SCA skipped: ${err}"
                                }
                            }
                        }
                    }

                    // Container/IaC/Secrets: local scan; platform upload only on default branch.
                    stage('Container/IaC/Secrets') {
                        steps {
                            script {
                                boolean toPlatform = (env.VC_IS_DEFAULT == 'true')
                                if (toPlatform) {
                                    // Default branch: HMAC key bound only here, only now.
                                    withCredentials([string(credentialsId: 'veracode-api-id', variable: 'VERACODE_API_ID'),
                                                     string(credentialsId: 'veracode-api-key', variable: 'VERACODE_API_KEY')]) {
                                        runIacScan(true)
                                    }
                                } else {
                                    // PR/feature build: local-only, no tenant credentials.
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
            // SAST (Package + Policy): default branch only, on a toolchain agent.
            // A fresh checkout runs on the SAST node so no large source stash is
            // shipped between agents. HMAC creds are bound only for the wrapper
            // upload, via a 0600 credentials file, never on the command line.
            // -----------------------------------------------------------------
            stage('SAST') {
                when {
                    beforeAgent true
                    allOf {
                        expression { env.VC_IS_TOP_LEVEL == 'true' }
                        expression { env.VC_SAST_LABEL != null && env.VC_SAST_LABEL != '' }
                    }
                }
                agent { label "${env.VC_SAST_LABEL}" }
                stages {
                    stage('Package Artifacts') {
                        steps {
                            script {
                                checkout scm
                                installVeracodeCli()
                                if (config.buildSteps) {
                                    // buildSteps runs with no Veracode creds in scope.
                                    echo 'Package: running repo-supplied buildSteps (autopackager skipped).'
                                    config.buildSteps.call()
                                    ensureVerascanNonEmpty()
                                } else {
                                    runAutopackager()
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

            // Loud, visible signal if SAST is due but no toolchain label is set.
            stage('SAST routing check') {
                when {
                    allOf {
                        expression { env.VC_IS_TOP_LEVEL == 'true' }
                        expression { env.VC_SAST_LABEL == null || env.VC_SAST_LABEL == '' }
                    }
                }
                steps {
                    script {
                        unstable('SAST skipped: no toolchain agent label set. ' +
                                 'Set VERACODE_SAST_AGENT_LABEL on the org folder (or sastAgentLabel) to enable SAST.')
                    }
                }
            }
        }

        post {
            always {
                echo "Build finished with status: ${currentBuild.currentResult}"
                cleanWs(deleteDirs: true, notFailBuild: true)
            }
            failure { echo 'Veracode pipeline failed. Review the stage logs.' }
        }
    }
}

// =============================================================================
// Helpers (same vars file; callable from call()).
// =============================================================================

private boolean asBool(Object cfg, Object envVal, boolean dflt) {
    def truthy = ['true', '1', 'yes', 'on']
    if (cfg != null)            return truthy.contains(cfg.toString().trim().toLowerCase())
    if (envVal?.toString()?.trim()) return truthy.contains(envVal.toString().trim().toLowerCase())
    return dflt
}

// Default-branch detection. Primary signal is BRANCH_IS_PRIMARY from the GitHub
// branch source; TOP_LEVEL_BRANCHES is an optional regex fallback.
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

// Prefer a pre-staged on-PATH veracode binary. Only download if absent, and
// verify sha256 when one is supplied.
private void installVeracodeCli() {
    if (isUnix()) {
        sh '''
            set -e
            if command -v veracode >/dev/null 2>&1; then
                echo "Using pre-staged Veracode CLI: $(command -v veracode)"
                veracode version || true
                exit 0
            fi
            echo "WARNING: no pre-staged Veracode CLI; downloading. Pre-stage a pinned CLI on agents to avoid this."
            curl -fsS https://tools.veracode.com/veracode-cli/install -o install_cli.sh
            if [ -n "${VC_CLI_SHA256:-}" ]; then
                echo "${VC_CLI_SHA256}  install_cli.sh" | sha256sum -c -
            fi
            sh install_cli.sh
            ./veracode version
        '''
    } else {
        powershell '''
            $ProgressPreference = "silentlyContinue"
            if (Get-Command veracode -ErrorAction SilentlyContinue) {
                Write-Host "Using pre-staged Veracode CLI"
                veracode version
                exit 0
            }
            Write-Host "WARNING: no pre-staged Veracode CLI; downloading. Pre-stage a pinned CLI to avoid this."
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

// IaC/secrets directory scan. withCreds=false means no platform credentials in
// scope (PR/feature builds). On the default branch the caller wraps this in
// withCredentials and passes true so results can reach the platform.
private void runIacScan(boolean withCreds) {
    if (isUnix()) {
        sh """
            ${withCreds ? 'export VERACODE_API_KEY_ID="\$VERACODE_API_ID"; export VERACODE_API_KEY_SECRET="\$VERACODE_API_KEY"' : 'echo "Local IaC/secrets scan (no platform credentials on this build)."'}
            SRC="\$VC_SRC"
            VERACODE_BIN="./veracode"; command -v veracode >/dev/null 2>&1 && VERACODE_BIN="veracode"
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
        // Gate on presence of findings; tune the predicate to your policy.
        int findings = isUnix()
            ? sh(returnStatus: true, script: 'grep -q "\\"severity\\"" container_iac_secrets.json 2>/dev/null')
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
            VERACODE_BIN="./veracode"; command -v veracode >/dev/null 2>&1 && VERACODE_BIN="veracode"
            echo "Running Veracode autopackager on: $SRC"
            rm -rf verascan && mkdir -p verascan
            "$VERACODE_BIN" package --source "$SRC" --output verascan
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
            & $veracodeExe package --source "$src" --output verascan
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
// not Groovy interpolation) and removed after the run. The wrapper version is
// pinned when VC_WRAPPER_VER is set.
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
                    VERSION=$(curl -fsSL "$BASE/maven-metadata.xml" | sed -n 's:.*<latest>\\(.*\\)</latest>.*:\\1:p')
                fi
                [ -n "$VERSION" ] || { echo "No wrapper version" >&2; exit 1; }
                echo "Java API Wrapper version: $VERSION"

                rm -rf .veracode_jar && mkdir -p .veracode_jar
                curl -fsSL -o .veracode_jar/dist.zip "$BASE/$VERSION/vosp-api-wrappers-java-$VERSION-dist.zip"
                unzip -o -q .veracode_jar/dist.zip -d .veracode_jar
                JAR=$(find .veracode_jar -name 'VeracodeJavaAPI*.jar' | head -n 1)
                [ -n "$JAR" ] || { echo "wrapper jar not found" >&2; exit 1; }

                # credentials file, 0600, from masked env vars; removed on exit.
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
                java -jar "$JAR" \\
                    -action UploadAndScan \\
                    -appname "$VC_APP_NAME" \\
                    -createprofile true \\
                    -autoscan true \\
                    -filepath "verascan" \\
                    -version "$BRANCH_NAME $BUILD_NUMBER"
                RC=$?
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

                # credentials file from masked env vars; removed in finally.
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
