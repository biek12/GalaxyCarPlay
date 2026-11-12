#!/usr/bin/env bash
# GALAXY-ANDROID-R0-20261112 单命令门槛：整屏投屏归档与 AutoService 停注入。
# 只离线编译/资源/JUnit/格式/产物核对；不打包 APK、不连车、不启模拟器、不远程部署。
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$ROOT"
TASK_DIR=$(cd -- "$ROOT/.." && pwd -P)
LOG_DIR="$TASK_DIR/logs"
mkdir -p "$LOG_DIR" "$ROOT/.work/r0-validation"

# 固定离线缓存：GALAXY_VERIFY_CACHE 必须与 GALAXY_REFACTOR_CACHE 同时指向 .work，
# 否则 robolectric-offline init script 会去 worktree/.work 找依赖，与源码无关地失败。
export GALAXY_REFACTOR_CACHE=/Users/anjon/Documents/GalaxyPlay/.work
export GALAXY_VERIFY_CACHE=/Users/anjon/Documents/GalaxyPlay/.work
export ANDROID_HOME="$GALAXY_REFACTOR_CACHE/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$GALAXY_REFACTOR_CACHE/gradle-home"
export GALAXY_ROBOLECTRIC_STRICT_OFFLINE=1
AAPT2_PATH=${GALAXY_AAPT2:-$(GALAXY_ANDROID_SDK="$ANDROID_HOME" python3 scripts/verify/lib/local_aapt2.py)}
test -x "$AAPT2_PATH" || { echo '缺少本机 AAPT2' >&2; exit 2; }

ARGS=(--offline --no-daemon --no-configuration-cache --console=plain
  -Pkotlin.incremental=false -Pandroid.builder.sdkDownload=false
  "-Pandroid.aapt2FromMavenOverride=$AAPT2_PATH"
  -I scripts/verify/lib/maven-mirror.init.gradle
  -I scripts/verify/lib/robolectric-offline.init.gradle
  -x :service:buildHab -x :service:prepareQnxReceiver -x :service:prepareClusterHook)

# 只清本任务自身模块的 Kotlin class / merged manifest / test-results 输出目录，
# 保证产物与 Manifest 都是本轮编译结果，不拿旧 build 的残留 class 冒充本轮产物；
# 不清共享缓存、不动其他工作树。用「重命名到唯一后缀」隔离旧产物（移动而非删除），
# 这些目录都在被 .gitignore 忽略的 build/ 下，不进入 Git 提交。
python3 - <<'PY'
from pathlib import Path
import time

root = Path.cwd()
stamp = str(time.time_ns())
targets = [
    "galaxy/service/build/intermediates/built_in_kotlinc",
    "galaxy/cluster/build/intermediates/built_in_kotlinc",
    "galaxy/service/build/intermediates/merged_manifest",
    "galaxy/cluster/build/intermediates/merged_manifest",
    "galaxy/service/build/intermediates/merged_manifests",
    "galaxy/cluster/build/intermediates/merged_manifests",
    "galaxy/service/build/test-results",
    "galaxy/cluster/build/test-results",
]
for rel in targets:
    path = root / rel
    if not path.is_dir():
        continue
    # 只接受本 worktree 内 galaxy/{service,cluster}/build 下的目录。
    if not rel.startswith("galaxy/") or "/build/" not in rel:
        raise SystemExit(f"拒绝隔离非本任务编译目录: {rel}")
    path.rename(path.with_name(f"{path.name}.stale-{stamp}"))
    print(f"isolated {rel}")
PY

# 1) 固定格式门槛：ktlint 1.4.1 二次幂等 + XML libxml 20913 语义/二次幂等 + 脚本语法。
python3 tools/review/galaxy-r0-20261112/verify_format.py 2>&1 | tee "$LOG_DIR/format.log"

# 2) 复用既有 verify_cluster_pages：全下游编译/资源、U1 门槛与当前全部 Cluster 测试。
bash scripts/verify_cluster_pages.sh 2>&1 | tee "$LOG_DIR/verify_cluster_pages.log"

# 3) 同一离线环境执行本批 service R0 用例及受影响的既有回归（精确 suite 清单）。
# 零匹配由 Gradle "No tests found" 直接失败；具体报告再由 Python 逐项核对。
./gradlew "${ARGS[@]}" :service:testDebugUnitTest \
  --tests com.ecarx.autoservice.platform.RootProjectionArchiveTest \
  --tests com.ecarx.autoservice.platform.PlatformExecutionTest \
  --tests com.ecarx.autoservice.ui.ServiceHomeBehaviorTest \
  --tests com.ecarx.autoservice.ui.DiagnosticPageBehaviorTest \
  --tests com.ecarx.autoservice.ClusterElementPipelineTest \
  --tests com.ecarx.autoservice.ClusterHookLifecycleTest \
  --tests com.ecarx.autoservice.ClientRegistryTest \
  --tests com.ecarx.autoservice.platform.FoundationRecoveryTest \
  --tests com.ecarx.autoservice.remote.RemoteConnectionStatusTest \
  2>&1 | tee "$LOG_DIR/service-tests.log"

# 4) 真实产物/JUnit/归档摘要/最终源码 SHA256 核对，写父目录 VALIDATION.json。
python3 tools/review/galaxy-r0-20261112/verify_artifacts.py ready 2>&1 | tee "$LOG_DIR/artifacts.log"

# 5) 保留实际 JUnit XML 与归档清单副本作为交付证据。
mkdir -p "$LOG_DIR/junit"
for module in service cluster; do
  for xml in galaxy/$module/build/test-results/testDebugUnitTest/TEST-*.xml; do
    test -f "$xml" || { echo "缺少 JUnit 报告: $module" >&2; exit 2; }
    cp "$xml" "$LOG_DIR/junit/$module-$(basename "$xml")"
  done
done
cp archive/legacy-fullscreen-projection/20261112/manifest.json "$LOG_DIR/archive-manifest.json"
cp .work/r0-validation/format-result.json "$LOG_DIR/format-result.json"

echo "R0 门槛通过：格式、全下游编译、JUnit、Manifest/class、归档摘要均已核对；未打包、未上车。"
