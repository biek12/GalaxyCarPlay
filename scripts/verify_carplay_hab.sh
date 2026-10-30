#!/usr/bin/env bash
# 本轮行为回归与APK；不执行Lint、不连接/安装、不读取身份或签名。
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
python3 scripts/verify_hab_native.py --android 2>&1 | tee "$CACHE/logs/verify-carplay-hab-native.log"
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
    -I scripts/verify/lib/robolectric-offline.init.gradle \
    :shared:testDebugUnitTest \
    --tests '*AppMicrophoneRequestTest' --tests '*MicrophoneStartTest' \
    --tests '*MicrophoneInputPolicyTest' --tests '*TelephonyMicrophoneTest' \
    --tests '*MicrophoneSourceFallbackTest' --tests '*GalaxyFactoryAudioProfileTest' \
    :service:testDebugUnitTest --tests '*GalaxyHab*Test' \
    :common:testDebugUnitTest --tests '*ClusterMusicTest' \
    :mobile:assembleDebug 2>&1 | tee "$CACHE/logs/verify-carplay-hab-gradle.log"
python3 scripts/record_carplay_hab_validation.py
