#!/usr/bin/env bash
# 本机契约/车辆/生命周期/主题/native回归及三个APK，不运行Lint，不连接设备。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
CACHE=${GALAXY_VERIFY_CACHE:-$ROOT/.work}
export ANDROID_HOME=${GALAXY_ANDROID_SDK:-$CACHE/android-sdk}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$CACHE/gradle-home}
export GALAXY_VERIFY_CACHE="$CACHE"
export GALAXY_ROBOLECTRIC_STRICT_OFFLINE=1
mkdir -p "$CACHE/logs"
cd "$ROOT"
python3 scripts/verify_hab_native.py --android 2>&1 | tee "$CACHE/logs/verify-auto-native.log"
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
  -I scripts/verify/lib/robolectric-offline.init.gradle \
  :auto-contracts:testDebugUnitTest :service:testDebugUnitTest :cluster:testDebugUnitTest \
  :common:testDebugUnitTest --tests '*ClusterMusicTest' --tests '*GalaxyL6HabFrameFormatTest' \
  --tests '*GalaxyVehicleSpeedTest' --tests '*GalaxyHostAdapterTest' --tests '*CarPlayLocationSelectionTest' \
  :service:assembleDebug :service:assembleDebugAndroidTest :cluster:assembleDebug :mobile:assembleDebug 2>&1 | tee "$CACHE/logs/verify-auto-platform.log"
python3 -m unittest discover -s scripts/tests -p 'test_hab*.py'
python3 scripts/record_auto_platform_validation.py
