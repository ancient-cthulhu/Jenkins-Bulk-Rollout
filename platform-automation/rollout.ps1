<#
.SYNOPSIS
    rollout.ps1 -- PowerShell port of rollout.py. Same steps, same
    Jenkins/GitHub REST calls, no Python required.

.DESCRIPTION
    This file (rollout.ps1) is the safe template: placeholder values only,
    fine to commit as-is. Before running it for real, copy it to
    rollout.example.ps1 (covered by .gitignore, so it can never be committed
    with real values in it), fill in the CONFIG block in that copy, then
    run it:
        Copy-Item rollout.ps1 rollout.example.ps1
        .\rollout.example.ps1

    Editing and running rollout.ps1 directly also works, but then be
    deliberate about not committing it once CONFIG holds real org names,
    tokens, or URLs.

.NOTES
    Requires: PowerShell 5.1+ (or pwsh 7+), git on PATH.
#>

$ErrorActionPreference = "Stop"

# ==============================================================================
# CONFIGURE EVERYTHING HERE
# ==============================================================================

# --- Platform org (where veracode-pipeline and jenkins-platform repos live) ---
$PLATFORM_ORG = "your-github-org"

# --- Orgs to scan ---
$SCAN_ORGS = @(
    "your-github-org"
    # "another-org-to-scan"
)

# --- Jenkins folder (optional) ---
# Leave empty for top-level creation. Example: "veracode" or "veracode/github"
$JENKINS_FOLDER = ""

# --- Library version ---
$LIBRARY_VERSION = "v1"

# --- GitHub ---
# PAT read from env var GITHUB_TOKEN (or GH_PAT / GH_TOKEN as fallbacks).
$GITHUB_USERNAME = "your-github-username"

# --- Veracode ---
# Read from env vars VC_API_ID and VC_API_KEY (or VERACODE_API_KEY_ID /
# VERACODE_API_KEY_SECRET as fallbacks).

# --- Jenkins ---
$JENKINS_URL  = "http://your-jenkins-host:8080"
$JENKINS_USER = "admin"
# Read from env var JENKINS_TOKEN; falls back to JENKINS_USER value.

# --- GitHub API base ---
# Change for GitHub Enterprise, e.g. "https://github.example.com/api/v3"
$GITHUB_API = "https://api.github.com"

# --- Repo visibility ---
$REPOS_PRIVATE = $true

# ==============================================================================
# END OF CONFIG -- nothing below should need editing for a standard rollout
# ==============================================================================

function Require-Env {
    param([string]$Label, [string[]]$Names)
    foreach ($n in $Names) {
        $v = [Environment]::GetEnvironmentVariable($n)
        if (-not [string]::IsNullOrWhiteSpace($v)) { return $v }
    }
    Write-Error "ERROR: $Label not set. Export one of: $($Names -join ', ')"
    exit 1
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Error "ERROR: git is required but not installed."
    exit 1
}

$GITHUB_TOKEN     = Require-Env "GitHub PAT (GITHUB_TOKEN)" @("GITHUB_TOKEN", "GH_PAT", "GH_TOKEN")
$VERACODE_API_ID  = Require-Env "Veracode API ID (VC_API_ID)" @("VC_API_ID", "VERACODE_API_KEY_ID")
$VERACODE_API_KEY = Require-Env "Veracode API Key (VC_API_KEY)" @("VC_API_KEY", "VERACODE_API_KEY_SECRET")
$JENKINS_TOKEN    = if ($env:JENKINS_TOKEN) { $env:JENKINS_TOKEN } else { $JENKINS_USER }

$SCRIPT_DIR  = Split-Path -Parent $MyInvocation.MyCommand.Path
$BASE_DIR    = Split-Path -Parent $SCRIPT_DIR
$LIBRARY_DIR = Join-Path $BASE_DIR "library-repo"
$PLATFORM_DIR= Join-Path $BASE_DIR "platform-automation"

$Script:JenkinsSession = $null   # WebRequestSession, holds the crumb cookie

# ==============================================================================
# GITHUB HELPERS
# ==============================================================================

function Get-GhHeaders {
    @{
        "Authorization"        = "token $GITHUB_TOKEN"
        "Accept"               = "application/vnd.github+json"
        "X-GitHub-Api-Version" = "2022-11-28"
    }
}

