#!/usr/bin/env bash
# U1 的真实 Android 源码/资源编译与标准用例门槛；不打包、不签名、不连接设备。
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$ROOT"
native_path() {
  local path="$1"
  if command -v cygpath >/dev/null 2>&1; then
    case "$path" in
      /*|[A-Za-z]:*) cygpath -m "$path" ;;
      *) printf '%s\n' "$path" ;;
    esac
  else
    printf '%s\n' "$path"
  fi
}
resolve_python() {
  if [ -n "${GALAXY_PYTHON:-}" ] && "${GALAXY_PYTHON}" -c 'import sys' >/dev/null 2>&1; then
    printf '%s\n' "$GALAXY_PYTHON"
    return
  fi
  local candidate
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info[0] == 3 else 1)' >/dev/null 2>&1; then
      printf '%s\n' "$candidate"
      return
    fi
  done
  echo '缺少 Python 3' >&2
  exit 2
}
PY=$(resolve_python)
export GALAXY_PYTHON="$PY"
BASELINE=0
case "${1:-}" in
  "") ;;
  --baseline) BASELINE=1 ;;
  *) echo '用法: bash scripts/verify_android_refactor.sh [--baseline]' >&2; exit 2 ;;
esac
test "$#" -le 1 || { echo '参数过多' >&2; exit 2; }

# 缓存允许复用原工作区；源码、资源、生成 binding 与编译输出仍来自当前隔离 worktree。
CACHE_ROOT=${GALAXY_REFACTOR_CACHE:-$ROOT/.work}
export ANDROID_HOME=${GALAXY_ANDROID_SDK:-$CACHE_ROOT/android-sdk}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$CACHE_ROOT/gradle-home}
export GALAXY_VERIFY_CACHE="$CACHE_ROOT"
export GALAXY_ROBOLECTRIC_STRICT_OFFLINE=1
AAPT2_PATH=${GALAXY_AAPT2:-$(GALAXY_ANDROID_SDK="$ANDROID_HOME" "$PY" scripts/verify/lib/local_aapt2.py)}
AAPT2_PATH=$(native_path "$AAPT2_PATH")
test -x "$AAPT2_PATH" || { echo '缺少本机 AAPT2' >&2; exit 2; }
REPORT_DIR="$ROOT/.work/refactor-u1-validation"
mkdir -p "$REPORT_DIR"

ARGS=(--offline --no-daemon --no-configuration-cache --console=plain
  -Pkotlin.incremental=false -Pandroid.builder.sdkDownload=false
  "-Pandroid.aapt2FromMavenOverride=$AAPT2_PATH"
  -I scripts/verify/lib/maven-mirror.init.gradle
  -I scripts/verify/lib/robolectric-offline.init.gradle
  -x :service:buildHab -x :service:prepareQnxReceiver -x :service:prepareClusterHook)

# 仅排除未修改的 Native/QNX 资产准备，不能排除编译、资源处理或 test。
./gradlew "${ARGS[@]}" \
  :auto-ui:compileDebugKotlin :auto-client:compileDebugKotlin \
  :service:compileDebugKotlin :service:processDebugResources \
  :cluster:compileDebugKotlin :cluster:processDebugResources \
  :common:compileDebugKotlin :mobile:compileDebugKotlin :record:compileDebugKotlin \
  2>&1 | tee "$REPORT_DIR/compile.log"

UI_TESTS=(--tests com.galaxy.auto.ui.GalaxyControlsTest)
SERVICE_TESTS=(--tests com.ecarx.autoservice.remote.RemoteConnectionStatusTest)
if [ "$BASELINE" = 0 ]; then
  UI_TESTS+=(--tests com.galaxy.auto.ui.GalaxyXmlComponentsTest)
  SERVICE_TESTS+=(--tests com.ecarx.autoservice.ui.ServiceHomeBehaviorTest
    --tests com.ecarx.autoservice.ui.DiagnosticPageBehaviorTest)
fi
./gradlew "${ARGS[@]}" \
  :auto-ui:testDebugUnitTest "${UI_TESTS[@]}" \
  :service:testDebugUnitTest "${SERVICE_TESTS[@]}" \
  :cluster:testDebugUnitTest --tests com.galaxy.autocluster.ClusterAppNavigationTest \
  2>&1 | tee "$REPORT_DIR/tests.log"

"$PY" - "$BASELINE" "$(native_path "$REPORT_DIR")" <<'PY'
"""绑定真实 JUnit 报告和最终源码；缺少测试、跳过或失败均拒绝交审。"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

baseline = sys.argv[1] == "1"
report_dir = Path(sys.argv[2])
expected = {
    "ui": ["com.galaxy.auto.ui.GalaxyControlsTest"],
    "service": ["com.ecarx.autoservice.remote.RemoteConnectionStatusTest"],
    "cluster": ["com.galaxy.autocluster.ClusterAppNavigationTest"],
}
if not baseline:
    expected["ui"].append("com.galaxy.auto.ui.GalaxyXmlComponentsTest")
    expected["service"] += [
        "com.ecarx.autoservice.ui.ServiceHomeBehaviorTest",
        "com.ecarx.autoservice.ui.DiagnosticPageBehaviorTest",
    ]
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
    "baselineOnly": baseline,
    "head": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
    "worktreeStatus": subprocess.check_output(["git", "status", "--porcelain"], text=True),
    "nativeAssetPreparationExcluded": True,
    "apkBuilt": False,
    "vehicleValidated": False,
    "suites": results,
    "sourceSha256": sources,
}
(report_dir / "result.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
print(f"{'BASELINE' if baseline else 'U1'}: 编译成功，{len(results)} suites / {sum(x['tests'] for x in results)} cases通过，0失败/0跳过；未打包、未上车。")
PY
