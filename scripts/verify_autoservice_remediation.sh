#!/usr/bin/env bash
# AutoService整改的本机验证入口；不运行Lint、不连接/安装设备。
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
TASKS=(:service:testDebugUnitTest :cluster:testDebugUnitTest :service:assembleDebug :cluster:assembleDebug :mobile:assembleDebug)
if [[ "${1:-}" == --compile ]]; then TASKS=(:service:compileDebugKotlin); fi
if [[ "${1:-}" == --events ]]; then TASKS=(:service:testDebugUnitTest --tests "*VehicleEventsTest"); fi
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
  -I scripts/verify/lib/robolectric-offline.init.gradle "${TASKS[@]}" 2>&1 | tee "$CACHE/logs/verify-autoservice-remediation.log"
