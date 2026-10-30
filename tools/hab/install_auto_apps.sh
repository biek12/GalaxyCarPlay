#!/usr/bin/env bash
# 操作者显式执行并指定ADB序列号后安装；不卸载、不改签名、不自动启动投屏。
set -euo pipefail
SERIAL=${1:?用法: install_auto_apps.sh <明确的ADB序列号> <测试包app目录>}
APPS=${2:?需指定测试包app目录}
ADB=${GALAXY_ADB:-adb}
for NAME in AutoService AutoCluster CarPlay; do
    "$ADB" -s "$SERIAL" install -r "$APPS/$NAME-debug.apk"
done
