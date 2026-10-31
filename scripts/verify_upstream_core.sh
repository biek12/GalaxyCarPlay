#!/usr/bin/env bash
# 核心发布迁移回归：旧产品适配与新上游测试共同运行；不含Lint/设备操作。
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
python3 scripts/project_checks.py
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
  -I scripts/verify/lib/robolectric-offline.init.gradle \
  :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:assembleDebug :service:assembleDebug :cluster:assembleDebug \
  2>&1 | tee "$CACHE/logs/verify-upstream-core.log"
