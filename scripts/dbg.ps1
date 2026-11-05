<#
.SYNOPSIS
sp_ImeHook 调试辅助脚本：重启目标进程 / 构建 / 安装 / 看日志。

.DESCRIPTION
目标：豆包输入法（com.bytedance.android.doubaoime）。
设备：优先用 `$env:ANDROID_HOME\platform-tools\adb.exe`；否则从 local.properties 的 sdk.dir 拼；最后 fallback 到 PATH。

.USAGE
  .\scripts\dbg.ps1 restart       # force-stop 豆包输入法（系统收到下一次输入请求会自动拉起）
  .\scripts\dbg.ps1 build         # assembleDebug
  .\scripts\dbg.ps1 install       # build + installDebug（native 改了必须重装机）
  .\scripts\dbg.ps1 install-only  # 跳过 build，直接装现有 apk（只改了 Java/UI 时用）
  .\scripts\dbg.ps1 log           # 实时 logcat 过滤 ImeHook / ActivityManager
  .\scripts\dbg.ps1 clear         # 清模块自己的 SharedPreferences（调试用）
  .\scripts\dbg.ps1 all           # build + install + restart + 开 logcat（前台阻塞，ctrl+c 退出）

.EXAMPLE
  # 改完 native 后一条命令走完整链路
  .\scripts\dbg.ps1 all
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet("restart", "build", "install", "install-only", "log", "clear", "all")]
    [string]$Action = "restart"
)

$ErrorActionPreference = "Stop"

# ---------- 路径解析 ----------
$RepoRoot = Split-Path -Parent $PSScriptRoot
$LocalProps = Join-Path $RepoRoot "local.properties"

$SdkDir = $null
if (Test-Path $LocalProps) {
    foreach ($line in (Get-Content $LocalProps)) {
        if ($line -match '^\s*sdk\.dir=(.+)$') { $SdkDir = $Matches[1].Trim(); break }
    }
}
if (-not $SdkDir -and $env:ANDROID_HOME) { $SdkDir = $env:ANDROID_HOME }

$Adb = $null
if ($SdkDir) {
    $p = Join-Path $SdkDir "platform-tools\adb.exe"
    if (Test-Path $p) { $Adb = $p }
}
if (-not $Adb) {
    $Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
}
if (-not $Adb) { throw "adb 未找到：ANDROID_HOME 未设且 local.properties 无 sdk.dir，PATH 里也没有 adb" }

$Gradlew = Join-Path $RepoRoot "gradlew.bat"
$Apk = Join-Path $RepoRoot "app\build\outputs\apk\debug\app-debug.apk"

$TargetPkg = "com.bytedance.android.doubaoime"
$ModulePkg = "lpt.imehook"

function Invoke-Adb([string[]]$Args) {
    Write-Host "== adb $($Args -join ' ')" -ForegroundColor DarkCyan
    & $Adb @Args
    if ($LASTEXITCODE -ne 0) { throw "adb exit $LASTEXITCODE" }
}

function RestartTarget {
    # 输入法进程被 force-stop 后，系统不会自动恢复输入能力，但会在下次输入请求时拉起进程。
    # 这里只 force-stop，用户触发任何输入即可，避免误用 am start 启错 Activity。
    Write-Host "== force-stop $TargetPkg" -ForegroundColor DarkCyan
    & $Adb shell "am force-stop $TargetPkg"
    Write-Host "Done. 下次输入请求会自动拉起输入法进程。" -ForegroundColor Green
}

function Build-Apk {
    Push-Location $RepoRoot
    try {
        & $Gradlew assembleDebug --console=plain 2>&1 | ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) { throw "gradle assembleDebug failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
    if (-not (Test-Path $Apk)) { throw "$Apk 不存在，build 产物缺失" }
}

function Install-Apk {
    param([string]$Path)
    Write-Host "== install $Path -> $ModulePkg" -ForegroundColor DarkCyan
    & $Adb install -r $Path
    if ($LASTEXITCODE -ne 0) {
        # 某些机型需要 -t（允许 testOnly）
        & $Adb install -r -t $Path
        if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
    }
}

function Show-Log {
    Write-Host "== logcat (Ctrl+C 退出)" -ForegroundColor DarkCyan
    & $Adb logcat -v time --pid=$(& $Adb shell pidof -s $TargetPkg 2>$null) *:S ImeHook:I ActivityManager:I AndroidRuntime:E 2>$null
}

function Clear-ModulePrefs {
    Write-Host "== pm clear $ModulePkg" -ForegroundColor DarkCyan
    & $Adb shell "pm clear $ModulePkg"
}

# ---------- 分发 ----------
switch ($Action) {
    "restart"      { RestartTarget }
    "build"        { Build-Apk }
    "install"      { Build-Apk; Install-Apk -Path $Apk }
    "install-only" { Install-Apk -Path $Apk }
    "log"          { Show-Log }
    "clear"        { Clear-ModulePrefs }
    "all"          { Build-Apk; Install-Apk -Path $Apk; RestartTarget; Show-Log }
}
