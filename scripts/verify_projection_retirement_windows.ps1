# GALAXY-WINDOWS-VALIDATION-E0-20261112
# Windows 入口。Gradle / Python 只用参数数组调用，避免 PowerShell 把 -Pkotlin.incremental=false 拆开。
# 任务、用例和产物核对与 scripts/verify_projection_retirement.sh 及其子脚本相同。
[CmdletBinding()]
param(
    [string]$CacheRoot = "",
    [string]$ReportDir = "",
    [string]$KtlintJar = "",
    [string]$Aapt2Path = ""
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root
if (-not $CacheRoot) { $CacheRoot = Join-Path $Root ".work" }
if (-not $ReportDir) { $ReportDir = Join-Path $Root ".work\r0-validation" }
if (-not $KtlintJar) { $KtlintJar = Join-Path $CacheRoot "tools\ktlint-1.4.1\ktlint-cli-1.4.1-all.jar" }

function Resolve-Python {
    $candidate = & py -3 -c "import sys; print(sys.executable)"
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $candidate)) {
        throw "缺少 Python 3"
    }
    return $candidate.Trim()
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$File,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [string]$Log = ""
    )
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $File
    $start.WorkingDirectory = (Get-Location).Path
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.CreateNoWindow = $true
    $start.StandardOutputEncoding = [System.Text.Encoding]::Default
    $start.StandardErrorEncoding = [System.Text.Encoding]::Default
    $quoted = foreach ($item in $ArgumentList) {
        if ($item -match '[\s"]') { '"' + ($item.Replace('"', '\"')) + '"' } else { $item }
    }
    $argLine = ($quoted -join " ")
    # .bat 不能被 CreateProcess 直接执行。参数仍来自数组，cmd 不再按 PowerShell 属性拆点号。
    if ($File -like "*.bat" -or $File -like "*.cmd") {
        $start.FileName = $env:ComSpec
        $start.Arguments = "/d /c `"$File $argLine`""
    } else {
        $start.FileName = $File
        $start.Arguments = $argLine
    }
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    [void]$process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $text = $stdoutTask.Result + $stderrTask.Result
    if ($Log) {
        $utf8 = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::WriteAllText($Log, $text, $utf8)
    }
    if ($text) { Write-Host $text }
    if ($process.ExitCode -ne 0) {
        throw "$File 退出码 $($process.ExitCode)"
    }
}

function Invoke-PythonFile {
    param([string]$Code, [string[]]$Arguments, [string]$Name)
    $path = Join-Path $ReportDir $Name
    $utf8 = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($path, $Code, $utf8)
    Invoke-Native -File $script:Python -ArgumentList (@($path) + $Arguments)
}

$Python = Resolve-Python
$LogDir = Join-Path $ReportDir "logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$env:GALAXY_PYTHON = $Python
$env:GALAXY_REFACTOR_CACHE = $CacheRoot
$env:GALAXY_VERIFY_CACHE = $CacheRoot
$env:GALAXY_KTLINT_JAR = $KtlintJar
$env:GALAXY_R0_REPORT_DIR = $ReportDir
$env:ANDROID_HOME = Join-Path $CacheRoot "android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $CacheRoot "gradle-home"
$env:GALAXY_ROBOLECTRIC_STRICT_OFFLINE = "1"
$env:PYTHONIOENCODING = "utf-8"

if (-not $Aapt2Path) {
    $aaptLog = Join-Path $LogDir "aapt2.log"
    Invoke-Native -File $Python -ArgumentList @("scripts/verify/lib/local_aapt2.py") -Log $aaptLog
    $Aapt2Path = (Get-Content $aaptLog -Raw).Trim()
}
if (-not (Test-Path -LiteralPath $Aapt2Path)) { throw "缺少本机 AAPT2: $Aapt2Path" }
$env:GALAXY_AAPT2 = $Aapt2Path

$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
foreach ($relative in @(
    "galaxy/service/build/intermediates/built_in_kotlinc",
    "galaxy/cluster/build/intermediates/built_in_kotlinc",
    "galaxy/service/build/intermediates/merged_manifest",
    "galaxy/cluster/build/intermediates/merged_manifest",
    "galaxy/service/build/intermediates/merged_manifests",
    "galaxy/cluster/build/intermediates/merged_manifests",
    "galaxy/service/build/test-results",
    "galaxy/cluster/build/test-results"
)) {
    if (-not $relative.StartsWith("galaxy/") -or -not $relative.Contains("/build/")) {
        throw "拒绝隔离非本任务编译目录: $relative"
    }
    $path = Join-Path $Root ($relative -replace "/", "\")
    if (Test-Path -LiteralPath $path) {
        $name = Split-Path $path -Leaf
        Rename-Item -LiteralPath $path -NewName ("{0}.stale-{1}" -f $name, $stamp)
        Write-Host "isolated $relative"
    }
}

Invoke-Native -File $Python -ArgumentList @("tools/review/galaxy-r0-20261112/verify_format.py") -Log (Join-Path $LogDir "format.log")

$Gradle = Join-Path $Root "gradlew.bat"
$GradleArgs = @(
    "--offline", "--no-daemon", "--no-configuration-cache", "--console=plain",
    "-Pkotlin.incremental=false",
    "-Pandroid.builder.sdkDownload=false",
    "-Pandroid.aapt2FromMavenOverride=$Aapt2Path",
    "-I", "scripts/verify/lib/maven-mirror.init.gradle",
    "-I", "scripts/verify/lib/robolectric-offline.init.gradle",
    "-x", ":service:buildHab",
    "-x", ":service:prepareQnxReceiver",
    "-x", ":service:prepareClusterHook"
)

Invoke-Native -File $Gradle -ArgumentList ($GradleArgs + @(
    ":auto-ui:compileDebugKotlin", ":auto-client:compileDebugKotlin",
    ":service:compileDebugKotlin", ":service:processDebugResources",
    ":cluster:compileDebugKotlin", ":cluster:processDebugResources",
    ":common:compileDebugKotlin", ":mobile:compileDebugKotlin", ":record:compileDebugKotlin"
)) -Log (Join-Path $LogDir "u1-compile.log")

$U1Report = Join-Path $Root ".work\refactor-u1-validation"
New-Item -ItemType Directory -Force -Path $U1Report | Out-Null
Invoke-Native -File $Gradle -ArgumentList ($GradleArgs + @(
    ":auto-ui:testDebugUnitTest",
    "--tests", "com.galaxy.auto.ui.GalaxyControlsTest",
    "--tests", "com.galaxy.auto.ui.GalaxyXmlComponentsTest",
    ":service:testDebugUnitTest",
    "--tests", "com.ecarx.autoservice.remote.RemoteConnectionStatusTest",
    "--tests", "com.ecarx.autoservice.ui.ServiceHomeBehaviorTest",
    "--tests", "com.ecarx.autoservice.ui.DiagnosticPageBehaviorTest",
    ":cluster:testDebugUnitTest",
    "--tests", "com.galaxy.autocluster.ClusterAppNavigationTest"
)) -Log (Join-Path $U1Report "tests.log")

$U1Check = @'
import hashlib, json, subprocess, sys
from pathlib import Path
import xml.etree.ElementTree as ET
report_dir = Path(sys.argv[1])
expected = {
    "ui": ["com.galaxy.auto.ui.GalaxyControlsTest", "com.galaxy.auto.ui.GalaxyXmlComponentsTest"],
    "service": [
        "com.ecarx.autoservice.remote.RemoteConnectionStatusTest",
        "com.ecarx.autoservice.ui.ServiceHomeBehaviorTest",
        "com.ecarx.autoservice.ui.DiagnosticPageBehaviorTest",
    ],
    "cluster": ["com.galaxy.autocluster.ClusterAppNavigationTest"],
}
results = []
for module, suites in expected.items():
    for suite in suites:
        path = Path(f"galaxy/{module}/build/test-results/testDebugUnitTest/TEST-{suite}.xml")
        node = ET.parse(path).getroot()
        tests = int(node.get("tests", "0"))
        failed = int(node.get("failures", "0")) + int(node.get("errors", "0"))
        skipped = int(node.get("skipped", "0"))
        if tests <= 0 or failed or skipped:
            raise SystemExit(f"用例未达标: {suite}: tests={tests}, failed={failed}, skipped={skipped}")
        results.append({"suite": suite, "tests": tests, "report": path.as_posix(),
                        "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
sources = {}
for module in ["ui", "service", "cluster", "client", "contracts", "common", "mobile", "record", "lyrics", "shared"]:
    for path in sorted(Path("galaxy", module).rglob("*")):
        if path.is_file() and "build" not in path.parts and path.suffix in {".kt", ".kts", ".xml", ".aidl"}:
            sources[path.as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
record = {
    "baselineOnly": False,
    "head": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True, encoding="utf-8").strip(),
    "worktreeStatus": subprocess.check_output(["git", "status", "--porcelain"], text=True, encoding="utf-8"),
    "nativeAssetPreparationExcluded": True,
    "apkBuilt": False,
    "vehicleValidated": False,
    "suites": results,
    "sourceSha256": sources,
}
(report_dir / "result.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
print(f"U1: 编译成功，{len(results)} suites / {sum(item['tests'] for item in results)} cases通过，0失败/0跳过；未打包、未上车。")
'@
Invoke-PythonFile -Code $U1Check -Arguments @($U1Report) -Name "u1-result-check.py"

$C1Report = Join-Path $Root ".work\refactor-c1-validation"
New-Item -ItemType Directory -Force -Path $C1Report | Out-Null
Invoke-Native -File $Gradle -ArgumentList ($GradleArgs + @(":cluster:testDebugUnitTest")) -Log (Join-Path $C1Report "cluster-tests.log")

$C1Check = @'
import hashlib, json, subprocess, sys
from pathlib import Path
import xml.etree.ElementTree as ET
report_dir = Path(sys.argv[1])
u1_result = Path(sys.argv[2])
results = []
for path in sorted(Path("galaxy/cluster/build/test-results/testDebugUnitTest").glob("TEST-*.xml")):
    node = ET.parse(path).getroot()
    counts = {key: int(node.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
    if counts["tests"] <= 0 or any(counts[key] for key in ("failures", "errors", "skipped")):
        raise SystemExit(f"Cluster 用例未达标: {path}: {counts}")
    results.append({"suite": node.get("name"), **counts, "report": path.as_posix(),
                    "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
names = {item["suite"] for item in results}
required = {"com.galaxy.autocluster.ClusterAppNavigationTest", "com.galaxy.autocluster.ClusterPageBehaviorTest"}
if not required.issubset(names):
    raise SystemExit(f"缺少必测类: {required - names}")
standard = json.loads(u1_result.read_text(encoding="utf-8"))
for path, digest in standard["sourceSha256"].items():
    if hashlib.sha256(Path(path).read_bytes()).hexdigest() != digest:
        raise SystemExit(f"验证中源码变化: {path}")
record = {
    "baselineOnly": False,
    "head": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True, encoding="utf-8").strip(),
    "worktreeStatus": subprocess.check_output(["git", "status", "--porcelain"], text=True, encoding="utf-8"),
    "u1Suites": standard["suites"],
    "clusterSuites": results,
    "sourceSha256": standard["sourceSha256"],
    "nativeAssetPreparationExcluded": True,
    "apkBuilt": False,
    "vehicleValidated": False,
}
(report_dir / "result.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
print(f"C1: U1完整门槛及Cluster {len(results)} suites / {sum(item['tests'] for item in results)} cases通过；未打包、未上车。")
'@
Invoke-PythonFile -Code $C1Check -Arguments @($C1Report, (Join-Path $U1Report "result.json")) -Name "c1-result-check.py"

Invoke-Native -File $Gradle -ArgumentList ($GradleArgs + @(
    ":service:testDebugUnitTest",
    "--tests", "com.ecarx.autoservice.platform.RootProjectionArchiveTest",
    "--tests", "com.ecarx.autoservice.platform.PlatformExecutionTest",
    "--tests", "com.ecarx.autoservice.ui.ServiceHomeBehaviorTest",
    "--tests", "com.ecarx.autoservice.ui.DiagnosticPageBehaviorTest",
    "--tests", "com.ecarx.autoservice.ClusterElementPipelineTest",
    "--tests", "com.ecarx.autoservice.ClusterHookLifecycleTest",
    "--tests", "com.ecarx.autoservice.ClientRegistryTest",
    "--tests", "com.ecarx.autoservice.platform.FoundationRecoveryTest",
    "--tests", "com.ecarx.autoservice.remote.RemoteConnectionStatusTest"
)) -Log (Join-Path $LogDir "service-tests.log")

Invoke-Native -File $Python -ArgumentList @("tools/review/galaxy-r0-20261112/verify_artifacts.py", "ready") -Log (Join-Path $LogDir "artifacts.log")

$junitDir = Join-Path $LogDir "junit"
New-Item -ItemType Directory -Force -Path $junitDir | Out-Null
foreach ($module in @("service", "cluster")) {
    $found = @(Get-ChildItem (Join-Path $Root "galaxy\$module\build\test-results\testDebugUnitTest\TEST-*.xml") -ErrorAction SilentlyContinue)
    if ($found.Count -eq 0) { throw "缺少 JUnit 报告: $module" }
    foreach ($xml in $found) {
        Copy-Item -LiteralPath $xml.FullName -Destination (Join-Path $junitDir "$module-$($xml.Name)")
    }
}
Copy-Item -LiteralPath (Join-Path $Root "archive\legacy-fullscreen-projection\20261112\manifest.json") -Destination (Join-Path $LogDir "archive-manifest.json")
Copy-Item -LiteralPath (Join-Path $ReportDir "format-result.json") -Destination (Join-Path $LogDir "format-result.json")
Write-Host "R0 门槛通过：格式、全下游编译、JUnit、Manifest/class、归档摘要均已核对；未打包、未上车。"
