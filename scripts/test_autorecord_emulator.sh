#!/usr/bin/env bash
# 仅允许本机隔离 AVD；绝不 adb connect、不接受车机序列号。首次开机/摄像头测试另见离线记录。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
RUNTIME="$ROOT/.work/autorecord-emulator"
SDK=${GALAXY_EMULATOR_SDK:-/Users/anjon/android/sdk}
ADB="$SDK/platform-tools/adb"
SERIAL=emulator-5568
CAMERA=${GALAXY_TEST_WEBCAM:-webcam1}
STARTED=0
cleanup() {
    if [ "$STARTED" = 1 ] && [ "${GALAXY_KEEP_TEST_EMULATOR:-0}" != 1 ]; then
        "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT
mkdir -p "$RUNTIME/avd/AutoRecord_Offline.avd"
python3 - "$ROOT" "$RUNTIME" "$CAMERA" <<'PY'
from pathlib import Path
import sys
root,work,camera=map(Path,sys.argv[1:])
source=root/'.work/autoservice-ui-emulator/avd/AutoService_UI_API30.avd/config.ini'
config=dict(line.split('=',1) for line in source.read_text().splitlines() if '=' in line)
config={key.strip():value.strip() for key,value in config.items()}
config.update({'avd.id':'AutoRecord_Offline','avd.name':'AutoRecord_Offline',
               'hw.camera.back':str(camera),'hw.camera.front':'none','hw.lcd.density':'160',
               'hw.ramSize':'2048','fastboot.forceColdBoot':'yes','fastboot.forceFastBoot':'no'})
avd=work/'avd/AutoRecord_Offline.avd'
(avd/'config.ini').write_text(''.join(f'{k} = {v}\n' for k,v in config.items()))
(work/'avd/AutoRecord_Offline.ini').write_text(f'avd.ini.encoding=UTF-8\npath={avd}\ntarget=android-30\n')
PY
if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
    ANDROID_AVD_HOME="$RUNTIME/avd" ANDROID_EMULATOR_HOME="$RUNTIME" \
        nohup "$SDK/emulator/emulator" -avd AutoRecord_Offline -port 5568 -no-snapshot \
        -no-boot-anim -gpu swiftshader_indirect -camera-back "$CAMERA" -camera-front none \
        -no-audio -no-window > "$RUNTIME/emulator.log" 2>&1 &
    STARTED=1
fi
for _ in $(seq 1 120); do
    if [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then break; fi
    sleep 2
done
[ "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo '拒绝非模拟器目标' >&2; exit 2; }
[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = AutoRecord_Offline ] || { echo '拒绝其他 AVD' >&2; exit 2; }
export ANDROID_HOME="$ROOT/.work/android-sdk" ANDROID_SDK_ROOT="$ROOT/.work/android-sdk"
export GRADLE_USER_HOME="$ROOT/.work/gradle-home" ANDROID_PREFS_ROOT="$ROOT/.work/android-prefs"
[ -s "$ANDROID_PREFS_ROOT/.android/debug.keystore" ] || { echo '缺少原工程调试签名，拒绝自动生成' >&2; exit 2; }
if [ "${1:-}" != --no-build ]; then
"$ROOT/gradlew" --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false -PgalaxyRecordEmulatorTest=true \
    -Pkotlin.compiler.execution.strategy=in-process -I "$ROOT/scripts/verify/lib/robolectric-offline.init.gradle" :record:assembleEmulator
"$ROOT/gradlew" --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false -PgalaxyRecordEmulatorTest=true \
    -Pkotlin.compiler.execution.strategy=in-process -I "$ROOT/scripts/verify/lib/robolectric-offline.init.gradle" :record:assembleEmulatorAndroidTest
fi
"$ADB" -s "$SERIAL" install -r "$ROOT/galaxy/record/build/outputs/apk/emulator/record-emulator.apk"
"$ADB" -s "$SERIAL" install -r "$ROOT/galaxy/record/build/outputs/apk/androidTest/emulator/record-emulator-androidTest.apk"
"$ADB" -s "$SERIAL" shell pm grant com.galaxy.autorecord android.permission.CAMERA
# 仅此固定模拟器测试 APK 明确点名诊断编码器；未知分类保持未知，不改变产品选择策略。
"$ADB" -s "$SERIAL" shell am instrument -w -e codecName "${GALAXY_TEST_CODEC_NAME:-OMX.google.h264.encoder}" \
    com.galaxy.autorecord.test/com.galaxy.autorecord.RecordOfflineInstrumentation > "$RUNTIME/codec-result.txt"
grep -q 'INSTRUMENTATION_RESULT: result=PASS' "$RUNTIME/codec-result.txt" || { cat "$RUNTIME/codec-result.txt"; exit 1; }
"$ADB" -s "$SERIAL" shell am instrument -w -e action ui \
    com.galaxy.autorecord.test/com.galaxy.autorecord.RecordOfflineInstrumentation > "$RUNTIME/ui-result.txt"
grep -q 'INSTRUMENTATION_RESULT: result=PASS' "$RUNTIME/ui-result.txt" || { cat "$RUNTIME/ui-result.txt"; exit 1; }
"$ADB" -s "$SERIAL" shell am instrument -w -e action export \
    -e codecName "${GALAXY_TEST_CODEC_NAME:-OMX.google.h264.encoder}" \
    com.galaxy.autorecord.test/com.galaxy.autorecord.RecordOfflineInstrumentation > "$RUNTIME/export-result.txt"
grep -q 'INSTRUMENTATION_RESULT: result=PASS' "$RUNTIME/export-result.txt" || { cat "$RUNTIME/export-result.txt"; exit 1; }
for page in live settings storage codec library; do
    "$ADB" -s "$SERIAL" exec-out run-as com.galaxy.autorecord cat "cache/ui-probe/$page.png" > "$RUNTIME/$page.png"
done
cat "$RUNTIME/codec-result.txt" "$RUNTIME/ui-result.txt" "$RUNTIME/export-result.txt"
echo "隔离目标 ${SERIAL}；未绑定车辆 AutoService。画面质量限制单列于 cameraScene，日志与布局图：${RUNTIME}"
