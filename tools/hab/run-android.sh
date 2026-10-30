#!/system/bin/sh
# 在目标Android上显式执行；准备包时不会运行。
set -u
case "$0" in */*) SCRIPT_DIR=${0%/*} ;; *) SCRIPT_DIR=. ;; esac
HERE=$(CDPATH= cd "$SCRIPT_DIR" && pwd) || exit 2
ROOT=$(CDPATH= cd "$HERE/.." && pwd) || exit 2
MODE=${1:-self-test}
mkdir -p "$ROOT/logs" || exit 2
LOG="$ROOT/logs/android-$MODE.log"
PIDFILE="$ROOT/logs/android.pid"
if [ -f "$PIDFILE" ]; then echo "存在旧PID记录，先运行stop-android.sh核对处理"; exit 2; fi
case "$MODE" in
    self-test|transport) set -- "$MODE" ;;
    frames) set -- frames "$ROOT/test-only-auth.bin" 2 1920 532 4 6 1000 synthetic-only ;;
    *) echo "用法: sh run.sh self-test|transport|frames"; exit 2 ;;
esac
echo "本次日志: $LOG"
"$HERE/galaxy_hab_android_probe" "$@" >"$LOG" 2>&1 &
CHILD=$!
echo "$CHILD" >"$PIDFILE"
trap 'kill -TERM "$CHILD" 2>/dev/null || true' INT TERM
wait "$CHILD"
STATUS=$?
if ! kill -0 "$CHILD" 2>/dev/null; then rm -f "$PIDFILE"; fi
cat "$LOG"
echo "android_exit=$STATUS"
exit "$STATUS"
