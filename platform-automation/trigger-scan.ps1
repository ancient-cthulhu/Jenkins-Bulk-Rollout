<#
.SYNOPSIS
    trigger-scan.ps1 -- On-demand Veracode/Jenkins scan trigger.

.DESCRIPTION
    No webhook and no periodic poll are configured (see SOLUTION.md). Scans
    only run when explicitly triggered. Use this script to trigger one from
    a terminal instead of the Jenkins UI.
    Must be run from somewhere that can reach $JENKINS_URL (same constraint
    as rollout.ps1: the Jenkins controller, or a bastion on the same network).

.EXAMPLE
    .\trigger-scan.ps1 -Org my-org
    Rescan the whole org: discover new/renamed/deleted repos, trigger builds
    on anything changed since the last scan. Same as "Scan Organization Now".

.EXAMPLE
    .\trigger-scan.ps1 -Org my-org -Repo my-repo
    Rescan just that repo: discover new/changed branches and PRs, trigger
    builds on anything changed. Same as "Scan Repository Now". Faster than
    a full org rescan.

.EXAMPLE
    .\trigger-scan.ps1 -Org my-org -Repo my-repo -Branch main
    Skip scanning entirely and trigger an immediate build of that one
    branch job. Fastest option if you already know what you want built.
#>

param(
    [Parameter(Mandatory = $true)][string]$Org,
    [string]$Repo,
    [string]$Branch
)

$ErrorActionPreference = "Stop"

# ==============================================================================
# CONFIGURE
# ==============================================================================
$JENKINS_URL    = "http://your-jenkins-host:8080"
$JENKINS_USER   = "admin"
$JENKINS_FOLDER = ""   # must match JENKINS_FOLDER used in rollout.ps1, if any
# $env:JENKINS_TOKEN read from env var; falls back to JENKINS_USER.
# ==============================================================================

$JENKINS_TOKEN = if ($env:JENKINS_TOKEN) { $env:JENKINS_TOKEN } else { $JENKINS_USER }

if ($Branch -and -not $Repo) {
    Write-Error "ERROR: -Branch requires -Repo."
    exit 1
}

function Get-JenkinsAuthHeader {
    $pair = "${JENKINS_USER}:${JENKINS_TOKEN}"
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($pair)
    @{ "Authorization" = "Basic " + [Convert]::ToBase64String($bytes) }
}

$Session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Get-JenkinsCrumbHeaders {
    try {
        $resp = Invoke-WebRequest -Uri "$JENKINS_URL/crumbIssuer/api/json" `
            -Headers (Get-JenkinsAuthHeader) -WebSession $Session -UseBasicParsing
        $d = $resp.Content | ConvertFrom-Json
        $h = Get-JenkinsAuthHeader
        $h[$d.crumbRequestField] = $d.crumb
        return $h
    } catch {
        return (Get-JenkinsAuthHeader)  # CSRF may be disabled
    }
}

$folderPrefix = ""
if ($JENKINS_FOLDER) {
    $folderPath = $JENKINS_FOLDER.Trim("/")
    $folderPrefix = "/job/" + ($folderPath -replace "/", "/job/")
}

if ($Branch) {
    $targetUrl = "$JENKINS_URL$folderPrefix/job/$Org/job/$Repo/job/$Branch/build"
    $desc = "build of $Org/$Repo@$Branch"
} elseif ($Repo) {
    $targetUrl = "$JENKINS_URL$folderPrefix/job/$Org/job/$Repo/build"
    $desc = "repository scan of $Org/$Repo"
} else {
    $targetUrl = "$JENKINS_URL$folderPrefix/job/$Org/build"
    $desc = "organization scan of $Org"
}

Write-Host "Triggering $desc ..."
$headers = Get-JenkinsCrumbHeaders
try {
    $resp = Invoke-WebRequest -Uri $targetUrl -Method Post -Headers $headers `
        -WebSession $Session -UseBasicParsing
    Write-Host "  Triggered (HTTP $([int]$resp.StatusCode))."
} catch {
    $status = [int]$_.Exception.Response.StatusCode
    if ($status -eq 404) {
        Write-Host "  ERROR: HTTP 404. Check the org/repo/branch names and JENKINS_FOLDER, or that"
        Write-Host "  the org has already been discovered by at least one scan before."
    } else {
        Write-Host "  ERROR: HTTP $status from $targetUrl"
    }
    exit 1
}