function Invoke-GhGet {
    param([string]$Path)
    try {
        $resp = Invoke-WebRequest -Uri "$GITHUB_API$Path" -Headers (Get-GhHeaders) -Method Get -UseBasicParsing
        return @{ Status = [int]$resp.StatusCode; Body = ($resp.Content | ConvertFrom-Json) }
    } catch {
        $status = [int]$_.Exception.Response.StatusCode
        $body = $null
        try { $body = ($_.ErrorDetails.Message | ConvertFrom-Json) } catch {}
        return @{ Status = $status; Body = $body }
    }
}

function Invoke-GhPost {
    param([string]$Path, [hashtable]$Body)
    $json = $Body | ConvertTo-Json -Depth 10
    try {
        $resp = Invoke-WebRequest -Uri "$GITHUB_API$Path" -Headers (Get-GhHeaders) -Method Post `
            -ContentType "application/json" -Body $json -UseBasicParsing
        return @{ Status = [int]$resp.StatusCode; Body = ($resp.Content | ConvertFrom-Json) }
    } catch {
        $status = [int]$_.Exception.Response.StatusCode
        $body = $null
        try { $body = ($_.ErrorDetails.Message | ConvertFrom-Json) } catch {}
        return @{ Status = $status; Body = $body }
    }
}

function New-GitHubRepo {
    param([string]$Name, [string]$Description)

    $existing = Invoke-GhGet "/repos/$PLATFORM_ORG/$Name"
    if ($existing.Status -eq 200) {
        Write-Host "  ${Name}: already exists at $($existing.Body.html_url)"
        return $existing.Body.clone_url
    }

    Write-Host "  Creating $PLATFORM_ORG/$Name ..."
    $created = Invoke-GhPost "/orgs/$PLATFORM_ORG/repos" @{
        name         = $Name
        description  = $Description
        private      = $REPOS_PRIVATE
        auto_init    = $false
    }
    if ($created.Status -ne 200 -and $created.Status -ne 201) {
        Write-Error "  ERROR creating repo: $($created.Status) $($created.Body)"
        exit 1
    }
    Write-Host "  Created: $($created.Body.html_url)"
    return $created.Body.clone_url
}

function Push-Directory {
    param([string]$SrcDir, [string]$CloneUrl, [string]$Tag = $null)

    $authedUrl = $CloneUrl -replace "^https://", "https://$GITHUB_TOKEN@"
    $env:GIT_TERMINAL_PROMPT = "0"

    Push-Location $SrcDir
    try {
        if (-not (Test-Path ".git")) { git init -q }
        git config user.email "ci@veracode-rollout" | Out-Null
        git config user.name  "Veracode Rollout" | Out-Null
        git add -A | Out-Null

        git diff --cached --quiet
        if ($LASTEXITCODE -ne 0) {
            git commit -q -m "change: rollout commit" | Out-Null
        }

        git remote remove origin 2>$null | Out-Null
        git remote add origin $authedUrl | Out-Null
        git push -u origin HEAD:main

        if ($Tag) {
            git tag -f $Tag | Out-Null
            git push origin "refs/tags/$Tag" -f
            Write-Host "  Tagged $Tag and pushed."
        }
    } finally {
        Pop-Location
    }
}

# ==============================================================================
# JENKINS HELPERS
# ==============================================================================

function Get-JenkinsAuthHeader {
    $pair = "${JENKINS_USER}:${JENKINS_TOKEN}"
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($pair)
    @{ "Authorization" = "Basic " + [Convert]::ToBase64String($bytes) }
}

function Get-JenkinsCrumb {
    # Populates $Script:JenkinsSession, returns @{Field=...; Crumb=...} or $null
    if (-not $Script:JenkinsSession) {
        $Script:JenkinsSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    }
    try {
        $resp = Invoke-WebRequest -Uri "$JENKINS_URL/crumbIssuer/api/json" `
            -Headers (Get-JenkinsAuthHeader) -WebSession $Script:JenkinsSession -UseBasicParsing
        $d = $resp.Content | ConvertFrom-Json
        return @{ Field = $d.crumbRequestField; Crumb = $d.crumb }
    } catch {
        # CSRF may be disabled on some setups; fall through with no crumb.
        return $null
    }
}

function Get-JenkinsHeaders {
    param([hashtable]$Extra = @{})
    $headers = Get-JenkinsAuthHeader
    $crumb = Get-JenkinsCrumb
    if ($crumb) { $headers[$crumb.Field] = $crumb.Crumb }
    foreach ($k in $Extra.Keys) { $headers[$k] = $Extra[$k] }
    return $headers
}

