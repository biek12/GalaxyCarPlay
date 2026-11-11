#!/usr/bin/env bash
# 对指定工作副本执行真实公共组件审计反例；不改生产源集文件、不打包、不连接设备。
set -euo pipefail
FIXTURE_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
REPOSITORY=$(cd -- "$FIXTURE_ROOT/../../.." && pwd -P)
PROJECT=${1:-$REPOSITORY/.work/reviews/u1-6184fa9}
test "$#" -le 1 || { echo '用法: bash tools/review/galaxy-u1-20261112/run.sh [工作副本]' >&2; exit 2; }
cd "$PROJECT"
export ANDROID_HOME=${GALAXY_ANDROID_SDK:-$REPOSITORY/.work/android-sdk}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$REPOSITORY/.work/gradle-home}
export GALAXY_VERIFY_CACHE=${GALAXY_VERIFY_CACHE:-$REPOSITORY/.work}
export GALAXY_ROBOLECTRIC_STRICT_OFFLINE=1
export GALAXY_U1_AUDIT_REPRO="$FIXTURE_ROOT"
REVIEW_AAPT2=${GALAXY_AAPT2:-$(python3 scripts/verify/lib/local_aapt2.py)}
./gradlew --offline --no-daemon --no-configuration-cache --console=plain \
  -Pandroid.builder.sdkDownload=false "-Pandroid.aapt2FromMavenOverride=$REVIEW_AAPT2" \
  -I scripts/verify/lib/maven-mirror.init.gradle \
  -I scripts/verify/lib/robolectric-offline.init.gradle \
  -I "$FIXTURE_ROOT/repro.init.gradle" \
  :auto-ui:testDebugUnitTest --tests com.galaxy.auto.ui.U1AuditRegressionTest
