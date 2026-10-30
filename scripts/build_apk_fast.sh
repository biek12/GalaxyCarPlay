#!/usr/bin/env bash
# 快速本机产出APK；仅assembleDebug，不执行lint或单测，不安装。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
CACHE=${GALAXY_VERIFY_CACHE:-$ROOT/.work}
export ANDROID_HOME=${GALAXY_ANDROID_SDK:-$CACHE/android-sdk}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$CACHE/gradle-home}
mkdir -p "$CACHE/logs"
cd "$ROOT"
"$ROOT/gradlew" --offline --no-daemon --console=plain \
    -Pandroid.builder.sdkDownload=false :mobile:assembleDebug :service:assembleDebug :cluster:assembleDebug 2>&1 | tee "$CACHE/logs/build-apk-fast.log"
echo "APK: $ROOT/galaxy/mobile/build/outputs/apk/debug/mobile-debug.apk"
echo "仅assembleDebug；未执行lint/单测、未安装。"
