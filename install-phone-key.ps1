# ============================================================
#  Install the PHONE's SSH public key on EQR6
#
#  Why: the Android app needs to open an SSH tunnel so it can reach the DSH
#  web UI, which listens on 127.0.0.1 only. The app generates its own keypair
#  on the phone; this script installs the public half.
#
#  Usage (Administrator PowerShell on EQR6):
#    powershell -ExecutionPolicy Bypass -File install-phone-key.ps1 -PublicKey "ssh-ed25519 AAAA... eqr6-app-phone"
#
#  The ACL is re-applied afterwards, because Windows sshd refuses to use
#  administrators_authorized_keys if anyone beyond SYSTEM and Administrators
#  can read it.
#
#  NOTE: ASCII-only source.
# ============================================================

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PublicKey,

    # remove a key instead of adding it (match on the key blob)
    [switch]$Remove,

    # list the keys currently installed and exit
    [switch]$List
)

$ErrorActionPreference = 'Stop'

$KeysFile = 'C:\ProgramData\ssh\administrators_authorized_keys'
$UserName = '1'

function Say { param([string]$T, [string]$C = 'Gray') Write-Host $T -ForegroundColor $C }
function Test-Admin {
    $p = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    return $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

Say ''
Say '=== Install phone SSH key ===' 'Cyan'
Say ''

if (-not (Test-Admin)) { Say 'FAILED: run this in an ADMIN PowerShell.' 'Red'; exit 1 }

# ---- ensure the file exists with the right ACL ----
if (-not (Test-Path $KeysFile)) {
    New-Item -ItemType File -Path $KeysFile -Force | Out-Null
    Say "created $KeysFile"
}

$lines = @()
if ((Get-Item $KeysFile).Length -gt 0) {
    $lines = @(Get-Content -LiteralPath $KeysFile -ErrorAction SilentlyContinue |
               ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
}

# ---- list mode ----
if ($List) {
    Say "current keys ($($lines.Count)):" 'Cyan'
    foreach ($l in $lines) {
        $parts = $l -split '\s+'
        $comment = if ($parts.Count -ge 3) { $parts[2] } else { '(no comment)' }
        $blobHead = if ($parts.Count -ge 2) { $parts[1].Substring(0, [Math]::Min(24, $parts[1].Length)) } else { '?' }
        Say ("  {0} {1}...  [{2}]" -f $parts[0], $blobHead, $comment)
    }
    exit 0
}

# ---- normalise the supplied key ----
$key = $PublicKey.Trim() -replace '\s+', ' '
if ($key -notmatch '^ssh-(ed25519|rsa) ') {
    Say 'FAILED: that does not look like an OpenSSH public key line.' 'Red'
    Say 'Expected something like: ssh-ed25519 AAAA...comment' 'Yellow'
    exit 1
}
$keyParts = $key -split ' '
$keyType = $keyParts[0]
$keyBlob = $keyParts[1]
Say "key type : $keyType"
Say "key head : $($keyBlob.Substring(0, [Math]::Min(28, $keyBlob.Length)))..."

# comment is replaced so the file stays ASCII and identifiable
$entry = "$keyType $keyBlob eqr6-phone"

# ---- remove or add ----
$before = $lines.Count
$kept = @()
$matched = $false
foreach ($l in $lines) {
    $p = $l -split ' '
    if ($p.Count -ge 2 -and $p[1] -eq $keyBlob) {
        $matched = $true
    } else {
        $kept += $l
    }
}

if ($Remove) {
    if (-not $matched) { Say 'key not found - nothing to remove' 'Yellow'; exit 0 }
    $kept = $kept
    Say 'removed the phone key' 'Green'
} else {
    $kept += $entry
    if ($matched) { Say 'key already present - refreshed its entry' 'Yellow' }
    else { Say 'added the phone key' 'Green' }
}

# ---- write back: ASCII, CRLF, no BOM ----
if ($kept.Count -eq 0) {
    # an empty file is valid but sshd prefers it to exist
    [System.IO.File]::WriteAllText($KeysFile, "", (New-Object System.Text.ASCIIEncoding))
} else {
    $content = ($kept -join "`r`n") + "`r`n"
    [System.IO.File]::WriteAllText($KeysFile, $content, (New-Object System.Text.ASCIIEncoding))
}
Say "keys now: $($kept.Count) (was $before)" 'Cyan'

# ---- ASCII check ----
$bytes = [System.IO.File]::ReadAllBytes($KeysFile)
$high = @($bytes | Where-Object { $_ -gt 127 }).Count
if ($high -eq 0) { Say 'file is pure ASCII (sshd safe)' 'Green' }
else { Say "WARNING: $high non-ASCII bytes present" 'Yellow' }

# ---- ACL: sshd refuses the file if anyone else can read it ----
$icacls = "$env:SystemRoot\System32\icacls.exe"
$saved = $ErrorActionPreference
$ErrorActionPreference = 'SilentlyContinue'
& $icacls $KeysFile /inheritance:r | Out-Null
& $icacls $KeysFile /grant:r 'SYSTEM:F' | Out-Null
& $icacls $KeysFile /grant:r 'BUILTIN\Administrators:F' | Out-Null
$aclOut = (& $icacls $KeysFile 2>&1 | Out-String).Trim()
$ErrorActionPreference = $saved
Say ''
Say 'ACL now:' 'Cyan'
$aclOut -split "`n" | Where-Object { $_.Trim() } | ForEach-Object { Say "  $_" }
if ($aclOut -match [regex]::Escape("PTCG-1\$UserName")) {
    Say 'WARNING: an explicit entry for the user account is present; sshd may reject the file.' 'Yellow'
} else {
    Say 'ACL correct (SYSTEM + Administrators only)' 'Green'
}

# ---- restart sshd so it re-reads the file ----
try {
    Restart-Service sshd -Force -ErrorAction Stop
    Start-Sleep -Seconds 2
    Say "sshd restarted, status = $((Get-Service sshd).Status)" 'Green'
} catch {
    Say "could not restart sshd: $($_.Exception.Message)" 'Yellow'
}

Say ''
Say '=================================================' 'Cyan'
Say '  Done. Now open the app and tap the DSH button.' 'Green'
Say '=================================================' 'Cyan'
Say ''
