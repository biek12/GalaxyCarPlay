#!/usr/bin/env bash
# GALAXY-AUTOSERVICE-COMPLETION-A4-20261113：累计门槛及任务内真实编译检查点。
# 只离线编译/资源/JUnit/格式/产物核对；不打包 APK、不连车、不启模拟器、不远程部署。
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$ROOT"

# Git Bash 的 /f/... 不能直接交给 Windows Python/Gradle。只转换绝对路径。
native_path() {
  local path="${1//\\//}"
  if command -v cygpath >/dev/null 2>&1; then
    case "$path" in
      /*|[A-Za-z]:*) cygpath -m "$path" ;;
      *) printf '%s\n' "$path" ;;
    esac
  else
    printf '%s\n' "$path"
  fi
}
# 把明确的宿主工具交给 Gradle Test。POSIX 未设置时保留 /bin/sh 和 PATH 上的 cksum。
prepare_host_tools() {
  export PYTHONIOENCODING=utf-8
  export PYTHONUTF8=1
  if [ -z "${LANG:-}" ]; then
    export LANG=C.UTF-8
  fi
  if [ -z "${GALAXY_TEST_SHELL:-}" ] || [ -z "${GALAXY_TEST_CKSUM:-}" ] || [ -z "${GALAXY_TEST_PATH_PREFIX:-}" ]; then
    if command -v cygpath >/dev/null 2>&1; then
      local git_sh="/c/Program Files/Git/bin/sh.exe"
      if [ -z "${GALAXY_TEST_SHELL:-}" ]; then
        if [ -f "$git_sh" ]; then
          export GALAXY_TEST_SHELL="$(cygpath -m "$git_sh")"
        elif [ -x /bin/sh ]; then
          export GALAXY_TEST_SHELL="$(cygpath -m /bin/sh)"
        else
          echo '缺少 Git Bash 的 sh.exe，请设置 GALAXY_TEST_SHELL' >&2
          exit 2
        fi
      fi
      if [ ! -x /usr/bin/cksum ] || [ ! -d /usr/bin ]; then
        echo '缺少 Git Bash 的 cksum 或 usr/bin，请设置 GALAXY_TEST_CKSUM 和 GALAXY_TEST_PATH_PREFIX' >&2
        exit 2
      fi
      export GALAXY_TEST_CKSUM="${GALAXY_TEST_CKSUM:-$(cygpath -m /usr/bin/cksum)}"
      export GALAXY_TEST_PATH_PREFIX="${GALAXY_TEST_PATH_PREFIX:-$(cygpath -m /usr/bin)}"
    elif [ -z "${GALAXY_TEST_SHELL:-}" ]; then
      if [ ! -x /bin/sh ]; then
        echo '缺少 /bin/sh，请设置 GALAXY_TEST_SHELL' >&2
        exit 2
      fi
      export GALAXY_TEST_SHELL=/bin/sh
    fi
  fi
  if [ -n "${GALAXY_TEST_SHELL:-}" ] && [ ! -x "$GALAXY_TEST_SHELL" ] && [ ! -f "$GALAXY_TEST_SHELL" ]; then
    echo "宿主 shell 不存在: $GALAXY_TEST_SHELL" >&2
    exit 2
  fi
  if [ -n "${GALAXY_TEST_CKSUM:-}" ] && [ ! -x "$GALAXY_TEST_CKSUM" ] && [ ! -f "$GALAXY_TEST_CKSUM" ]; then
    echo "宿主 cksum 不存在: $GALAXY_TEST_CKSUM" >&2
    exit 2
  fi
  if [ -n "${GALAXY_TEST_PATH_PREFIX:-}" ]; then
    case ":$PATH:" in
      *":$GALAXY_TEST_PATH_PREFIX:"*) ;;
      *) export PATH="$GALAXY_TEST_PATH_PREFIX:$PATH" ;;
    esac
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
PY=$(native_path "$(resolve_python)")
export GALAXY_PYTHON="$PY"
prepare_host_tools

# 显式环境变量优先。未设置时缓存和报告都落在当前仓库 .work，不写到仓库父目录。
# 交给 native Python/Java 前转成 Windows 路径，不把 /f/... 直接送进去。
export GALAXY_REFACTOR_CACHE="$(native_path "${GALAXY_REFACTOR_CACHE:-$ROOT/.work}")"
export GALAXY_VERIFY_CACHE="$(native_path "${GALAXY_VERIFY_CACHE:-$GALAXY_REFACTOR_CACHE}")"
export GALAXY_KTLINT_JAR="$(native_path "${GALAXY_KTLINT_JAR:-$GALAXY_REFACTOR_CACHE/tools/ktlint-1.4.1/ktlint-cli-1.4.1-all.jar}")"
export GALAXY_R0_REPORT_DIR="$(native_path "${GALAXY_R0_REPORT_DIR:-$ROOT/.work/r0-validation}")"
LOG_DIR="$GALAXY_R0_REPORT_DIR/logs"
mkdir -p "$LOG_DIR" "$GALAXY_R0_REPORT_DIR"

# GALAXY_VERIFY_CACHE 必须与 GALAXY_REFACTOR_CACHE 同时指向同一缓存根，
# 否则 robolectric-offline init script 会去另一处找依赖，与源码无关地失败。
export ANDROID_HOME="$GALAXY_REFACTOR_CACHE/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$GALAXY_REFACTOR_CACHE/gradle-home"
export GALAXY_ROBOLECTRIC_STRICT_OFFLINE=1
AAPT2_PATH=${GALAXY_AAPT2:-$(GALAXY_ANDROID_SDK="$ANDROID_HOME" "$PY" scripts/verify/lib/local_aapt2.py)}
AAPT2_PATH=$(native_path "$AAPT2_PATH")
export GALAXY_AAPT2="$AAPT2_PATH"
test -x "$AAPT2_PATH" || { echo '缺少本机 AAPT2' >&2; exit 2; }

ARGS=(--offline --no-daemon --no-configuration-cache --console=plain
  -Pkotlin.incremental=false -Pandroid.builder.sdkDownload=false
  "-Pandroid.aapt2FromMavenOverride=$AAPT2_PATH"
  -I scripts/verify/lib/maven-mirror.init.gradle
  -I scripts/verify/lib/robolectric-offline.init.gradle
  -I scripts/verify/lib/kadb-transform.init.gradle
  -x :service:buildHab -x :service:prepareQnxReceiver -x :service:prepareClusterHook)

if [ "${GALAXY_A4_CHECKPOINT:-}" = "A2-baseline" ]; then
  ./gradlew "${ARGS[@]}" :auto-client:testDebugUnitTest \
    2>&1 | tee "$LOG_DIR/client-baseline-build.log"
  "$PY" tools/review/galaxy-client-a2-20261113/verify_client.py baseline \
    2>&1 | tee "$LOG_DIR/client-baseline-result.log"
  exit 0
fi
if [ "${GALAXY_A4_CHECKPOINT:-}" = "A1-baseline" ]; then
  ./gradlew "${ARGS[@]}" :auto-client:testDebugUnitTest :service:processDebugManifest :record:processDebugManifest \
    2>&1 | tee "$LOG_DIR/account-baseline-build.log"
  "$PY" tools/review/galaxy-account-a1-20261113/verify_account.py baseline \
    2>&1 | tee "$LOG_DIR/account-baseline-result.log"
  exit 0
fi
if [ -n "${GALAXY_A4_CHECKPOINT:-}" ]; then
  "$PY" tools/review/galaxy-autoservice-a4-20261113/checkpoint.py before "$GALAXY_A4_CHECKPOINT" \
    2>&1 | tee "$LOG_DIR/checkpoint-format.log"
  ./gradlew "${ARGS[@]}" :service:testDebugUnitTest :service:compileDebugAndroidTestKotlin :service:recordGalaxyKadbTransformInputs \
    2>&1 | tee "$LOG_DIR/checkpoint-build.log"
  "$PY" tools/review/galaxy-autoservice-a4-20261113/checkpoint.py after "$GALAXY_A4_CHECKPOINT" \
    2>&1 | tee "$LOG_DIR/checkpoint-result.log"
  exit 0
fi

"$PY" tools/review/galaxy-client-a2-20261113/verify_client.py before
export GALAXY_ACCOUNT_SCREENSHOT_DIR="$GALAXY_R0_REPORT_DIR/screenshots"

# 只清本任务自身模块的 Kotlin class / merged manifest / test-results 输出目录，
# 保证产物与 Manifest 都是本轮编译结果，不拿旧 build 的残留 class 冒充本轮产物；
# 不清共享缓存、不动其他工作树。用「重命名到唯一后缀」隔离旧产物（移动而非删除），
# 这些目录都在被 .gitignore 忽略的 build/ 下，不进入 Git 提交。
"$PY" - <<'PY'
from pathlib import Path
import os
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
    "galaxy/client/build/intermediates/built_in_kotlinc",
    "galaxy/client/build/intermediates/javac",
    "galaxy/client/build/test-results",
    "galaxy/record/build/intermediates/merged_manifests",
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
report = Path(os.environ["GALAXY_R0_REPORT_DIR"]).resolve()
screenshots = Path(os.environ["GALAXY_ACCOUNT_SCREENSHOT_DIR"]).resolve()
if screenshots.parent != report or screenshots.name != "screenshots":
    raise SystemExit("拒绝隔离本次报告截图目录之外的路径")
if screenshots.is_dir():
    screenshots.rename(screenshots.with_name(f"screenshots.stale-{stamp}"))
screenshots.mkdir(parents=True, exist_ok=True)
PY

# 1) 固定格式门槛：ktlint 1.4.1 二次幂等 + XML libxml 20913 语义/二次幂等 + 脚本语法。
"$PY" tools/review/galaxy-r0-20261112/verify_format.py 2>&1 | tee "$LOG_DIR/format.log"

# 2) 复用既有 verify_cluster_pages：全下游编译/资源、U1 门槛与当前全部 Cluster 测试。
bash scripts/verify_cluster_pages.sh 2>&1 | tee "$LOG_DIR/verify_cluster_pages.log"

# 3) 同一离线环境执行全部 Service 回归，并只编译 AndroidTest。
# Python 继续要求精确必测 suite 非空，且所有实际报告零失败/错误/跳过。
./gradlew "${ARGS[@]}" :service:testDebugUnitTest :service:compileDebugAndroidTestKotlin :service:recordGalaxyKadbTransformInputs \
  :auto-client:testDebugUnitTest :record:processDebugManifest \
  2>&1 | tee "$LOG_DIR/service-tests.log"
"$PY" tools/review/galaxy-platform-p1-20261113/verify_kadb_transform.py \
  2>&1 | tee "$LOG_DIR/kadb-transform.log"

# 4) 真实产物/JUnit/归档摘要/最终源码 SHA256 核对，写父目录 VALIDATION.json。
"$PY" tools/review/galaxy-r0-20261112/verify_artifacts.py ready 2>&1 | tee "$LOG_DIR/artifacts.log"

# 5) 保留实际 JUnit XML 与归档清单副本作为交付证据。
mkdir -p "$LOG_DIR/junit"
for module in service cluster client; do
  for xml in galaxy/$module/build/test-results/testDebugUnitTest/TEST-*.xml; do
    test -f "$xml" || { echo "缺少 JUnit 报告: $module" >&2; exit 2; }
    cp "$xml" "$LOG_DIR/junit/$module-$(basename "$xml")"
  done
done
cp archive/legacy-fullscreen-projection/20261112/manifest.json "$LOG_DIR/archive-manifest.json"
cp archive/theme-engine/20261112/manifest.json "$LOG_DIR/theme-archive-manifest.json"
cp "$GALAXY_R0_REPORT_DIR/format-result.json" "$LOG_DIR/format-result.json"

echo "累计重构门槛通过：格式、全下游/AndroidTest编译、全Service测试、KADB实际ASM、Manifest/class和归档摘要均已核对；未打包、未上车。"
