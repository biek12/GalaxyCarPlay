#!/system/bin/sh
# 仅白名单系统信息，不抓全量logcat/getprop/配对/标识或网络配置。
set -u
case "$0" in */*) SCRIPT_DIR=${0%/*} ;; *) SCRIPT_DIR=. ;; esac
HERE=$(CDPATH= cd "$SCRIPT_DIR" && pwd) || exit 2
mkdir -p "$HERE/../logs" || exit 2
{
    date
    getprop ro.build.version.release
    getprop ro.build.version.sdk
    getprop ro.product.cpu.abi
    ls -l /dev/hab
    ls -l "$HERE/galaxy_hab_android_probe"
    ps -A | grep 'galaxy_hab' || true
} >"$HERE/../logs/android-snapshot.txt" 2>&1
echo "已保存限定字段快照"
