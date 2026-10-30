#!/bin/sh
# 不抓全量进程参数或slog；原车窗口只读核对尺寸/显示ID/层级等可视属性。
set -u
case "$0" in */*) SCRIPT_DIR=${0%/*} ;; *) SCRIPT_DIR=. ;; esac
HERE=$(CDPATH= cd "$SCRIPT_DIR" && pwd) || exit 2
mkdir -p "$HERE/../logs" || exit 2
{
    date
    uname -srm
    ls -l "$HERE/galaxy_hab_qnx_probe-candidate"
    pidin ar | grep -E 'galaxy_hab_qnx_probe|cluster-hmi|/Screen' || true
    for WINDOW in /dev/screen/cluster-hmi/win-*; do
        if [ -f "$WINDOW" ]; then
            grep -E 'size|order|dpy|visible|type|status' "$WINDOW" || true
        fi
    done
} >"$HERE/../logs/qnx-snapshot.txt" 2>&1
echo "已保存限定字段快照"
