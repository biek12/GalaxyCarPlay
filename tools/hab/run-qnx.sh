#!/bin/sh
# 在目标QNX上显式执行；不修改原车启动项。serve前台跨会话监听，操作者显式停止。
set -u
case "$0" in */*) SCRIPT_DIR=${0%/*} ;; *) SCRIPT_DIR=. ;; esac
HERE=$(CDPATH= cd "$SCRIPT_DIR" && pwd) || exit 2
ROOT=$(CDPATH= cd "$HERE/.." && pwd) || exit 2
MODE=${1:-self-test}
mkdir -p "$ROOT/logs" || exit 2
LOG="$ROOT/logs/qnx-$MODE.log"
PIDFILE="$ROOT/logs/qnx.pid"
if [ -f "$PIDFILE" ]; then echo "存在旧PID记录，先运行stop-qnx.sh核对处理"; exit 2; fi
case "$MODE" in
    self-test|inventory|transport) set -- "$MODE" ;;
    receive) set -- receive "$ROOT/test-only-auth.bin" 2 1920 532 4 trace ;;
    serve)
        if [ "$#" -ne 2 ] || [ ! -f "$2" ]; then
            echo "用法: sh run.sh serve <本应用生成并显式配置到QNX的32B密钥文件>"; exit 2
        fi
        KEY=$2
        set -- serve "$KEY" 2 1920 532 4 ;;
    *) echo "用法: sh run.sh self-test|inventory|transport|receive 或 serve <应用key文件>"; exit 2 ;;
esac
echo "本次日志: $LOG"
"$HERE/galaxy_hab_qnx_probe-candidate" "$@" >"$LOG" 2>&1 &
CHILD=$!
echo "$CHILD" >"$PIDFILE"
trap 'kill -TERM "$CHILD" 2>/dev/null || true' INT TERM
wait "$CHILD"
STATUS=$?
if ! kill -0 "$CHILD" 2>/dev/null; then rm -f "$PIDFILE"; fi
cat "$LOG"
echo "qnx_exit=$STATUS"
exit "$STATUS"
