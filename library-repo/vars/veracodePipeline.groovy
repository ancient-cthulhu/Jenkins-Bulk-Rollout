#!/usr/bin/env groovy
// =============================================================================
// veracodePipeline  -  Veracode Security Pipeline as a Jenkins Shared Library
// =============================================================================
// Single, tenant-wide pipeline template. Works on both Linux (bash) and Windows
// (PowerShell) agents by switching on isUnix() at runtime.
//
// Scans:
//   Agent-Based SCA           every build (token-gated, skips if absent)
//   Container/IaC/Secrets      every build (directory scan of source)
//   Policy Scan (SAST)         repo default branch only, post-merge (BRANCH_IS_PRIMARY)
//
// Call from a Jenkinsfile (or a centrally managed default Jenkinsfile):
//   @Library('veracode-pipeline@v1') _
//   veracodePipeline()
//
// Optional per-repo overrides (all also settable as folder/job env vars):
//   veracodePipeline(
//       appName:          'acme-corp/api-service',  // default: org/repo
//       sourceDir:        'app',                    // default: repo root
//       topLevelBranches: 'main',                   // fallback only; normally unset
//       buildSteps:       { sh 'mvn -pl api -am package' }  // optional; see below
//   )
//
// buildSteps (optional closure): replaces the autopackager in the Package
// Artifacts stage for builds the autopackager cannot produce (multi-module,
// monorepo, compiled stacks needing a real build, or prebuilt artifacts). The
// closure MUST leave scannable artifacts in verascan/. sourceDir is unaffected
// and still drives SCA + IaC/secrets, so keep it pointed at real source, not
// verascan. When buildSteps is absent, the autopackager runs as before.
//
// Required credentials, resolved by ID through the folder hierarchy:
//   veracode-api-id   (Secret text, required)
//   veracode-api-key  (Secret text, required)
//   srcclr-api-token  (Secret text, optional - SCA skips if absent)
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

        environment {
            VERACODE_API_ID  = credentials('veracode-api-id')
            VERACODE_API_KEY = credentials('veracode-api-key')
        }

        stages {

            stage('Checkout') {
                steps {
                    checkout scm
                    script {
                        def isCR = (env.CHANGE_ID != null)

                        // SAST/Policy runs only on the repo's DEFAULT branch, post-merge,
                        // never on PRs. A merge to the default branch is a push with no
                        // CHANGE_ID; PRs always set CHANGE_ID.
                        // Primary signal: BRANCH_IS_PRIMARY, set by the GitHub
                        // branch source for the repo's default branch in an org folder.
                        // Optional override (only if a source does not populate it): set
                        // TOP_LEVEL_BRANCHES (or topLevelBranches) to a branch-name regex.
                        def overridePattern = (config.topLevelBranches ?: env.TOP_LEVEL_BRANCHES?.trim())
                        def isDefaultBranch
                        if (env.BRANCH_IS_PRIMARY != null) {
                            isDefaultBranch = (env.BRANCH_IS_PRIMARY == 'true')
                        } else if (overridePattern) {
                            echo "BRANCH_IS_PRIMARY not set by the branch source; falling back to TOP_LEVEL_BRANCHES regex."
                            isDefaultBranch = (env.BRANCH_NAME ==~ /(${overridePattern})/)
                        } else {
                            echo "WARNING: BRANCH_IS_PRIMARY not set and no TOP_LEVEL_BRANCHES override; SAST/Policy will be skipped. Set TOP_LEVEL_BRANCHES to the default branch name to enable it."
                            isDefaultBranch = false
                        }
                        env.IS_TOP_LEVEL = (isDefaultBranch && !isCR) ? 'true' : 'false'

                        // App profile is always org/repo, independent of branch. In a
                        // multibranch job JOB_NAME is org/repo/branch (branch is the last,
                        // URL-encoded segment), so drop the final path segment.
                        def jobPath = env.JOB_NAME
                        def orgRepo = jobPath?.contains('/') ? jobPath.substring(0, jobPath.lastIndexOf('/')) : jobPath
                        env.VERACODE_APP_NAME_RESOLVED = (config.appName ?: env.VERACODE_APP_NAME?.trim()) ?: orgRepo

                        // Resolve the scan/package source once and pass it to every stage.
                        env.VERACODE_SRC = (config.sourceDir ?: env.VERACODE_SOURCE_DIR?.trim()) ?: '.'

                        echo "Branch: ${env.BRANCH_NAME} | Change request: ${isCR} | " +
                             "Default branch: ${isDefaultBranch} | SAST/Policy eligible: ${env.IS_TOP_LEVEL} | " +
                             "App profile: ${env.VERACODE_APP_NAME_RESOLVED} | " +
                             "Source: ${env.VERACODE_SRC}"
                    }
                }
            }

            // -----------------------------------------------------------------
            // Install the Veracode CLI once (every build). Package Artifacts and
            // the Container/IaC/Secrets scan reuse it from the shared workspace
            // (Linux: ./veracode) or the user profile (Windows: %USERPROFILE%).
            // -----------------------------------------------------------------
            stage('Install Veracode CLI') {
                steps {
                    script {
                        if (isUnix()) {
                            sh '''
                                echo "Installing Veracode CLI..."
                                curl -fsS https://tools.veracode.com/veracode-cli/install | sh
                                VERACODE_BIN="./veracode"
                                command -v veracode >/dev/null 2>&1 && VERACODE_BIN="veracode"
                                "$VERACODE_BIN" version
                            '''
                        } else {
                            powershell '''
                                $ProgressPreference = "silentlyContinue"
                                Write-Host "Installing Veracode CLI..."
                                Invoke-WebRequest -Uri "https://tools.veracode.com/veracode-cli/install.ps1" -OutFile "install.ps1"
                                powershell -NoProfile -ExecutionPolicy Bypass -File ".\\install.ps1"
                                $veracodeExe = Join-Path $env:USERPROFILE ".veracode-cli\\veracode.exe"
                                if (!(Test-Path $veracodeExe)) { $veracodeExe = "veracode" }
                                & $veracodeExe version
                            '''
                        }
                    }
                }
            }

            // -----------------------------------------------------------------
            // Package Artifacts (default branch only, post-merge). Uses the Veracode CLI
            // autopackager. Replace with your own build if you already produce
            // deployable artifacts: build here, then point sourceDir at the build
            // output or drop artifacts into verascan/.
            //   Packaging Cheat Sheet: https://docs.veracode.com/cheatsheet/
            // -----------------------------------------------------------------
            stage('Package Artifacts') {
                when { expression { env.IS_TOP_LEVEL == 'true' } }
                steps {
                    script {
                        // Repo-supplied build wins. If the consumer passes a buildSteps
                        // closure, run it instead of the autopackager. Contract: the closure
                        // must leave scannable artifacts in verascan/ (the Policy Scan stage
                        // unstashes and uploads that directory). Use this for builds the
                        // autopackager cannot produce: multi-module Maven/Gradle, monorepos,
                        // compiled stacks needing a real build first, or prebuilt artifacts.
                        // sourceDir still drives SCA + IaC/secrets, so keep it on real source.
                        if (config.buildSteps) {
                            echo "Package Artifacts: running repo-supplied buildSteps (autopackager skipped)."
                            config.buildSteps.call()
                            if (isUnix()) {
                                sh 'test -n "$(find verascan -type f 2>/dev/null)" || { echo "buildSteps left verascan/ empty; nothing to scan" >&2; exit 1; }'
                            } else {
                                powershell 'if (-not (Get-ChildItem -Recurse -File verascan -ErrorAction SilentlyContinue)) { Write-Error "buildSteps left verascan/ empty; nothing to scan"; exit 1 }'
                            }
                        } else if (isUnix()) {
                            sh '''
                                export VERACODE_API_KEY_ID="$VERACODE_API_ID"
                                export VERACODE_API_KEY_SECRET="$VERACODE_API_KEY"
                                SRC="$VERACODE_SRC"

                                # Veracode CLI was installed in the Install Veracode CLI stage.
                                VERACODE_BIN="./veracode"
                                command -v veracode >/dev/null 2>&1 && VERACODE_BIN="veracode"

                                echo "Running Veracode autopackager on: $SRC"
                                rm -rf verascan && mkdir -p verascan
                                "$VERACODE_BIN" package --source "$SRC" --output verascan --trust

                                echo "Packaged artifacts:"
                                find verascan -type f \\( \
                                    -iname '*.war' -o \
                                    -iname '*.jar' -o \
                                    -iname '*.ear' -o \
                                    -iname '*.zip' -o \
                                    -iname '*.tar' -o \
                                    -iname '*.tar.gz' -o \
                                    -iname '*.tgz' -o \
                                    -iname '*.apk' -o \
                                    -iname '*.ipa' -o \
                                    -iname '*.dll' -o \
                                    -iname '*.exe' -o \
                                    -iname '*.pdb' -o \
                                    -iname '*.so' -o \
                                    -iname '*.dylib' -o \
                                    -iname '*.a' -o \
                                    -iname '*.lib' \
                                \\) | tee artifact_list.txt

                                if [ ! -s artifact_list.txt ]; then
                                    echo "No packaged artifacts found" >&2
                                    exit 1
                                fi

                                echo "Total artifacts: $(wc -l < artifact_list.txt)"
                            '''
                        } else {
                            powershell '''
                                $env:VERACODE_API_KEY_ID = $env:VERACODE_API_ID
                                $env:VERACODE_API_KEY_SECRET = $env:VERACODE_API_KEY
                                $sourceDir = $env:VERACODE_SRC

                                # Veracode CLI was installed in the Install Veracode CLI stage.
                                $veracodeExe = Join-Path $env:USERPROFILE ".veracode-cli\\veracode.exe"
                                if (!(Test-Path $veracodeExe)) { $veracodeExe = "veracode" }

                                Write-Host "Running Veracode autopackager on: $sourceDir"
                                Remove-Item -Recurse -Force verascan -ErrorAction SilentlyContinue
                                New-Item -ItemType Directory -Force -Path verascan | Out-Null

                                & $veracodeExe package --source "$sourceDir" --output verascan --trust

                                Write-Host "Packaged files:"
                                Get-ChildItem -Recurse verascan -File | Format-Table FullName, Length -AutoSize

                                $artifactPatterns = @(
                                    '*.war',
                                    '*.jar',
                                    '*.ear',
                                    '*.zip',
                                    '*.tar',
                                    '*.tar.gz',
                                    '*.tgz',
                                    '*.apk',
                                    '*.ipa',
                                    '*.dll',
                                    '*.exe',
                                    '*.pdb',
                                    '*.so',
                                    '*.dylib',
                                    '*.a',
                                    '*.lib'
                                )

                                $artifacts = Get-ChildItem -Path verascan -Recurse -File |
                                    Where-Object {
                                        $fileName = $_.Name
                                        $artifactPatterns | Where-Object { $fileName -like $_ }
                                    }

                                if (!$artifacts -or $artifacts.Count -eq 0) {
                                    Write-Error "No packaged artifacts found"
                                    exit 1
                                }

                                $artifacts.FullName | Out-File -FilePath artifact_list.txt -Encoding utf8

                                Write-Host "Artifacts found:"
                                Get-Content artifact_list.txt

                                Write-Host "Total artifacts: $($artifacts.Count)"
                            '''
                        }
                    }
                }
                post {
                    success {
                        stash name: 'verascan-bundle', includes: 'verascan/**'
                        archiveArtifacts artifacts: 'artifact_list.txt', allowEmptyArchive: true
                    }
                }
            }

            stage('Veracode Security Scans') {
                parallel {

                    // ---------------------------------------------------------
                    // Agent-Based SCA: every build. Skips cleanly if the
                    // srcclr-api-token credential is not configured.
                    // ---------------------------------------------------------
                    stage('Agent-Based SCA') {
                        steps {
                            script {
                                try {
                                    withCredentials([string(credentialsId: 'srcclr-api-token', variable: 'SRCCLR_API_TOKEN')]) {
                                        if (isUnix()) {
                                            sh '''
                                                echo "Running Agent-Based SCA scan..."
                                                curl -sSL https://sca-downloads.veracode.com/ci.sh | sh -s -- scan --recursive --update-advisor
                                            '''
                                        } else {
                                            powershell '''
                                                Set-ExecutionPolicy AllSigned -Scope Process -Force
                                                $ProgressPreference = "silentlyContinue"
                                                Write-Host "Downloading Veracode SCA agent (ci.ps1)..."
                                                $client = New-Object System.Net.WebClient
                                                $sca = $client.DownloadString("https://sca-downloads.veracode.com/ci.ps1")
                                                Write-Host "Running Agent-Based SCA scan..."
                                                Invoke-Command -ScriptBlock ([scriptblock]::Create($sca)) `
                                                    -ArgumentList @("scan", "--recursive", "--update-advisor")
                                            '''
                                        }
                                    }
                                } catch (err) {
                                    echo "Agent-Based SCA skipped (srcclr-api-token missing or scan error): ${err}"
                                }
                            }
                        }
                    }

                    // ---------------------------------------------------------
                    // Container/IaC/Secrets Scan: every build. Directory scan of
                    // source (IaC misconfigurations + secrets). Non-gating by
                    // default. To gate, drop the "|| echo" (Linux) or replace the
                    // Write-Host with "exit $LASTEXITCODE" (Windows).
                    // Secrets rules: container_scan: secret-rules: in veracode.yml
                    // Platform results: analysis_on_platform: true in veracode.yml
                    // ---------------------------------------------------------
                    stage('Container/IaC/Secrets Scan') {
                        steps {
                            script {
                                if (isUnix()) {
                                    sh '''
                                        export VERACODE_API_KEY_ID="$VERACODE_API_ID"
                                        export VERACODE_API_KEY_SECRET="$VERACODE_API_KEY"
                                        SRC="$VERACODE_SRC"

                                        # Veracode CLI was installed in the Install Veracode CLI stage.
                                        VERACODE_BIN="./veracode"
                                        command -v veracode >/dev/null 2>&1 && VERACODE_BIN="veracode"

                                        echo "Running directory scan (IaC + secrets) on: $SRC"
                                        "$VERACODE_BIN" scan \\
                                            --type directory \\
                                            --source "$SRC" \\
                                            --format json \\
                                            --output container_iac_secrets.json \\
                                            || echo "Container/IaC/Secrets scan reported findings or errored (non-gating)."
                                    '''
                                } else {
                                    powershell '''
                                        $ProgressPreference = "silentlyContinue"
                                        # Keep native nonzero exits from throwing, so scan findings
                                        # stay non-gating even if the agent sets this to $true.
                                        $PSNativeCommandUseErrorActionPreference = $false

                                        $env:VERACODE_API_KEY_ID = $env:VERACODE_API_ID
                                        $env:VERACODE_API_KEY_SECRET = $env:VERACODE_API_KEY
                                        $sourceDir = $env:VERACODE_SRC

                                        # Veracode CLI was installed in the Install Veracode CLI stage.
                                        $veracodeExe = Join-Path $env:USERPROFILE ".veracode-cli\\veracode.exe"
                                        if (!(Test-Path $veracodeExe)) { $veracodeExe = "veracode" }

                                        Write-Host "Running directory scan (IaC + secrets) on: $sourceDir"
                                        & $veracodeExe scan --type directory --source "$sourceDir" --format json --output container_iac_secrets.json
                                        if ($LASTEXITCODE -ne 0) {
                                            Write-Host "Container/IaC/Secrets scan reported findings or errored (non-gating). Exit: $LASTEXITCODE"
                                        }
                                    '''
                                }
                            }
                        }
                        post {
                            always {
                                archiveArtifacts artifacts: 'container_iac_secrets.json', allowEmptyArchive: true
                            }
                        }
                    }

                    // ---------------------------------------------------------
                    // Policy Scan: default branch only (post-merge). Production certification.
                    // ---------------------------------------------------------
                    stage('Policy Scan') {
                        when { expression { env.IS_TOP_LEVEL == 'true' } }
                        steps {
                            unstash 'verascan-bundle'
                            script {
                                if (isUnix()) {
                                    sh '''
                                        BASE="https://repo1.maven.org/maven2/com/veracode/vosp/api/wrappers/vosp-api-wrappers-java"

                                        echo "Resolving latest Java API Wrapper..."
                                        VERSION=$(curl -fsSL "$BASE/maven-metadata.xml" | sed -n 's:.*<latest>\\(.*\\)</latest>.*:\\1:p')
                                        [ -n "$VERSION" ] || { echo "Failed to get API Wrapper version" >&2; exit 1; }
                                        echo "Version: $VERSION"

                                        rm -rf .veracode && mkdir -p .veracode
                                        curl -fsSL -o .veracode/dist.zip "$BASE/$VERSION/vosp-api-wrappers-java-$VERSION-dist.zip"
                                        unzip -o -q .veracode/dist.zip -d .veracode

                                        JAR=$(find .veracode -name 'VeracodeJavaAPI*.jar' | head -n 1)
                                        [ -n "$JAR" ] || { echo "VeracodeJavaAPI.jar not found" >&2; exit 1; }

                                        echo "Uploading to Veracode Platform Policy Scan..."
                                        echo "Application: $VERACODE_APP_NAME_RESOLVED"
                                        ls -la verascan

                                        java -jar "$JAR" \\
                                            -vid "$VERACODE_API_ID" \\
                                            -vkey "$VERACODE_API_KEY" \\
                                            -action UploadAndScan \\
                                            -appname "$VERACODE_APP_NAME_RESOLVED" \\
                                            -createprofile true \\
                                            -autoscan true \\
                                            -filepath "verascan" \\
                                            -version "$BRANCH_NAME $BUILD_NUMBER"
                                    '''
                                } else {
                                    powershell '''
                                        Write-Host "Downloading Veracode Java API Wrapper..."
                                        New-Item -ItemType Directory -Force -Path ".veracode" | Out-Null

                                        [xml]$metadata = (Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/com/veracode/vosp/api/wrappers/vosp-api-wrappers-java/maven-metadata.xml").Content
                                        $version = $metadata.metadata.versioning.latest
                                        if (!$version) { Write-Error "Failed to get API Wrapper version" }

                                        Write-Host "Downloading version: $version"
                                        $wrapperUrl = "https://repo1.maven.org/maven2/com/veracode/vosp/api/wrappers/vosp-api-wrappers-java/$version/vosp-api-wrappers-java-$version-dist.zip"
                                        Invoke-WebRequest -Uri $wrapperUrl -OutFile ".veracode\\dist.zip"
                                        Expand-Archive -Path ".veracode\\dist.zip" -DestinationPath ".veracode" -Force

                                        $jar = Get-ChildItem -Path ".veracode" -Recurse -File -Filter "VeracodeJavaAPI*.jar" | Select-Object -First 1
                                        if (!$jar) { Write-Error "VeracodeJavaAPI.jar not found" }
                                        Copy-Item -Force $jar.FullName "vosp-api-wrapper-java.jar"

                                        $appName = $env:VERACODE_APP_NAME_RESOLVED

                                        Write-Host "Uploading to Veracode Platform Policy Scan..."
                                        Write-Host "Application: $appName"
                                        Get-ChildItem verascan

                                        & java -jar vosp-api-wrapper-java.jar `
                                            -vid "$env:VERACODE_API_ID" `
                                            -vkey "$env:VERACODE_API_KEY" `
                                            -action UploadAndScan `
                                            -appname "$appName" `
                                            -createprofile true `
                                            -autoscan true `
                                            -filepath "verascan" `
                                            -version "$env:BRANCH_NAME $env:BUILD_NUMBER"
                                    '''
                                }
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                echo "Build finished with status: ${currentBuild.currentResult}"
                cleanWs(deleteDirs: true, notFailBuild: true)
            }
            failure {
                echo "Veracode pipeline failed. Review the stage logs."
            }
        }
    }
}
