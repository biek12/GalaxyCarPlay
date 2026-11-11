#!/usr/bin/env bash
# 公共 UI 与 Wi-Fi 入口的离线编译；不生成签名、不运行模拟器、不连接设备。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$ROOT"
export ANDROID_HOME=${GALAXY_ANDROID_SDK:-$ROOT/.work/android-sdk}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$ROOT/.work/gradle-home}
if [ -z "${GALAXY_AAPT2:-}" ]; then GALAXY_AAPT2=$(python3 scripts/verify/lib/local_aapt2.py); fi
export GALAXY_AAPT2
INIT_ARGS=()
RESOLVE_ARGS=(--offline)
for option in "$@"; do
  case "$option" in
    --mirror) INIT_ARGS=(-I scripts/verify/lib/maven-mirror.init.gradle) ;;
    --resolve) RESOLVE_ARGS=() ;;
    *) echo "用法: bash scripts/verify_shared_ui.sh [--mirror] [--resolve]" >&2; exit 2 ;;
  esac
done
AAPT_ARGS=()
if [ -n "${GALAXY_AAPT2:-}" ]; then
  test -x "$GALAXY_AAPT2"
  AAPT_ARGS=(-Pandroid.aapt2FromMavenOverride="$GALAXY_AAPT2")
fi
mkdir -p "$ROOT/.work/logs"
./gradlew ${INIT_ARGS[@]+"${INIT_ARGS[@]}"} ${AAPT_ARGS[@]+"${AAPT_ARGS[@]}"} ${RESOLVE_ARGS[@]+"${RESOLVE_ARGS[@]}"} --no-configuration-cache --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
  :auto-ui:compileDebugKotlin :common:compileDebugKotlin :service:compileDebugKotlin \
  :service:processDebugResources :mobile:processDebugResources :cluster:compileDebugKotlin :cluster:processDebugResources \
  2>&1 | tee "$ROOT/.work/logs/verify-shared-ui.log"
