#!/usr/bin/env bash
# Cluster本机逻辑与Android构建；不跑Lint、不联网安装依赖、不连接设备。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$ROOT"
CACHE=${GALAXY_VERIFY_CACHE:-$ROOT/.work}
export GALAXY_VERIFY_CACHE="$CACHE"
export ANDROID_HOME=${GALAXY_ANDROID_SDK:-$CACHE/android-sdk}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$CACHE/gradle-home}
export GALAXY_ROBOLECTRIC_STRICT_OFFLINE=1
mkdir -p "$CACHE/logs"
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
  -I scripts/verify/lib/robolectric-offline.init.gradle \
  :cluster:testDebugUnitTest :cluster:assembleDebug 2>&1 | tee "$CACHE/logs/verify-cluster.log"
