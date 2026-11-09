#!/usr/bin/env bash
# 只在本机测试/构建，不调用远程通道、不部署服务器、不连接或安装车辆。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
export ANDROID_HOME="$ROOT/.work/android-sdk" ANDROID_SDK_ROOT="$ROOT/.work/android-sdk"
export GRADLE_USER_HOME="$ROOT/.work/gradle-home" ANDROID_PREFS_ROOT="$ROOT/.work/android-prefs"
[ -s "$ANDROID_PREFS_ROOT/.android/debug.keystore" ] || { echo '缺少原工程调试签名，拒绝自动生成' >&2; exit 2; }
export GALAXY_VERIFY_CACHE="$ROOT/.work" GALAXY_ROBOLECTRIC_STRICT_OFFLINE=1
(cd "$ROOT/server/galaxyd" && go test -race ./...)
"$ROOT/gradlew" --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
    -Pkotlin.compiler.execution.strategy=in-process -I "$ROOT/scripts/verify/lib/robolectric-offline.init.gradle" \
    :record:testDebugUnitTest :record:assembleDebug :record:assembleField :record:assembleEmulator \
    :service:assembleDebug :service:testDebugUnitTest --tests com.ecarx.autoservice.platform.ClientPermissionPolicyTest
"$ROOT/gradlew" --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
    -Pkotlin.compiler.execution.strategy=in-process -PgalaxyRecordEmulatorTest=true \
    -I "$ROOT/scripts/verify/lib/robolectric-offline.init.gradle" :record:assembleEmulatorAndroidTest
if [ "${1:-}" = --emulator ]; then "$ROOT/scripts/test_autorecord_emulator.sh" --no-build; fi
