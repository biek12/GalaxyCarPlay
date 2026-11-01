#!/usr/bin/env bash
# GalaxyPlay 本机验证入口（决策 D09 / D10）
#
# 一条命令完成：离线源码契约 → 工具回归 → :shared/:common 单测 → :mobile lint + assembleDebug。
# 该脚本与 scripts/verify/lib 下的 init 脚本只服务本机验证，不进入 APK、不修改产品依赖声明。
#
# 用法：
#   scripts/verify.sh                  # 全量：检查 + 单测 + lint + assembleDebug
#   scripts/verify.sh --quick          # 只跑离线源码检查与工具回归（秒级，不调 Gradle）
#   scripts/verify.sh --no-lint        # 跳过 :mobile:lintDebug
#   scripts/verify.sh --offline        # 强制 Robolectric 离线；缓存缺失直接失败
#   scripts/verify.sh --mirror         # 依赖解析走公开镜像（仅本机网络受限时）
#   scripts/verify.sh --log /tmp/x.log # 自定义日志路径
#
# 缓存与工具链（默认值均位于仓库内 .work，已被 .gitignore 排除）：
#   GALAXY_VERIFY_CACHE   Robolectric 依赖与沙箱 home 的根目录（默认 <repo>/.work）
#   GRADLE_USER_HOME      Gradle 用户目录（默认 <GALAXY_VERIFY_CACHE>/gradle-home）
#   GALAXY_ANDROID_SDK    Android SDK 路径；目录存在时会导出为 ANDROID_HOME/ANDROID_SDK_ROOT

set -uo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
CACHE=${GALAXY_VERIFY_CACHE:-$ROOT/.work}
GRADLE_HOME=${GRADLE_USER_HOME:-$CACHE/gradle-home}
SDK=${GALAXY_ANDROID_SDK:-$CACHE/android-sdk}
ROBOLECTRIC_INIT="$ROOT/scripts/verify/lib/robolectric-offline.init.gradle"
MIRROR_INIT="$ROOT/scripts/verify/lib/maven-mirror.init.gradle"

MODE=full
RUN_LINT=1
STRICT_OFFLINE=0
USE_MIRROR=0
LOG=""

usage() { sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
    case "$1" in
        --quick) MODE=quick ;;
        --no-lint) RUN_LINT=0 ;;
        --offline) STRICT_OFFLINE=1 ;;
        --mirror) USE_MIRROR=1 ;;
        --log) shift; LOG=${1:-} ;;
        -h|--help) usage; exit 0 ;;
        *) echo "未知参数: $1（--help 查看用法）" >&2; exit 2 ;;
    esac
    shift
done

if [ -z "$LOG" ]; then
    LOG="$CACHE/logs/verify-$(date +%Y%m%d-%H%M%S).log"
fi
mkdir -p "$(dirname -- "$LOG")" "$GRADLE_HOME" "$CACHE/android-user-home" || {
    echo "无法创建验证目录: $LOG" >&2; exit 2;
}

FAILED_STEP=""
STARTED_AT=$(date +%s)

log_line() { printf '%s\n' "$*" | tee -a "$LOG"; }

run_step() {
    local name=$1; shift
    local begin step_seconds
    begin=$(date +%s)
    log_line ""
    log_line "==> $name"
    log_line "    \$ $*"
    if "$@" 2>&1 | tee -a "$LOG" | sed -e 's/^/    /'; then
        step_seconds=$(( $(date +%s) - begin ))
        log_line "<== $name 通过（${step_seconds}s）"
        return 0
    else
        local status=${PIPESTATUS[0]:-1}
        step_seconds=$(( $(date +%s) - begin ))
        log_line "<== $name 失败（exit=${status}，${step_seconds}s）"
        FAILED_STEP=$name
        return 1
    fi
}

