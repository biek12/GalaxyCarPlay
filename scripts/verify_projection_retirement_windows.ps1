# GALAXY-WINDOWS-VALIDATION-E0-R1-20261112
# 薄入口：只解析参数、准备环境和宿主工具，然后把退出码原样交还原 Shell 门槛。
[CmdletBinding()]
param(
    [string]$CacheRoot = "",
    [string]$ReportDir = "",
    [string]$KtlintJar = "",
    [string]$Aapt2Path = "",
    [string]$BashPath = "",
    [ValidateSet("", "baseline", "C1", "C2", "C3", "C4", "A1-baseline", "A2-baseline")]
    [string]$Checkpoint = ""
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

function Convert-ToToolPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    return ([System.IO.Path]::GetFullPath($Path) -replace '\\', '/')
}

if (-not $CacheRoot) { $CacheRoot = Join-Path $Root ".work" }
if (-not $ReportDir) { $ReportDir = Join-Path $Root ".work\r0-validation" }
$CacheRoot = Convert-ToToolPath $CacheRoot
$ReportDir = Convert-ToToolPath $ReportDir
if (-not $KtlintJar) {
    $KtlintJar = Join-Path ($CacheRoot -replace '/', '\') "tools\ktlint-1.4.1\ktlint-cli-1.4.1-all.jar"
}
$KtlintJar = Convert-ToToolPath $KtlintJar

if (-not $BashPath) { $BashPath = $env:GALAXY_BASH }
if (-not $BashPath) { $BashPath = "C:\Program Files\Git\bin\bash.exe" }
if (-not (Test-Path -LiteralPath $BashPath)) {
    Write-Error "缺少 Git Bash: $BashPath"
    exit 2
}

$gitRoot = "C:\Program Files\Git"
if ($env:GALAXY_GIT_ROOT) { $gitRoot = $env:GALAXY_GIT_ROOT }
$shellTool = Join-Path $gitRoot "bin\sh.exe"
$cksumTool = Join-Path $gitRoot "usr\bin\cksum.exe"
$usrBin = Join-Path $gitRoot "usr\bin"
foreach ($tool in @(
    $shellTool,
    $cksumTool,
    (Join-Path $usrBin "yes.exe"),
    (Join-Path $usrBin "head.exe"),
    (Join-Path $usrBin "sleep.exe")
)) {
    if (-not (Test-Path -LiteralPath $tool)) {
        Write-Error "缺少宿主工具: $tool"
        exit 2
    }
}

$env:GALAXY_REFACTOR_CACHE = $CacheRoot
$env:GALAXY_VERIFY_CACHE = $CacheRoot
$env:GALAXY_KTLINT_JAR = $KtlintJar
$env:GALAXY_R0_REPORT_DIR = $ReportDir
$env:GALAXY_A4_CHECKPOINT = $Checkpoint
$env:GALAXY_TEST_SHELL = Convert-ToToolPath $shellTool
$env:GALAXY_TEST_CKSUM = Convert-ToToolPath $cksumTool
$env:GALAXY_TEST_PATH_PREFIX = Convert-ToToolPath $usrBin
$env:PYTHONIOENCODING = "utf-8"
$env:PYTHONUTF8 = "1"
if (-not $env:LANG) { $env:LANG = "C.UTF-8" }
if ($Aapt2Path) { $env:GALAXY_AAPT2 = Convert-ToToolPath $Aapt2Path }
if (-not $env:GALAXY_PYTHON) {
    $python = (& py -3 -c "import sys; print(sys.executable)")
    if ($LASTEXITCODE -ne 0 -or -not $python) {
        Write-Error "缺少 Python 3"
        exit 2
    }
    $env:GALAXY_PYTHON = Convert-ToToolPath $python.Trim()
}

$script = Join-Path $Root "scripts\verify_projection_retirement.sh"
$start = New-Object System.Diagnostics.ProcessStartInfo
$start.FileName = $BashPath
$start.WorkingDirectory = $Root
$start.UseShellExecute = $false
$start.RedirectStandardOutput = $false
$start.RedirectStandardError = $false
if ($script -match '[\s"]') {
    $start.Arguments = '"' + ($script -replace '"', '\"') + '"'
} else {
    $start.Arguments = $script
}
$process = New-Object System.Diagnostics.Process
$process.StartInfo = $start
[void]$process.Start()
$process.WaitForExit()
exit $process.ExitCode
