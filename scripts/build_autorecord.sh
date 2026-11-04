#!/usr/bin/env bash
# AutoRecord 本机验证（D09 单命令）：:record 单元测试 + assembleDebug。
# 只服务本机验证，不进入 APK；不执行 Lint、不安装、不连接车辆。
#
# 受限环境说明：本脚本把 Gradle 用户目录、调试签名目录与 Kotlin 编译都限制在仓库内
# （.work/），并用 in-process 策略绕开无法写 ~/Library 的 Kotlin daemon。这是本机验证配置，
# 不是产品构建设置；在普通终端里同样可用。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
CACHE=${GALAXY_VERIFY_CACHE:-$ROOT/.work}
export ANDROID_HOME=${GALAXY_ANDROID_SDK:-$CACHE/android-sdk}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_PREFS_ROOT=${ANDROID_PREFS_ROOT:-$CACHE/android-prefs}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$CACHE/gradle-home}
export GALAXY_VERIFY_CACHE="$CACHE"
export GALAXY_ROBOLECTRIC_STRICT_OFFLINE=1
mkdir -p "$CACHE/logs" "$GRADLE_USER_HOME" "$ANDROID_PREFS_ROOT/.android"

INIT="$ROOT/scripts/verify/lib/robolectric-offline.init.gradle"
[ -f "$INIT" ] || { echo "缺少 Robolectric init 脚本: $INIT" >&2; exit 2; }
# 沿用已安装 AutoService/CarPlay 的工程调试签名；缺少时停止，不能让 AGP 自动生成另一套。
[ -s "$ANDROID_PREFS_ROOT/.android/debug.keystore" ] || { echo '缺少原工程调试签名，请恢复既有本机签名配置' >&2; exit 2; }

LOG="$CACHE/logs/build-autorecord-$(date +%Y%m%d-%H%M%S).log"
ARGS=(--offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false
      -Pkotlin.compiler.execution.strategy=in-process -I "$INIT")

cd "$ROOT"
echo "==> :record:testDebugUnitTest + :record:assembleDebug（日志: ${LOG}）"
"$ROOT/gradlew" "${ARGS[@]}" :record:testDebugUnitTest :record:assembleDebug 2>&1 | tee "$LOG"
echo
echo "APK: $ROOT/galaxy/record/build/outputs/apk/debug/record-debug.apk"
echo "仅 assembleDebug + 单测；未执行 lint、未安装、未连接车辆。"