function Invoke-JenkinsScript {
    param([string]$Groovy)
    $headers = Get-JenkinsHeaders
    $body = "script=" + [System.Uri]::EscapeDataString($Groovy)
    try {
        $resp = Invoke-WebRequest -Uri "$JENKINS_URL/scriptText" -Method Post `
            -Headers $headers -ContentType "application/x-www-form-urlencoded" `
            -Body $body -WebSession $Script:JenkinsSession -UseBasicParsing
        return @{ Status = [int]$resp.StatusCode; Output = $resp.Content }
    } catch {
        $status = [int]$_.Exception.Response.StatusCode
        $output = $_.ErrorDetails.Message
        return @{ Status = $status; Output = $output }
    }
}

function Get-CredentialStorePath {
    if ([string]::IsNullOrWhiteSpace($JENKINS_FOLDER)) {
        return "/credentials/store/system/domain/_"
    }
    Ensure-JenkinsFolder
    $folderPath = ($JENKINS_FOLDER.Trim("/")) -replace "/", "/job/"
    return "/job/$folderPath/credentials/store/folder/domain/_"
}

function Ensure-JenkinsFolder {
    if ([string]::IsNullOrWhiteSpace($JENKINS_FOLDER)) { return }
    $current = ""
    foreach ($folder in ($JENKINS_FOLDER -split "/") | Where-Object { $_ -ne "" }) {
        $current = if ($current) { "$current/$folder" } else { $folder }
        New-JenkinsFolderIfNeeded -FolderPath $current -FolderName $folder
    }
}

function New-JenkinsFolderIfNeeded {
    param([string]$FolderPath, [string]$FolderName)

    $folderApiPath = $FolderPath -replace "/", "/job/"
    $checkUrl = "$JENKINS_URL/job/$folderApiPath/api/json"
    try {
        Invoke-WebRequest -Uri $checkUrl -Headers (Get-JenkinsAuthHeader) `
            -WebSession $Script:JenkinsSession -UseBasicParsing | Out-Null
        return  # already exists
    } catch {
        $status = [int]$_.Exception.Response.StatusCode
        if ($status -ne 404) { return }
    }

    $folderConfig = @"
<?xml version='1.0' encoding='UTF-8'?>
<com.cloudbees.hudson.plugins.folder.Folder plugin="cloudbees-folder">
  <description></description>
  <properties/>
</com.cloudbees.hudson.plugins.folder.Folder>
"@

    $headers = Get-JenkinsHeaders -Extra @{ "Content-Type" = "application/xml" }
    $createUrl = "$JENKINS_URL/createItem?name=" + [System.Uri]::EscapeDataString($FolderName)
    try {
        Invoke-WebRequest -Uri $createUrl -Method Post -Headers $headers -Body $folderConfig `
            -WebSession $Script:JenkinsSession -UseBasicParsing | Out-Null
        Write-Host "  Created Jenkins folder: $FolderPath"
    } catch {
        $status = [int]$_.Exception.Response.StatusCode
        if ($status -ne 400) {
            Write-Host "  WARNING creating folder ${FolderPath}: HTTP $status"
        }
    }
}

function Set-JenkinsCredential {
    param([string]$CredXml, [string]$CredId)

    $storePath = Get-CredentialStorePath
    $headers = Get-JenkinsHeaders -Extra @{ "Content-Type" = "application/xml" }

    $updateUrl = "$JENKINS_URL$storePath/credential/$CredId/config.xml"
    try {
        $resp = Invoke-WebRequest -Uri $updateUrl -Method Post -Headers $headers -Body $CredXml `
            -WebSession $Script:JenkinsSession -UseBasicParsing
        Write-Host "  Updated credential: $CredId (HTTP $([int]$resp.StatusCode))"
        return
    } catch {
        $status = [int]$_.Exception.Response.StatusCode
        if ($status -ne 404) {
            Write-Host "  WARNING updating ${CredId}: HTTP $status"
        }
    }

    $createUrl = "$JENKINS_URL$storePath/createCredentials"
    try {
        $resp = Invoke-WebRequest -Uri $createUrl -Method Post -Headers $headers -Body $CredXml `
            -WebSession $Script:JenkinsSession -UseBasicParsing
        Write-Host "  Created credential: $CredId (HTTP $([int]$resp.StatusCode))"
    } catch {
        $status = [int]$_.Exception.Response.StatusCode
        Write-Host "  ERROR creating ${CredId}: HTTP $status"
    }
}

# ==============================================================================
# STEP 1: GitHub repos
# ==============================================================================

