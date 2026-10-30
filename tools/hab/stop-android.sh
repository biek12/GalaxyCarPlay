#!/system/bin/sh
set -u
case "$0" in */*) SCRIPT_DIR=${0%/*} ;; *) SCRIPT_DIR=. ;; esac
HERE=$(CDPATH= cd "$SCRIPT_DIR" && pwd) || exit 2
PIDFILE="$HERE/../logs/android.pid"
if [ ! -f "$PIDFILE" ]; then echo "没有本次发送端PID记录"; exit 0; fi
PID=$(cat "$PIDFILE")
case "$PID" in ''|*[!0-9]*) echo "PID记录无效"; exit 2 ;; esac
if ! kill -0 "$PID" 2>/dev/null; then rm -f "$PIDFILE"; exit 0; fi
if ! tr '\000' ' ' <"/proc/$PID/cmdline" | grep -q '/galaxy_hab_android_probe'; then
    echo "PID不再属于本次发送程序；未发送信号"; exit 2
fi
kill -TERM "$PID"
sleep 1
if kill -0 "$PID" 2>/dev/null; then echo "程序仍在等待，保留PID与日志；未强制终止"; exit 1; fi
rm -f "$PIDFILE"
