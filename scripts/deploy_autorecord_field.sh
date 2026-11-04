#!/usr/bin/env bash
# AutoRecord 实车部署与初验（单命令）：
#   ./scripts/deploy_autorecord_field.sh build     只构建 field 包
#   ./scripts/deploy_autorecord_field.sh deploy    构建 + 中继上传 + 保留数据安装
#   ./scripts/deploy_autorecord_field.sh verify    启动应用 + 抓相机日志（需已 deploy）
#   ./scripts/deploy_autorecord_field.sh all       依次做上面三步
#
# 中继 artifact 配额已满时用压缩分块；按偏移覆盖、可校验续传，不依赖 .work 临时程序。
#
# 边界：只安装 com.galaxy.autorecord 并授予它自己的 CAMERA；不改原车启动项、不动其他应用。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
ACTION=${1:-all}
SESSION=${GALAXY_REMOTE_SESSION:-$ROOT/.local/workbuddy-remote-handoff/remote-session.json}
APK="$ROOT/galaxy/record/build/outputs/apk/field/record-field.apk"
PACKAGE=com.galaxy.autorecord
ACTIVITY="$PACKAGE/.ui.RecordActivity"
CACHE="$ROOT/.work"
LOG_DIR="$CACHE/logs"

mkdir -p "$LOG_DIR"

build() {
    echo "==> 构建 field 包（R8 精简，调试签名）"
    ANDROID_HOME="$CACHE/android-sdk" \
    ANDROID_SDK_ROOT="$CACHE/android-sdk" \
    ANDROID_PREFS_ROOT="$CACHE/android-user-home" \
    GRADLE_USER_HOME="$CACHE/gradle-home" \
        "$ROOT/gradlew" --offline --no-daemon --console=plain \
        -Pandroid.builder.sdkDownload=false \
        -Pkotlin.compiler.execution.strategy=in-process \
        -I "$ROOT/scripts/verify/lib/robolectric-offline.init.gradle" \
        :record:assembleField
    ls -la "$APK"
}

remote() {
    python3 "$ROOT/scripts/remote_car.py" --session "$SESSION" shell "$1"
}

deploy() {
    [ -f "$SESSION" ] || { echo "缺少远程会话: $SESSION" >&2; exit 2; }
    [ -f "$APK" ] || build
    echo "==> 中继上传、摘要核验与保留数据安装（首次权限由 App 向 AutoService 串行申请）"
    python3 "$ROOT/scripts/autorecord_field.py" --session "$SESSION" deploy --apk "$APK"
}

verify() {
    echo "==> 启动并抓日志"
    remote "am force-stop $PACKAGE; am start -n $ACTIVITY" >/dev/null
    sleep 10
    local stamp
    stamp=$(date +%Y%m%d-%H%M%S)
    local out="$LOG_DIR/field-$stamp.log"
    remote "p=\$(pidof $PACKAGE); test -n \"\$p\" && logcat -d --pid=\$p -s AutoRecordCamera:V AutoRecordUi:V AndroidRuntime:E" > "$out" 2>&1
    echo "相机/界面日志: $out"
    grep -E "首帧|帧率|打开失败|HAL 拒绝|异常" "$out" | tail -20 || true
    local crash="$LOG_DIR/field-crash-$stamp.log"
    remote "logcat -d -b crash | grep -A20 'Process: $PACKAGE' | tail -40" > "$crash" 2>&1 || true
    if ! grep -q '首帧' "$out"; then
        echo "!! 未取得首帧证据，见 $out（历史崩溃仅供排查: $crash）" >&2
        return 1
    fi
    echo "==> 当前进程存活且已取得首帧；不代替四路/长时验收"
}

case "$ACTION" in
    build) build ;;
    deploy) deploy ;;
    verify) verify ;;
    all) build; deploy; verify ;;
    *) echo "用法: $0 {build|deploy|verify|all}" >&2; exit 2 ;;
esac
