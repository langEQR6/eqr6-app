# ============================================================
#  Build and publish the EQR6 Android app
#
#  What it does:
#    1. builds a signed release APK
#    2. copies it into the shared folder (D:\Server\Share\app)
#    3. writes version.json (the OTA manifest the app reads)
#    4. prints a summary + SHA256 so updates can be verified
#
#  Usage:
#    powershell -ExecutionPolicy Bypass -File publish-app.ps1
#    powershell -ExecutionPolicy Bypass -File publish-app.ps1 -BumpBuild
#
#  -BumpBuild increments versionCode/versionName in app/build.gradle.kts,
#  which is REQUIRED for the phone to treat it as a new version.
#
#  NOTE: ASCII-only source.
# ============================================================

[CmdletBinding()]
param(
    [switch]$BumpBuild,
    [string]$Notes = '',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

# ---- toolchain paths -------------------------------------------------
# Prefer the newest JDK 17 present under C:\Java: AGP 8.5 wants 17.0.7+,
# and a stale hardcoded path silently kept an older JDK in use before.
$JavaHome   = 'C:\Java\jdk-17.0.2'
$AndroidSdk = 'C:\Android\Sdk'
$Gradle     = 'C:\Gradle\gradle-8.9\bin\gradle.bat'

$bestJdk = Get-ChildItem 'C:\Java' -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^jdk-17' -and (Test-Path (Join-Path $_.FullName 'bin\java.exe')) } |
    Sort-Object { [version](($_.Name -replace '^jdk-','') -replace '\+.*$','') } -Descending |
    Select-Object -First 1
if ($bestJdk) { $JavaHome = $bestJdk.FullName }

$ProjectDir = 'D:\Server\stock\eqr6-app'
$GradleFile = Join-Path $ProjectDir 'app\build.gradle.kts'
$ShareDir   = 'D:\Server\Share\app'
$ApkOutDir  = Join-Path $ProjectDir 'app\build\outputs\apk\release'

function Say { param([string]$T, [string]$C = 'Gray') Write-Host $T -ForegroundColor $C }

Say ''
Say '=================================================' 'Cyan'
Say '   Build and publish EQR6 app' 'Cyan'
Say '=================================================' 'Cyan'
Say ''

if (-not (Test-Path $ProjectDir)) { Say "FAILED: project not found: $ProjectDir" 'Red'; exit 1 }
if (-not (Test-Path $Gradle))     { Say "FAILED: gradle not found: $Gradle" 'Red'; exit 1 }

# ---- optional version bump -------------------------------------------
if ($BumpBuild) {
    Say '--- bumping version ---' 'Cyan'
    $content = [System.IO.File]::ReadAllText($GradleFile, [System.Text.Encoding]::UTF8)

    $mCode = [regex]::Match($content, 'versionCode\s*=\s*(\d+)')
    $mName = [regex]::Match($content, 'versionName\s*=\s*"([^"]*)"')
    if (-not $mCode.Success -or -not $mName.Success) {
        Say '  FAILED: could not find versionCode/versionName' 'Red'; exit 1
    }

    $oldCode = [int]$mCode.Groups[1].Value
    $oldName = $mName.Groups[1].Value
    $newCode = $oldCode + 1

    # 1.0 -> 1.1 -> 1.2 ... then 1.9 -> 1.10 (keep it simple and readable)
    $parts = $oldName.Split('.')
    if ($parts.Length -ge 2) {
        $minor = 0
        if ([int]::TryParse($parts[1], [ref]$minor)) { $parts[1] = "$($minor + 1)" } else { $parts[1] = '1' }
        $newName = ($parts -join '.')
    } else {
        $newName = "$oldName.1"
    }

    $content = $content -replace "versionCode\s*=\s*\d+", "versionCode = $newCode"
    $content = $content -replace 'versionName\s*=\s*"[^"]*"', "versionName = `"$newName`""
    [System.IO.File]::WriteAllText($GradleFile, $content, (New-Object System.Text.UTF8Encoding($false)))

    Say "  versionCode: $oldCode -> $newCode" 'Green'
    Say "  versionName: $oldName -> $newName" 'Green'
}

# ---- read the version we are building --------------------------------
$content = [System.IO.File]::ReadAllText($GradleFile, [System.Text.Encoding]::UTF8)
$versionCode = [int]([regex]::Match($content, 'versionCode\s*=\s*(\d+)')).Groups[1].Value
$versionName = ([regex]::Match($content, 'versionName\s*=\s*"([^"]*)"')).Groups[1].Value

Say ''
Say "building version $versionName (code $versionCode)" 'Cyan'

# ---- build -----------------------------------------------------------
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:Path = "$JavaHome\bin;$env:Path"

if (-not $SkipBuild) {
    Say ''
    Say '--- gradle assembleRelease ---' 'Cyan'
    Push-Location $ProjectDir
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $out = & $Gradle assembleRelease --no-daemon --console=plain 2>&1
    $sw.Stop()
    Pop-Location

    $failed = ($out | Select-String -Pattern 'BUILD FAILED' -Quiet)
    if ($failed) {
        Say "  BUILD FAILED after $([math]::Round($sw.Elapsed.TotalSeconds,1))s" 'Red'
        Say ''
        $out | Select-String -Pattern 'e: |error:|What went wrong|Execution failed' |
            Select-Object -Last 20 | ForEach-Object { Say "  $($_.Line)" 'Red' }
        exit 1
    }
    Say "  BUILD SUCCEEDED in $([math]::Round($sw.Elapsed.TotalSeconds,1))s" 'Green'
} else {
    Say '  (skipping build, publishing existing APK)' 'Yellow'
}

# ---- locate the APK --------------------------------------------------
$apk = Get-ChildItem $ApkOutDir -Filter '*.apk' -ErrorAction SilentlyContinue |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { Say "FAILED: no APK in $ApkOutDir" 'Red'; exit 1 }

# ---- publish ---------------------------------------------------------
Say ''
Say '--- publishing to shared folder ---' 'Cyan'
if (-not (Test-Path $ShareDir)) { New-Item -ItemType Directory -Path $ShareDir -Force | Out-Null }

$targetName = 'eqr6-app-release.apk'
$targetApk = Join-Path $ShareDir $targetName
Copy-Item -LiteralPath $apk.FullName -Destination $targetApk -Force

$sha = (Get-FileHash -LiteralPath $targetApk -Algorithm SHA256).Hash
$sizeMb = [math]::Round((Get-Item $targetApk).Length / 1MB, 2)

# manifest the app polls; apkUrl is relative to this file's own location
$manifest = [ordered]@{
    versionCode = $versionCode
    versionName = $versionName
    apkUrl      = $targetName
    sha256      = $sha
    sizeBytes   = (Get-Item $targetApk).Length
    publishedAt = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    notes       = $Notes
}
$manifestPath = Join-Path $ShareDir 'version.json'
$json = $manifest | ConvertTo-Json -Depth 3
[System.IO.File]::WriteAllText($manifestPath, $json, (New-Object System.Text.UTF8Encoding($false)))

Say "  APK      : $targetApk" 'Green'
Say "  size     : $sizeMb MB" 'Green'
Say "  manifest : $manifestPath" 'Green'

# ---- also keep a versioned copy for history ---------------------------
$historyDir = Join-Path $ShareDir 'history'
if (-not (Test-Path $historyDir)) { New-Item -ItemType Directory -Path $historyDir -Force | Out-Null }
$histName = "eqr6-app-$versionName-$versionCode.apk"
Copy-Item -LiteralPath $targetApk -Destination (Join-Path $historyDir $histName) -Force
Say "  history  : history\$histName"

# ---- summary ---------------------------------------------------------
Say ''
Say '=================================================' 'Cyan'
Say "   PUBLISHED $versionName (build $versionCode)" 'Green'
Say '=================================================' 'Cyan'
Say ''
Say "  SHA256: $sha" 'White'
Say ''
Say '  On the phone, open the app and tap the check button.' 'Yellow'
Say ''
Say '  The app reads this manifest:' 'DarkGray'
Say "    http://192.168.3.11/version.json      (same LAN / Tailscale)" 'DarkGray'
Say ''
Say "  Notes: $(if($Notes){$Notes}else{'(none)'})" 'DarkGray'
Say ''
