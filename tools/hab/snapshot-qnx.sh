#!/bin/sh
# 不抓全量进程参数或slog；原车窗口只读核对尺寸/显示ID/层级等可视属性。
set -u
case "$0" in */*) SCRIPT_DIR=${0%/*} ;; *) SCRIPT_DIR=. ;; esac
HERE=$(CDPATH= cd "$SCRIPT_DIR" && pwd) || exit 2
PHASE=${1:-snapshot}
case "$PHASE" in before|during|after|snapshot) ;; *) echo "phase须为before/during/after/snapshot"; exit 2 ;; esac
mkdir -p "$HERE/../logs" || exit 2
{
    date
    uname -srm
    ls -l "$HERE/galaxy_hab_qnx_probe-candidate"
    pidin ar | grep -E 'galaxy_hab_qnx_probe|cluster-hmi|/Screen' || true
    # 同时保留原车/安全显示/自有窗口的限定属性；仅看cluster-hmi会漏掉独立告警层。
    for WINDOW in /dev/screen/*/win-*; do
        if [ -f "$WINDOW" ]; then
            echo "window = $WINDOW"
            grep -E '^[[:space:]]*(display|status|buffer size|size|order|position|source size|source position|visible|type|format|alpha|transparency|pipeline|parent|group)[[:space:]]*=' "$WINDOW" || true
        fi
    done
} >"$HERE/../logs/qnx-$PHASE.txt" 2>&1
echo "已保存限定字段快照"
