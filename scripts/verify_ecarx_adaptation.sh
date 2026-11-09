#!/usr/bin/env bash
# 本机定向回归及三个APK；不执行Lint、不连接设备、不安装。
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
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
  -I scripts/verify/lib/robolectric-offline.init.gradle \
  :shared:testDebugUnitTest --tests '*MediaDuckingGainTest' --tests '*AudioDuckCommandTest' \
  --tests '*CarPlayNavigationInputTest' --tests '*MicrophoneCaptureStatsTest' --tests '*MicrophoneStartTest' \
  :common:testDebugUnitTest --tests '*GalaxyL6NavigationValuesTest' --tests '*GalaxyL6DimNavigationPortTest' \
  --tests '*L7MediaCenterSessionTest' --tests '*GalaxyOemArtworkServerTest' --tests '*GalaxyNavigationSessionTest' \
  :mobile:assembleDebug :service:assembleDebug :cluster:assembleDebug \
  2>&1 | tee "$CACHE/logs/verify-ecarx-adaptation.log"
