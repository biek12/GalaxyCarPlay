#!/usr/bin/env bash
# 本机定向回归/编译；不执行Lint、不连接设备、不安装。
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
  :shared:testDebugUnitTest --tests '*GalaxyFactoryAudioProfileTest' --tests '*AppMicrophoneRequestTest' \
  --tests '*TelephonyMicrophoneTest' --tests '*MicrophoneStartTest' --tests '*MicrophoneSourceFallbackTest' \
  --tests '*MicrophoneInputPolicyTest' --tests '*AudioChannelMappingTest' --tests '*CarPlayAudioStreamIsolationTest' \
  :common:testDebugUnitTest --tests '*L7MediaCenterSessionTest' --tests '*GalaxyOemMediaContractTest' \
  --tests '*GalaxyHostAdapterTest' --tests '*GalaxyMediaBindingTest' --tests '*GalaxyMediaSdkTest' \
  --tests '*GalaxyOemLyricsTest' \
  :mobile:assembleDebug :service:assembleDebug :cluster:assembleDebug \
  2>&1 | tee "$CACHE/logs/verify-carplay-experience.log"
