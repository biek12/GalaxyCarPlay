#!/usr/bin/env bash
# 在既有隔离 AVD 上运行设计验收并保存真实系统截图；不接受外部设备序列号。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
# 启动示例：ANDROID_AVD_HOME=.work/autorecord-emulator/avd ANDROID_EMULATOR_HOME=.work/autorecord-emulator
# /Users/anjon/android/sdk/emulator/emulator -avd AutoRecord_Offline -port 5568 -no-snapshot -gpu host -feature -Vulkan -no-window -no-metrics
SDK=${GALAXY_EMULATOR_SDK:-/Users/anjon/android/sdk}
ADB="$SDK/platform-tools/adb"
SERIAL=emulator-5568
OUT="$ROOT/.work/autorecord-emulator/design-screens"
[ "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ]
[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = AutoRecord_Offline ]
export ANDROID_HOME="$ROOT/.work/android-sdk" ANDROID_SDK_ROOT="$ROOT/.work/android-sdk"
export GRADLE_USER_HOME="$ROOT/.work/gradle-home" ANDROID_PREFS_ROOT="$ROOT/.work/android-prefs"
[ -s "$ANDROID_PREFS_ROOT/.android/debug.keystore" ]
if [ "${1:-}" != --no-build ]; then
    "$ROOT/gradlew" --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
        -PgalaxyRecordEmulatorTest=true -Pkotlin.compiler.execution.strategy=in-process \
        -I "$ROOT/scripts/verify/lib/robolectric-offline.init.gradle" :record:assembleEmulator :record:assembleEmulatorAndroidTest
fi
"$ADB" -s "$SERIAL" install -r "$ROOT/galaxy/record/build/outputs/apk/emulator/record-emulator.apk"
"$ADB" -s "$SERIAL" install -r "$ROOT/galaxy/record/build/outputs/apk/androidTest/emulator/record-emulator-androidTest.apk"
"$ADB" -s "$SERIAL" shell pm grant com.galaxy.autorecord android.permission.CAMERA
"$ADB" -s "$SERIAL" shell am instrument -w -e action design \
    com.galaxy.autorecord.test/com.galaxy.autorecord.RecordOfflineInstrumentation > "$ROOT/.work/autorecord-emulator/design-result.txt"
cat "$ROOT/.work/autorecord-emulator/design-result.txt"
grep -q 'INSTRUMENTATION_RESULT: result=PASS' "$ROOT/.work/autorecord-emulator/design-result.txt"
mkdir -p "$OUT"
for file in $("$ADB" -s "$SERIAL" shell run-as com.galaxy.autorecord ls cache/design-probe | tr -d '\r' | rg '\.png$'); do
    "$ADB" -s "$SERIAL" exec-out run-as com.galaxy.autorecord cat "cache/design-probe/$file" > "$OUT/$file"
done
echo "截图目录：${OUT}；仅测试样本，非车辆读数。"
