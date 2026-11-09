#!/usr/bin/env bash
# 单命令本机回归；不连接车辆，不走远程通道，不运行Lint。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
export ANDROID_HOME="$ROOT/.work/android-sdk" ANDROID_SDK_ROOT="$ROOT/.work/android-sdk"
export GRADLE_USER_HOME="$ROOT/.work/gradle-home" ANDROID_PREFS_ROOT="$ROOT/.work/android-prefs"
export GALAXY_VERIFY_CACHE="$ROOT/.work" GALAXY_ROBOLECTRIC_STRICT_OFFLINE=1
test -s "$ANDROID_PREFS_ROOT/.android/debug.keystore" || { echo '缺少工程原签名，拒绝生成新签名' >&2; exit 2; }
ARGS=(--offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false -Pkotlin.compiler.execution.strategy=in-process
      -I "$ROOT/scripts/verify/lib/robolectric-offline.init.gradle")
if [ "${1:-}" != --emulator-only ]; then
    "$ROOT/gradlew" "${ARGS[@]}" :record:testDebugUnitTest :auto-contracts:testDebugUnitTest \
        :service:testDebugUnitTest :service:assembleDebug :record:assembleField
fi
if [ "${1:-}" != --emulator ] && [ "${1:-}" != --emulator-only ]; then exit 0; fi
# testBuildType切换后Debug单测任务不存在，故独立Gradle调用。
"$ROOT/gradlew" "${ARGS[@]}" -PgalaxyRecordEmulatorTest=true :record:assembleEmulator :record:assembleEmulatorAndroidTest
SDK=${GALAXY_EMULATOR_SDK:-/Users/anjon/android/sdk}
ADB="$SDK/platform-tools/adb"
SERIAL=emulator-5572
RUNTIME="$ROOT/.work/autorecord-flat-sentry-emulator"
STARTED=0
cleanup(){ if [ "$STARTED" = 1 ]; then "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true; fi; }
trap cleanup EXIT
if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
    python3 - "$ROOT" "$RUNTIME" <<'PY'
from pathlib import Path
import sys
root, runtime = map(Path, sys.argv[1:])
source = root / '.work/autoservice-ui-emulator/avd/AutoService_UI_API30.avd/config.ini'
cfg = dict(line.split('=',1) for line in source.read_text().splitlines() if '=' in line)
cfg = {k.strip():v.strip() for k,v in cfg.items()}
cfg.update({'avd.id':'AutoRecord_FlatSentry','avd.name':'AutoRecord_FlatSentry',
    'hw.camera.back':'none','hw.camera.front':'none','hw.cpu.ncore':'2',
    'fastboot.forceColdBoot':'yes','fastboot.forceFastBoot':'no'})
avd = runtime / 'avd/AutoRecord_FlatSentry.avd'
avd.mkdir(parents=True, exist_ok=True)
(avd/'config.ini').write_text(''.join(f'{k} = {v}\n' for k,v in cfg.items()))
(runtime/'avd/AutoRecord_FlatSentry.ini').write_text(f'avd.ini.encoding=UTF-8\npath={avd}\ntarget=android-30\n')
PY
    # 空闲空间不足以重新展开6GiB镜像时，克隆既有离线AVD的匹配数据/加密元数据；不改原件。
    TARGET="$RUNTIME/avd/AutoRecord_FlatSentry.avd"
    SOURCE="$ROOT/.work/autoservice-ui-emulator/avd/AutoService_UI_API30.avd"
    if [ ! -f "$TARGET/userdata-qemu.img" ]; then
        for part in userdata-qemu.img userdata-qemu.img.qcow2 encryptionkey.img encryptionkey.img.qcow2; do
            cp -c "$SOURCE/$part" "$TARGET/$part"
        done
    fi
    ANDROID_AVD_HOME="$RUNTIME/avd" ANDROID_EMULATOR_HOME="$RUNTIME" \
        "$SDK/emulator/emulator" -avd AutoRecord_FlatSentry -port 5572 -no-snapshot -no-boot-anim \
        -gpu swiftshader_indirect -no-audio -no-window -no-metrics > "$RUNTIME/emulator.log" 2>&1 &
    STARTED=1
fi
for _ in $(seq 1 120); do
    if [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then break; fi
    sleep 1
done
[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r')" = 1 ] || { echo '隔离AVD未完成启动' >&2; exit 2; }
[ "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || exit 2
[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = AutoRecord_FlatSentry ] || exit 2
"$ADB" -s "$SERIAL" shell am force-stop com.ecarx.autoservice
"$ADB" -s "$SERIAL" install -r "$ROOT/galaxy/record/build/outputs/apk/emulator/record-emulator.apk"
"$ADB" -s "$SERIAL" install -r "$ROOT/galaxy/record/build/outputs/apk/androidTest/emulator/record-emulator-androidTest.apk"
for action in flat-render flat-playback sentinel-ui; do
    "$ADB" -s "$SERIAL" shell am instrument -w -e action "$action" -e codecName OMX.google.h264.encoder \
        com.galaxy.autorecord.test/com.galaxy.autorecord.RecordOfflineInstrumentation > "$RUNTIME/$action.txt"
    cat "$RUNTIME/$action.txt"
    rg -q 'INSTRUMENTATION_RESULT: result=PASS' "$RUNTIME/$action.txt"
done