function Step-GitHubRepos {
    Write-Host ""
    Write-Host "=== Step 1: Create GitHub repos ==="

    Write-Host ""
    Write-Host "  veracode-pipeline (shared library):"
    $url = New-GitHubRepo "veracode-pipeline" `
        "Veracode Jenkins shared pipeline library -- SCA, IaC/secrets, SAST/Policy"
    Push-Directory $LIBRARY_DIR $url $LIBRARY_VERSION

    Write-Host ""
    Write-Host "  jenkins-platform (platform automation):"
    $url = New-GitHubRepo "jenkins-platform" `
        "Veracode Jenkins platform automation -- JCasC, onboarding, bulk-PR"
    Push-Directory $PLATFORM_DIR $url

    Write-Host ""
    Write-Host "  veracode-pipeline: https://github.com/$PLATFORM_ORG/veracode-pipeline"
    Write-Host "  jenkins-platform:  https://github.com/$PLATFORM_ORG/jenkins-platform"
}

# ==============================================================================
# STEP 2: Jenkins credentials
# ==============================================================================

function Step-JenkinsCredentials {
    Write-Host ""
    Write-Host "=== Step 2: Configure Jenkins credentials ==="
    if ($JENKINS_FOLDER) { Write-Host "  Using Jenkins folder: $JENKINS_FOLDER" }

    $xml = @"
<org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>
  <scope>GLOBAL</scope>
  <id>veracode-api-id</id>
  <description>Veracode API ID</description>
  <secret>$VERACODE_API_ID</secret>
</org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>
"@
    Set-JenkinsCredential $xml "veracode-api-id"

    $xml = @"
<org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>
  <scope>GLOBAL</scope>
  <id>veracode-api-key</id>
  <description>Veracode API Key</description>
  <secret>$VERACODE_API_KEY</secret>
</org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>
"@
    Set-JenkinsCredential $xml "veracode-api-key"

    $xml = @"
<com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl>
  <scope>GLOBAL</scope>
  <id>scm-readonly</id>
  <description>GitHub read-only scan account (PAT). Used for org discovery and library fetch.</description>
  <username>$GITHUB_USERNAME</username>
  <password>$GITHUB_TOKEN</password>
</com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl>
"@
    Set-JenkinsCredential $xml "scm-readonly"

    Write-Host "  Done. Credentials: veracode-api-id, veracode-api-key, scm-readonly"
}

# ==============================================================================
# STEP 3: Configure GitHub Server (no webhook, no polling -- ad hoc only)
# ==============================================================================

function Step-GitHubServer {
    Write-Host ""
    Write-Host "=== Step 3: Configure GitHub Server (no webhook registration) ==="
    # manageHooks=false intentionally -- this deployment is egress-only and
    # has no automatic trigger at all: no webhook, no periodic poll. Every
    # scan is ad hoc (Jenkins UI or trigger-scan.sh/.ps1). This step still
    # registers the GitHub Server entry because it's how the plugin tracks
    # API rate-limit usage against scm-readonly; it does not open any
    # inbound path.

    $groovy = @"
import jenkins.model.Jenkins
import org.jenkinsci.plugins.github.config.GitHubServerConfig
import org.jenkinsci.plugins.github.config.GitHubPluginConfig

def config = Jenkins.get().getDescriptor(GitHubPluginConfig.class)
def servers = new ArrayList(config.configs)
servers.removeIf { it.apiUrl == '$GITHUB_API' }

def server = new GitHubServerConfig('scm-readonly')
server.apiUrl          = '$GITHUB_API'
server.manageHooks     = false
server.clientCacheSize = 20
servers.add(server)

config.configs = servers
config.save()
println "GitHub Server registered: $GITHUB_API (manageHooks=false, no inbound webhook, credential=scm-readonly)"
"@
    $result = Invoke-JenkinsScript $groovy
    if ($result.Status -eq 200 -and $result.Output -match "GitHub Server registered") {
        Write-Host "  $($result.Output.Trim())"
    } else {
        Write-Host "  ERROR: $($result.Status)"
        Write-Host "  $($result.Output.Substring(0, [Math]::Min(300, $result.Output.Length)))"
        exit 1
    }
}

# ==============================================================================
# STEP 4: Register the shared library
# ==============================================================================

