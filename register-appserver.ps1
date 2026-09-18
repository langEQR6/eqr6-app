# ============================================================
#  Register the EQR6 app update server as a scheduled task
#
#  Serves D:\Server\Share\app over HTTP on port 8080 so the phone can
#  fetch the APK and version.json. A firewall rule restricts access to
#  the Tailscale range only.
#
#  Requires: Administrator PowerShell
#
#  Usage:
#    powershell -ExecutionPolicy Bypass -File register-appserver.ps1
#    powershell -ExecutionPolicy Bypass -File register-appserver.ps1 -Remove
#
#  NOTE: ASCII-only source.
# ============================================================

[CmdletBinding()]
param(
    [switch]$Remove,
    [int]$Port = 8080
)

$ErrorActionPreference = 'Stop'

$TaskName  = 'EQR6-AppUpdateServer'
$Script    = 'D:\Server\stock\eqr6-app\serve-app.py'
$Python    = 'C:\Program Files\Python312\python.exe'
$RuleName  = 'EQR6-AppServer-LAN-and-Tailscale'
$TsRange   = '100.64.0.0/10'
$LogDir    = 'D:\Server\logs'

function Say { param([string]$T, [string]$C = 'Gray') Write-Host $T -ForegroundColor $C }
function Test-Admin {
    $p = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    return $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

Say ''
Say '=== EQR6 app update server ===' 'Cyan'
Say ''

if (-not (Test-Admin)) { Say 'FAILED: run this in an ADMIN PowerShell.' 'Red'; exit 1 }
if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }

# ------------------------------------------------------------
if ($Remove) {
    $t = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($t) { Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false; Say 'task removed' 'Green' }
    $r = Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue
    if ($r) { Remove-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue; Say 'firewall rule removed' 'Green' }
    # stop a running instance
    $pid2 = @(netstat -ano | Select-String ":$Port\s+.*LISTENING" |
              ForEach-Object { if ($_.Line -match 'LISTENING\s+(\d+)$') { [int]$Matches[1] } } | Select-Object -Unique)
    foreach ($p in $pid2) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue; Say "stopped PID $p" }
    exit 0
}

if (-not (Test-Path $Python)) { Say "FAILED: python not found: $Python" 'Red'; exit 1 }
if (-not (Test-Path $Script)) { Say "FAILED: server script not found: $Script" 'Red'; exit 1 }

# ------------------------------------------------------------
# firewall: port 8080 from the Tailscale range only
# ------------------------------------------------------------
$existing = Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue
if ($existing) {
    Remove-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue
    Say 'replacing existing firewall rule' 'Yellow'
}
New-NetFirewallRule -DisplayName $RuleName `
    -Description 'Allow the EQR6 app update server (TCP 8080) from the local LAN and the Tailscale range. NOT exposed to the internet (no port forwarding exists).' `
    -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port `
    -RemoteAddress 'LocalSubnet',$TsRange -Profile Any -Enabled True -ErrorAction Stop | Out-Null
Say "firewall: TCP $Port allowed from LocalSubnet + $TsRange" 'Green'
Say '  (LocalSubnet covers the LAN; the Tailscale entry covers remote access)' 'DarkGray'

# ------------------------------------------------------------
# scheduled task: run at startup, no login required
# ------------------------------------------------------------
$action = New-ScheduledTaskAction -Execute $Python `
    -Argument "`"$Script`"" `
    -WorkingDirectory 'D:\Server\stock\eqr6-app'

$trigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew

$old = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($old) { Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false; Say 'replacing existing task' 'Yellow' }

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings `
    -Description 'Serves the EQR6 Android app APK and update manifest on TCP 8080 (Tailscale only).' `
    -ErrorAction Stop | Out-Null
Say 'task registered (AtStartup, SYSTEM)' 'Green'

# ------------------------------------------------------------
# start it now and verify
# ------------------------------------------------------------
Say ''
Say '--- starting and verifying ---' 'Cyan'
Start-ScheduledTask -TaskName $TaskName

$up = $false
for ($i = 1; $i -le 15; $i++) {
    Start-Sleep -Seconds 1
    if (netstat -ano | Select-String ":$Port\s+.*LISTENING") { $up = $true; break }
}
if ($up) {
    Say "port $Port is listening (after ${i}s)" 'Green'
    # local fetch test
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/version.json" -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
        Say "manifest fetch OK (HTTP $($r.StatusCode), $($r.Content.Length) bytes)" 'Green'
        Say ''
        Say $r.Content
    } catch {
        Say "manifest fetch failed: $($_.Exception.Message)" 'Red'
    }
    try {
        $r2 = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/eqr6-app-release.apk" -Method Head -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
        Say "apk fetch OK (HTTP $($r2.StatusCode), $([math]::Round([int]$r2.Headers['Content-Length']/1MB,2)) MB)" 'Green'
    } catch {
        Say "apk fetch failed: $($_.Exception.Message)" 'Red'
    }
} else {
    Say "port $Port did not come up - check the task" 'Red'
    $info = Get-ScheduledTaskInfo -TaskName $TaskName
    Say "  LastTaskResult: $($info.LastTaskResult)"
}

Say ''
Say '=================================================' 'Cyan'
Say '   Phone URLs' 'White'
Say '=================================================' 'Cyan'
Say ''
Say '  Update manifest : http://100.77.117.76:8080/version.json' 'Yellow'
Say '  APK download    : http://100.77.117.76:8080/eqr6-app-release.apk' 'Yellow'
Say ''
Say '  On the same LAN you can also use 192.168.3.11:8080' 'DarkGray'
Say ''
