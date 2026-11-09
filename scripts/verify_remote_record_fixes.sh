#!/usr/bin/env bash
# 两份2026-11-04排查记录的离线回归；不跑Lint、不连接车辆、不更换签名。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
export ANDROID_HOME="$ROOT/.work/android-sdk" ANDROID_SDK_ROOT="$ROOT/.work/android-sdk"
export ANDROID_PREFS_ROOT="$ROOT/.work/android-prefs" GRADLE_USER_HOME="$ROOT/.work/gradle-home"
export GALAXY_VERIFY_CACHE="$ROOT/.work" GALAXY_ROBOLECTRIC_STRICT_OFFLINE=1
[ -s "$ANDROID_PREFS_ROOT/.android/debug.keystore" ] || { echo '缺少原工程调试签名，停止构建' >&2; exit 2; }
mkdir -p "$ROOT/.work/logs"
cd "$ROOT"
TESTS=(:record:testDebugUnitTest --tests 'com.galaxy.autorecord.recording.*' --tests 'com.galaxy.autorecord.storage.*' --tests 'com.galaxy.autorecord.runtime.*'
       :service:testDebugUnitTest --tests 'com.ecarx.autoservice.remote.*' --tests 'com.ecarx.autoservice.platform.AdbCryptoTest' --tests 'com.ecarx.autoservice.diagnostics.*')
if [ "${1:-}" = --all-tests ]; then TESTS=(:record:testDebugUnitTest :service:testDebugUnitTest); fi
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
  -Pkotlin.compiler.execution.strategy=in-process -I scripts/verify/lib/robolectric-offline.init.gradle \
  :auto-client:testDebugUnitTest "${TESTS[@]}" \
  :record:assembleDebug :record:assembleField :service:assembleDebug :cluster:assembleDebug :mobile:assembleDebug \
  2>&1 | tee "$ROOT/.work/logs/verify-remote-record-fixes.log"
