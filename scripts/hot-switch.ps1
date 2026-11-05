# scripts/hot-switch.ps1
# Hot-switch doubaoime scheme slot / inject custom mapping table via Frida.
# Module must be installed + LSPosed-enabled + hook active first.

[CmdletBinding()]
param(
    [Parameter(Position = 0, Mandatory = $true)]
    [ValidateSet("slot", "custom", "off", "status")]
    [string]$Mode,

    [Parameter(Position = 1)]
    [ValidateRange(1, 7)]
    [int]$Slot = 2,

    [Parameter()]
    [string]$Table = ""
)

$ErrorActionPreference = "Stop"

$fridaScriptPath = Join-Path $PSScriptRoot "hot_switch.js"
if (-not (Test-Path $fridaScriptPath)) { throw "missing: $fridaScriptPath" }

$nativeMode = switch ($Mode) {
    "off"    { 0 }
    "slot"   { 1 }
    "custom" { 2 }
    "status" { -1 }
}

$payload = @{
    mode  = $nativeMode
    slot  = $Slot
    table = $Table
} | ConvertTo-Json -Compress

Write-Host "== mode=$Mode native=$nativeMode slot=$Slot =="

$adb = "A:\WorkSpace\AllSDK\Android\platform-tools\adb.exe"
$pidRaw = (& $adb shell pidof -s com.bytedance.android.doubaoime 2>$null | Out-String).Trim()
$procPid = ($pidRaw -split "\s+")[0]

$fridaArgs = New-Object System.Collections.Generic.List[string]
$fridaArgs.Add("-U")
if ($procPid -match "^\d+$") {
    Write-Host "== attach pid=$procPid"
    $fridaArgs.Add("-p"); $fridaArgs.Add($procPid)
} else {
    Write-Host "== no pid, spawning LauncherActivity"
    $fridaArgs.Add("-f"); $fridaArgs.Add("com.bytedance.android.doubaoime/.activity.LauncherActivity")
}
$fridaArgs.Add("-l"); $fridaArgs.Add($fridaScriptPath)

# inject payload JSON as a global JS var via -e
$escaped = $payload -replace "'", "\\'"
$fridaArgs.Add("-e"); $fridaArgs.Add("FSWITCH_ARGS = '$escaped';")

$fridaPath = (where.exe frida 2>$null | Select-Object -First 1).Trim()
if (-not $fridaPath -or -not (Test-Path $fridaPath)) {
    throw "frida not found in PATH"
}

Write-Host "== frida $($fridaArgs -join ' ')" -ForegroundColor DarkGray

# frida keeps running after script exits; we kill it after a short wait
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $fridaPath
foreach ($a in $fridaArgs) { $psi.ArgumentList.Add($a) }
$psi.RedirectStandardOutput = $false
$psi.RedirectStandardError = $false
$psi.UseShellExecute = $false

$proc = [System.Diagnostics.Process]::Start($psi)
Start-Sleep -Seconds 6
if (-not $proc.HasExited) { $proc.Kill(); $proc.WaitForExit() }