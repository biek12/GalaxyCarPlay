#!/usr/bin/env bash
# 固定隔离 AVD，合成相机场景。无电脑远程 ADB、无车机/公网请求。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
SDK=${GALAXY_EMULATOR_SDK:-/Users/anjon/android/sdk}
ADB="$SDK/platform-tools/adb"
RUNTIME="$ROOT/.work/autorecord-emulator"
SERIAL=emulator-5568
STARTED=0
cleanup() {
  if [ "$STARTED" = 1 ] && [ "${GALAXY_KEEP_TEST_EMULATOR:-0}" != 1 ]; then "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
mkdir -p "$RUNTIME"
if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  ANDROID_AVD_HOME="$RUNTIME/avd" ANDROID_EMULATOR_HOME="$RUNTIME" \
    nohup "$SDK/emulator/emulator" -avd AutoRecord_Offline -port 5568 -no-snapshot -no-boot-anim \
    -gpu swiftshader_indirect -camera-back emulated -camera-front none -no-window -no-audio \
    > "$RUNTIME/remote-record-emulator.log" 2>&1 &
  STARTED=1
fi
for _ in $(seq 1 120); do
  if [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then break; fi
  sleep 2
done
[ "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo '拒绝非模拟器目标' >&2; exit 2; }
[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = AutoRecord_Offline ] || { echo '拒绝其他AVD' >&2; exit 2; }
export ANDROID_HOME="$ROOT/.work/android-sdk" ANDROID_SDK_ROOT="$ROOT/.work/android-sdk"
export ANDROID_PREFS_ROOT="$ROOT/.work/android-prefs" GRADLE_USER_HOME="$ROOT/.work/gradle-home"
[ -s "$ANDROID_PREFS_ROOT/.android/debug.keystore" ] || { echo '缺少原工程调试签名' >&2; exit 2; }
cd "$ROOT"
if [ "${1:-}" != --no-build ]; then
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false -PgalaxyRecordEmulatorTest=true \
  -Pkotlin.compiler.execution.strategy=in-process -I scripts/verify/lib/robolectric-offline.init.gradle \
  :record:assembleEmulator :record:assembleEmulatorAndroidTest :service:assembleDebug :service:assembleDebugAndroidTest \
  > "$RUNTIME/remote-record-build.log" 2>&1 || { tail -80 "$RUNTIME/remote-record-build.log"; exit 1; }
fi
# 仅回收本次测试新增缓存，避免打包同一AVD其他UI测试/旧样本。
# 只在固定测试AVD关闭业务自启；测试身份/TLS不启动后台业务与远程注册。
"$ADB" -s "$SERIAL" shell run-as com.ecarx.autoservice pm disable com.ecarx.autoservice/.startup.AutoBootReceiver >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell run-as com.ecarx.autoservice pm disable com.ecarx.autoservice/.startup.AutoRecoveryJob >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell am force-stop com.ecarx.autoservice
"$ADB" -s "$SERIAL" install -r "$ROOT/galaxy/service/build/outputs/apk/debug/service-debug.apk"
"$ADB" -s "$SERIAL" install -r "$ROOT/galaxy/service/build/outputs/apk/androidTest/debug/service-debug-androidTest.apk"
"$ADB" -s "$SERIAL" shell run-as com.ecarx.autoservice pm disable com.ecarx.autoservice/.startup.AutoBootReceiver >/dev/null
"$ADB" -s "$SERIAL" shell run-as com.ecarx.autoservice pm disable com.ecarx.autoservice/.startup.AutoRecoveryJob >/dev/null
"$ADB" -s "$SERIAL" shell am force-stop com.ecarx.autoservice
"$ADB" -s "$SERIAL" shell am instrument -w -e action adb-identity \
  com.ecarx.autoservice.test/com.ecarx.autoservice.projection.ProjectionSmokeInstrumentation > "$RUNTIME/identity-result.txt"
grep -q 'INSTRUMENTATION_RESULT: status=PASS' "$RUNTIME/identity-result.txt" || { cat "$RUNTIME/identity-result.txt"; exit 1; }
"$ADB" -s "$SERIAL" install -r "$ROOT/galaxy/record/build/outputs/apk/emulator/record-emulator.apk"
"$ADB" -s "$SERIAL" install -r "$ROOT/galaxy/record/build/outputs/apk/androidTest/emulator/record-emulator-androidTest.apk"
"$ADB" -s "$SERIAL" shell pm grant com.galaxy.autorecord android.permission.CAMERA
"$ADB" -s "$SERIAL" shell am instrument -w -e action rollover -e codecName OMX.google.h264.encoder \
  com.galaxy.autorecord.test/com.galaxy.autorecord.RecordOfflineInstrumentation > "$RUNTIME/rollover-result.txt"
"$ADB" -s "$SERIAL" logcat -d -s AutoRecordEngine AutoRecordCamera > "$RUNTIME/rollover-logcat.txt"
PROBE_PATH=$(sed -n 's/^INSTRUMENTATION_RESULT: artifacts=//p' "$RUNTIME/rollover-result.txt" | tr -d '\r')
if [[ "$PROBE_PATH" =~ ^/data/user/0/com\.galaxy\.autorecord/cache/rollover-probe-[0-9]+$ ]]; then
  "$ADB" -s "$SERIAL" exec-out run-as com.galaxy.autorecord tar -cf - "cache/${PROBE_PATH##*/}" > "$RUNTIME/rollover-artifacts.tar"
fi
grep -q 'INSTRUMENTATION_RESULT: result=PASS' "$RUNTIME/rollover-result.txt" || { cat "$RUNTIME/rollover-result.txt"; exit 1; }
cat "$RUNTIME/identity-result.txt" "$RUNTIME/rollover-result.txt"
