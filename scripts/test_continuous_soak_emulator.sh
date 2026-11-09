#!/usr/bin/env bash
# 固定隔离AVD，真实编码1GiB循环覆盖，默认30分钟；不访问车辆。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
SDK=${GALAXY_EMULATOR_SDK:-/Users/anjon/android/sdk}
ADB="$SDK/platform-tools/adb"
SERIAL=emulator-5568
RUN="$ROOT/.work/continuous-soak"
mkdir -p "$RUN"
# 此入口先完成真实Camera2多次换段回归并保持隔离AVD运行。
if [ "${GALAXY_SKIP_SOAK_PREFLIGHT:-0}" != 1 ]; then
 GALAXY_KEEP_TEST_EMULATOR=1 bash "$ROOT/scripts/test_remote_record_fixes_emulator.sh" > "$RUN/preflight.log" 2>&1 || { cat "$RUN/preflight.log"; exit 1; }
fi
cleanup() { if [ "${GALAXY_KEEP_TEST_EMULATOR:-0}" != 1 ]; then "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true;fi; }
trap cleanup EXIT
[ "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ]
[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = AutoRecord_Offline ]
"$ADB" -s "$SERIAL" shell am instrument -w -e action diagnostic-crash \
 com.galaxy.autorecord.test/com.galaxy.autorecord.RecordOfflineInstrumentation > "$RUN/crash-injection.txt" || true
"$ADB" -s "$SERIAL" shell am instrument -w -e action diagnostics \
 com.galaxy.autorecord.test/com.galaxy.autorecord.RecordOfflineInstrumentation > "$RUN/diagnostics-result.txt"
grep -q 'INSTRUMENTATION_RESULT: result=PASS' "$RUN/diagnostics-result.txt" || { cat "$RUN/diagnostics-result.txt";exit 1; }
"$ADB" -s "$SERIAL" shell am instrument -w -e action soak -e seconds "${GALAXY_SOAK_SECONDS:-1800}" \
 com.galaxy.autorecord.test/com.galaxy.autorecord.RecordOfflineInstrumentation > "$RUN/result.txt"
"$ADB" -s "$SERIAL" logcat -d -t 6000 > "$RUN/logcat.txt"
PROBE_PATH=$(sed -n 's/^INSTRUMENTATION_RESULT: artifacts=//p' "$RUN/result.txt" | tr -d '\r')
if [[ "$PROBE_PATH" =~ ^/data/user/0/com\.galaxy\.autorecord/cache/continuous-soak-[0-9]+$ ]]; then
 "$ADB" -s "$SERIAL" exec-out run-as com.galaxy.autorecord tar -cf - "cache/${PROBE_PATH##*/}" > "$RUN/artifacts.tar"
 "$ADB" -s "$SERIAL" exec-out run-as com.galaxy.autorecord cat "$PROBE_PATH/resources.jsonl" > "$RUN/resources.jsonl"
fi
cat "$RUN/result.txt"
grep -q 'INSTRUMENTATION_RESULT: result=PASS' "$RUN/result.txt"
