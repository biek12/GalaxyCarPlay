#!/usr/bin/env bash
# 仅固定隔离AVD注入自身Java/native崩溃，验证恢复补报；不连接车机/公网。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
SDK=${GALAXY_EMULATOR_SDK:-/Users/anjon/android/sdk}
ADB="$SDK/platform-tools/adb"
SERIAL=emulator-5568
RUN="$ROOT/.work/native-diagnostics"
mkdir -p "$RUN"
if [ "${1:-}" != --no-build ]; then
 GALAXY_KEEP_TEST_EMULATOR=1 bash "$ROOT/scripts/test_remote_record_fixes_emulator.sh" > "$RUN/preflight.log" 2>&1 || { tail -80 "$RUN/preflight.log";exit 1; }
fi
[ "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ]
[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = AutoRecord_Offline ]
# 系统日志权限只授予本次固定AVD中的自有服务，重启使补充组生效。
"$ADB" -s "$SERIAL" shell pm grant com.ecarx.autoservice android.permission.READ_LOGS
"$ADB" -s "$SERIAL" shell am force-stop com.ecarx.autoservice
for mode in diagnostic-crash diagnostic-native-crash; do
 "$ADB" -s "$SERIAL" shell am instrument -w -e action "$mode" \
  com.galaxy.autorecord.test/com.galaxy.autorecord.RecordOfflineInstrumentation > "$RUN/$mode.txt" || true
 action=diagnostics
 if [ "$mode" = diagnostic-native-crash ];then action=native-diagnostics;fi
 "$ADB" -s "$SERIAL" shell am instrument -w -e action "$action" \
  com.galaxy.autorecord.test/com.galaxy.autorecord.RecordOfflineInstrumentation > "$RUN/$action-result.txt"
 cat "$RUN/$action-result.txt"
 grep -q 'INSTRUMENTATION_RESULT: result=PASS' "$RUN/$action-result.txt"
done
