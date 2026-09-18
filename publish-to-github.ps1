# ============================================================
#  Publish the current version to GitHub Releases
#
#  Uploads the APK and version.json as release assets so the phone can
#  update from any network (with a VPN where github.com itself is blocked,
#  since the app falls back to api.github.com automatically).
#
#  Requires:
#    - the release APK already built by publish-app.ps1
#    - a GitHub token with repo scope
#
#  Token lookup order:
#    1. $env:GH_TOKEN
#    2. D:\Server\stock\eqr6-app\.github-token   (gitignored)
#
#  Usage:
#    powershell -ExecutionPolicy Bypass -File publish-to-github.ps1
#    powershell -ExecutionPolicy Bypass -File publish-to-github.ps1 -Notes "what changed"
#
#  NOTE: ASCII-only source.
# ============================================================

[CmdletBinding()]
param(
    [string]$Owner = 'langEQR6',
    [string]$Repo  = 'eqr6-app',
    [string]$Notes = '',
    [string]$Tag   = '',
    [switch]$SkipIfExists
)

$ErrorActionPreference = 'Stop'

$AppDir    = 'D:\Server\stock\eqr6-app'
$GradleFile = Join-Path $AppDir 'app\build.gradle.kts'
$TokenFile = Join-Path $AppDir '.github-token'
$ApkPath   = 'D:\Server\Share\app\eqr6-app-release.apk'
$ManifestPath = 'D:\Server\Share\app\version.json'

function Say { param([string]$T, [string]$C = 'Gray') Write-Host $T -ForegroundColor $C }

Say ''
Say '=================================================' 'Cyan'
Say '   Publish to GitHub Releases' 'Cyan'
Say '=================================================' 'Cyan'
Say ''

# ---- token ----------------------------------------------------------
$token = $env:GH_TOKEN
if (-not $token -and (Test-Path $TokenFile)) {
    $token = (Get-Content -LiteralPath $TokenFile -Raw).Trim()
}
if (-not $token) {
    Say 'FAILED: no GitHub token found.' 'Red'
    Say "  set `$env:GH_TOKEN, or create $TokenFile" 'Yellow'
    exit 1
}

# ---- inputs ---------------------------------------------------------
if (-not (Test-Path $ApkPath))      { Say "FAILED: APK not found: $ApkPath" 'Red'; exit 1 }
if (-not (Test-Path $ManifestPath)) { Say "FAILED: manifest not found: $ManifestPath" 'Red'; exit 1 }

$content = [System.IO.File]::ReadAllText($GradleFile, [System.Text.Encoding]::UTF8)
$versionCode = [int]([regex]::Match($content, 'versionCode\s*=\s*(\d+)')).Groups[1].Value
$versionName = ([regex]::Match($content, 'versionName\s*=\s*"([^"]*)"')).Groups[1].Value
if (-not $Tag) { $Tag = "v$versionName" }

Say "version : $versionName (build $versionCode)"
Say "tag     : $Tag"
Say "repo    : $Owner/$Repo"

# ---- HTTP helpers ---------------------------------------------------
Add-Type -AssemblyName System.Net.Http

function New-GitHubClient {
    param([string]$Tok)
    $c = New-Object System.Net.Http.HttpClient
    $c.Timeout = [TimeSpan]::FromMinutes(10)
    $c.DefaultRequestHeaders.Add('Authorization', "token $Tok")
    $c.DefaultRequestHeaders.Add('User-Agent', 'EQR6-Server')
    $c.DefaultRequestHeaders.Add('Accept', 'application/vnd.github+json')
    return $c
}

# ---- 1. does the release already exist? -----------------------------
$client = New-GitHubClient -Tok $token
$releaseApi = "https://api.github.com/repos/$Owner/$Repo/releases/tags/$Tag"
$existing = $null
try {
    $resp = $client.GetAsync($releaseApi).Result
    if ($resp.IsSuccessStatusCode) {
        $existing = $resp.Content.ReadAsStringAsync().Result | ConvertFrom-Json
        Say "release $Tag already exists (id=$($existing.id))" 'Yellow'
    }
} catch { }

if ($existing -and $SkipIfExists) {
    Say 'release exists and -SkipIfExists was given - nothing to do' 'Yellow'
    $client.Dispose()
    exit 0
}