function Step-RegisterLibrary {
    Write-Host ""
    Write-Host "=== Step 4: Register shared library in Jenkins ==="

    $libraryUrl = "https://github.com/$PLATFORM_ORG/veracode-pipeline.git"
    $groovy = @"
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.libs.*
import jenkins.plugins.git.GitSCMSource

def jenkins   = Jenkins.get()
def globalLib = jenkins.getDescriptor(GlobalLibraries.class)

globalLib.libraries.removeIf { it.name == 'veracode-pipeline' }

def source = new GitSCMSource('$libraryUrl')
source.credentialsId = 'scm-readonly'

def retriever = new SCMSourceRetriever(source)
def lib = new LibraryConfiguration('veracode-pipeline', retriever)
lib.defaultVersion     = '$LIBRARY_VERSION'
lib.implicit           = false
lib.allowVersionOverride = true
lib.includeInChangesets  = false

globalLib.libraries.add(lib)
globalLib.save()
println "Library registered: veracode-pipeline @ $LIBRARY_VERSION -> $libraryUrl"
"@
    $result = Invoke-JenkinsScript $groovy
    if ($result.Status -eq 200) {
        Write-Host "  $($result.Output.Trim())"
    } else {
        Write-Host "  ERROR registering library: HTTP $($result.Status)"
        Write-Host "  $($result.Output.Substring(0, [Math]::Min(300, $result.Output.Length)))"
        exit 1
    }
}

# ==============================================================================
# STEP 5: Run veracode-onboard.groovy
# ==============================================================================

function Step-OnboardOrgs {
    Write-Host ""
    Write-Host "=== Step 5: Run veracode-onboard.groovy ==="

    $onboardPath = Join-Path $PLATFORM_DIR "veracode-onboard.groovy"
    $lines = Get-Content -Path $onboardPath

    $orgsBlockLines = @("@Field List<String> ORGS = [")
    foreach ($o in $SCAN_ORGS) { $orgsBlockLines += "    '$o'," }
    $orgsBlockLines += "]"

    # Replace the (possibly multi-line) ORGS declaration.
    $out = New-Object System.Collections.Generic.List[string]
    $skip = $false
    foreach ($line in $lines) {
        if (-not $skip -and $line -match "@Field\s+List<String>\s+ORGS\s*=") {
            $out.AddRange($orgsBlockLines)
            $skip = -not ($line -match "\]")
            continue
        }
        if ($skip) {
            if ($line -match "\]") { $skip = $false }
            continue
        }
        $out.Add($line)
    }

    if ($JENKINS_FOLDER) {
        for ($i = 0; $i -lt $out.Count; $i++) {
            if ($out[$i] -match "@Field\s+final\s+String\s+PARENT_FOLDER") {
                $out[$i] = "@Field final String PARENT_FOLDER = '$JENKINS_FOLDER'"
            }
        }
        Write-Host "  Jenkins folder: $JENKINS_FOLDER"
    }

    $script = ($out -join "`n")

    Write-Host "  Scanning orgs: $($SCAN_ORGS -join ', ')"
    $result = Invoke-JenkinsScript $script

    foreach ($line in ($result.Output -split "`n")) {
        if ($line.Trim()) { Write-Host "  $line" }
    }

    if ($result.Status -ne 200) {
        Write-Host "  ERROR: Script Console returned HTTP $($result.Status)"
        exit 1
    }
    if ($result.Output -match "FAILED") {
        Write-Host "  WARNING: one or more orgs reported failures (see above)"
    }
}

# ==============================================================================
# MAIN
# ==============================================================================

function Main {
    Write-Host ("=" * 60)
    Write-Host "  Veracode + Jenkins rollout"
    Write-Host "  Platform org   : $PLATFORM_ORG"
    Write-Host "  Scan orgs      : $($SCAN_ORGS -join ', ')"
    if ($JENKINS_FOLDER) { Write-Host "  Jenkins folder : $JENKINS_FOLDER" }
    Write-Host "  Library ver    : $LIBRARY_VERSION"
    Write-Host "  Jenkins        : $JENKINS_URL"
    Write-Host ("=" * 60)

    Step-GitHubRepos
    Step-JenkinsCredentials
    Step-GitHubServer
    Step-RegisterLibrary
    Step-OnboardOrgs

    $orgsArg = $SCAN_ORGS -join " "
    Write-Host @"

=== Rollout complete ===

  veracode-pipeline : https://github.com/$PLATFORM_ORG/veracode-pipeline (tag $LIBRARY_VERSION)
  jenkins-platform  : https://github.com/$PLATFORM_ORG/jenkins-platform
  Jenkins           : $JENKINS_URL

Next step -- open Jenkinsfile PRs across each org:

  python3 bulk_add_jenkinsfile.py --orgs $orgsArg --lib-version $LIBRARY_VERSION --dry-run
  python3 bulk_add_jenkinsfile.py --orgs $orgsArg --lib-version $LIBRARY_VERSION --skip-archived --skip-forks --yes

(Ask for a bulk_add_jenkinsfile.sh / .ps1 port if this client also needs
 the bulk-PR step without Python.)

Review and merge the PRs. Jenkins will start scanning on the next push.

"@
}

Main
