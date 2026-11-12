#!/usr/bin/env bash
# C1 普通页面门槛：复用下游编译流程，再执行真实 Cluster 全部单元测试；无 APK/设备动作。
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
    *) echo '用法: bash scripts/verify_cluster_pages.sh [--baseline]' >&2; exit 2 ;;
esac
test "$#" -le 1 || { echo '参数过多' >&2; exit 2; }

# U1 已通过；即使 C1 基线模式也必须运行完整 U1 门槛，不退回旧的 U1 baseline 子集。
bash scripts/verify_android_refactor.sh
CACHE_ROOT=${GALAXY_REFACTOR_CACHE:-$ROOT/.work}
export ANDROID_HOME=${GALAXY_ANDROID_SDK:-$CACHE_ROOT/android-sdk}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$CACHE_ROOT/gradle-home}
export GALAXY_VERIFY_CACHE="$CACHE_ROOT"
export GALAXY_ROBOLECTRIC_STRICT_OFFLINE=1
AAPT2_PATH=${GALAXY_AAPT2:-$(GALAXY_ANDROID_SDK="$ANDROID_HOME" "$PY" scripts/verify/lib/local_aapt2.py)}
AAPT2_PATH=$(native_path "$AAPT2_PATH")
REPORT_DIR="$ROOT/.work/refactor-c1-validation"
mkdir -p "$REPORT_DIR"
./gradlew --offline --no-daemon --no-configuration-cache --console=plain \
    -Pkotlin.incremental=false -Pandroid.builder.sdkDownload=false \
    "-Pandroid.aapt2FromMavenOverride=$AAPT2_PATH" \
    -I scripts/verify/lib/maven-mirror.init.gradle \
    -I scripts/verify/lib/robolectric-offline.init.gradle \
    -x :service:buildHab -x :service:prepareQnxReceiver -x :service:prepareClusterHook \
    :cluster:testDebugUnitTest 2>&1 | tee "$REPORT_DIR/cluster-tests.log"

U1_RESULT=$(native_path "$ROOT/.work/refactor-u1-validation/result.json")
"$PY" - "$BASELINE" "$(native_path "$REPORT_DIR")" "$U1_RESULT" <<'PY'
"""读取实际 JUnit 及最终源码；新批次必测不得由空报告或跳过代替。"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

baseline = sys.argv[1] == "1"
report_dir = Path(sys.argv[2])
u1_result = Path(sys.argv[3])
results = []
for path in sorted(Path("galaxy/cluster/build/test-results/testDebugUnitTest").glob("TEST-*.xml")):
    node = ET.parse(path).getroot()
    counts = {key: int(node.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
    if counts["tests"] <= 0 or any(counts[key] for key in ("failures", "errors", "skipped")):
        raise SystemExit(f"Cluster 用例未达标: {path}: {counts}")
    results.append({"suite": node.get("name"), **counts, "report": path.as_posix(),
                    "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
names = {item["suite"] for item in results}
required = {"com.galaxy.autocluster.ClusterAppNavigationTest"}
if not baseline:
    required.add("com.galaxy.autocluster.ClusterPageBehaviorTest")
if not required.issubset(names):
    raise SystemExit(f"缺少必测类: {required - names}")
standard = json.loads(u1_result.read_text(encoding="utf-8"))
for path, digest in standard["sourceSha256"].items():
    if hashlib.sha256(Path(path).read_bytes()).hexdigest() != digest:
        raise SystemExit(f"验证中源码变化: {path}")
record = {
    "baselineOnly": baseline,
    "head": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
    "worktreeStatus": subprocess.check_output(["git", "status", "--porcelain"], text=True),
    "u1Suites": standard["suites"],
    "clusterSuites": results,
    "sourceSha256": standard["sourceSha256"],
    "nativeAssetPreparationExcluded": True,
    "apkBuilt": False,
    "vehicleValidated": False,
}
(report_dir / "result.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
print(f"C1{' BASELINE' if baseline else ''}: U1完整门槛及Cluster {len(results)} suites / "
      f"{sum(item['tests'] for item in results)} cases通过；未打包、未上车。")
PY