# 结果汇总：直接读 XML 结果，不依赖控制台文案。
summarize_tests() {
    python3 - "$@" <<'PY' 2>/dev/null
import glob, sys, xml.etree.ElementTree as ET
tests = failures = errors = skipped = 0
files = 0
for pattern in sys.argv[1:]:
    for path in glob.glob(pattern):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        files += 1
        tests += int(root.get('tests', 0) or 0)
        failures += int(root.get('failures', 0) or 0)
        errors += int(root.get('errors', 0) or 0)
        skipped += int(root.get('skipped', 0) or 0)
if not files:
    print('未找到测试结果 XML')
else:
    print(f'{tests} 项 / {failures} 失败 / {errors} 错误 / {skipped} 跳过（{files} 个结果文件）')
PY
}

log_line "GalaxyPlay 本机验证 $MODE 模式，开始于 $(date '+%Y-%m-%d %H:%M:%S %Z')"
log_line "仓库: $ROOT"
log_line "日志: $LOG"

if [ -d "$SDK" ]; then
    export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
    log_line "Android SDK: $SDK"
else
    log_line "Android SDK: 未找到 ${SDK}（Gradle 需自行定位）"
fi

if [ -f "$ROBOLECTRIC_INIT" ]; then
    export GALAXY_VERIFY_CACHE="$CACHE"
    [ "$STRICT_OFFLINE" = 1 ] && export GALAXY_ROBOLECTRIC_STRICT_OFFLINE=1
else
    log_line "缺少 Robolectric init 脚本: $ROBOLECTRIC_INIT"; exit 2
fi

# 1) 离线源码契约检查（不联网、不调 Gradle）
run_step "离线源码契约检查" python3 "$ROOT/scripts/project_checks.py" || true
# 2) 工具回归
[ -z "$FAILED_STEP" ] && { run_step "校验工具回归" python3 -m unittest discover -s "$ROOT/scripts/tests" || true; }

if [ "$MODE" = quick ]; then
    log_line ""
    log_line "（--quick：未执行 Gradle 编译与单测）"
else
    GRADLE_ARGS=(--no-daemon --console=plain -Pandroid.builder.sdkDownload=false
        "-I" "$ROBOLECTRIC_INIT")
    [ "$USE_MIRROR" = 1 ] && GRADLE_ARGS+=("-I" "$MIRROR_INIT")
    TASKS=(:shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleDebug)
    LINT_LABEL=""
    if [ "$RUN_LINT" = 1 ]; then TASKS+=(":mobile:lintDebug"); LINT_LABEL=" + lintDebug"; fi

    # 3) 单测 + 打包 + lint（GRADLE_USER_HOME 只作用于本次调用）
    run_gradle() {
        GRADLE_USER_HOME="$GRADLE_HOME" "$ROOT/gradlew" "${GRADLE_ARGS[@]}" "${TASKS[@]}"
    }
    [ -z "$FAILED_STEP" ] && {
        run_step "Gradle：单测 + assembleDebug${LINT_LABEL}" run_gradle || true
    }
fi

log_line ""
log_line "==> 结果汇总"
if [ "$MODE" = quick ]; then
    log_line "    （--quick 未重新执行单测，以下数字来自上次结果文件，仅供对照）"
fi
log_line "    :shared 单测: $(summarize_tests "$ROOT/shared/build/test-results/testDebugUnitTest/TEST-*.xml")"
log_line "    :common 单测: $(summarize_tests "$ROOT/common/build/test-results/testDebugUnitTest/TEST-*.xml")"
APK=$(ls -1 "$ROOT"/galaxy/mobile/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
if [ -n "$APK" ]; then
    log_line "    APK: ${APK#"$ROOT"/}（$(wc -c <"$APK" | tr -d ' ') 字节，debug 签名，未安装）"
else
    log_line "    APK: 未产出"
fi

ELAPSED=$(( $(date +%s) - STARTED_AT ))
log_line ""
if [ -z "$FAILED_STEP" ]; then
    log_line "验证通过（耗时 ${ELAPSED}s）。这是离线源码/单测/编译结论，不代表实车或设备验收。"
    exit 0
fi
log_line "验证失败于：${FAILED_STEP}（耗时 ${ELAPSED}s）。完整日志：$LOG"
exit 1