# ---- 2. create the release if needed --------------------------------
if (-not $existing) {
    Say ''
    Say '--- creating release ---' 'Cyan'
    $body = @{
        tag_name         = $Tag
        name             = "$versionName (build $versionCode)"
        body             = if ($Notes) { $Notes } else { "EQR6 App $versionName, built on the EQR6 server." }
        draft            = $false
        prerelease       = $false
    } | ConvertTo-Json

    $content2 = New-Object System.Net.Http.StringContent($body, [System.Text.Encoding]::UTF8, 'application/json')
    $resp2 = $client.PostAsync("https://api.github.com/repos/$Owner/$Repo/releases", $content2).Result
    $txt2 = $resp2.Content.ReadAsStringAsync().Result
    if (-not $resp2.IsSuccessStatusCode) {
        Say "FAILED to create release: HTTP $([int]$resp2.StatusCode)" 'Red'
        Say $txt2 'Red'
        $client.Dispose()
        exit 1
    }
    $existing = $txt2 | ConvertFrom-Json
    Say "created release $Tag (id=$($existing.id))" 'Green'
} else {
    Say 'reusing the existing release' 'DarkGray'
}

# ---- 3. upload assets (replacing same-named ones) -------------------
Say ''
Say '--- uploading assets ---' 'Cyan'

function Send-Asset {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Owner, [string]$Repo, [long]$ReleaseId,
        [string]$FilePath, [string]$AssetName
    )
    $uploadBase = "https://uploads.github.com/repos/$Owner/$Repo/releases/$ReleaseId/assets"

    # remove any existing asset with the same name (GitHub rejects duplicates)
    $listResp = $Client.GetAsync("https://api.github.com/repos/$Owner/$Repo/releases/$ReleaseId/assets").Result
    if ($listResp.IsSuccessStatusCode) {
        $assets = $listResp.Content.ReadAsStringAsync().Result | ConvertFrom-Json
        foreach ($a in $assets) {
            if ($a.name -eq $AssetName) {
                $del = $Client.DeleteAsync("https://api.github.com/repos/$Owner/$Repo/releases/assets/$($a.id)").Result
                if ($del.IsSuccessStatusCode) { Say "  removed old asset $AssetName" 'DarkGray' }
            }
        }
    }

    $bytes = [System.IO.File]::ReadAllBytes($FilePath)
    # PowerShell 5.1 flattens a byte[] passed positionally into many
    # constructor arguments. -ArgumentList wraps it so the array is bound
    # to the single byte[] parameter.
    $fileContent = New-Object -TypeName System.Net.Http.ByteArrayContent -ArgumentList (,$bytes)
    $mime = 'application/json'
    if ($AssetName -like '*.apk') { $mime = 'application/vnd.android.package-archive' }
    $fileContent.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue($mime)

    $resp = $Client.PostAsync("${uploadBase}?name=$AssetName", $fileContent).Result
    $body = $resp.Content.ReadAsStringAsync().Result
    if ($resp.IsSuccessStatusCode) {
        $a = $body | ConvertFrom-Json
        Say "  uploaded $AssetName  ($([math]::Round($a.size/1KB,1)) KB, id=$($a.id))" 'Green'
    } else {
        Say "  FAILED $AssetName : HTTP $([int]$resp.StatusCode)" 'Red'
        Say "    $body" 'Red'
        return $false
    }
    return $true
}

$okApk = Send-Asset -Client $client -Owner $Owner -Repo $Repo -ReleaseId $existing.id `
    -FilePath $ApkPath -AssetName 'eqr6-app-release.apk'
$okMan = Send-Asset -Client $client -Owner $Owner -Repo $Repo -ReleaseId $existing.id `
    -FilePath $ManifestPath -AssetName 'version.json'

$client.Dispose()

# ---- 4. summary -----------------------------------------------------
Say ''
Say '=================================================' 'Cyan'
if ($okApk -and $okMan) {
    Say "   PUBLISHED $versionName to GitHub" 'Green'
} else {
    Say '   PARTIAL: some assets failed' 'Yellow'
}
Say '=================================================' 'Cyan'
Say ''
Say '  Phone download URLs (open in browser, or let the app use them):' 'White'
Say "    manifest : https://github.com/$Owner/$Repo/releases/latest/download/version.json" 'DarkGray'
Say "    apk      : https://github.com/$Owner/$Repo/releases/latest/download/eqr6-app-release.apk" 'DarkGray'
Say ''
Say '  Where github.com is blocked, the app falls back to api.github.com' 'DarkGray'
Say '  automatically, so the download still works.' 'DarkGray'
Say ''
