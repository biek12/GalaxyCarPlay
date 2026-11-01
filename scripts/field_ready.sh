# source scripts/field_ready.sh：仅加载现场函数，不连接设备。
FIELD_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]:-${(%):-%x}}")/.." && pwd -P)
export FIELD_ROOT
export FIELD_HOST="${FIELD_HOST:-}"
export FIELD_RESULTS="${FIELD_RESULTS:-$FIELD_ROOT/.work/vehicle-results/session-$(date +%Y%m%d-%H%M%S)}"
field() {
    local bundle
    bundle=$(cat "$FIELD_ROOT/.work/vehicle-test/cluster-current.txt") || return
    if [ "${1:-}" != init ] && [ -z "$FIELD_HOST" ]; then
        printf '%s\n' '请先 export FIELD_HOST=当前Android地址（IPv6链路本地地址须带接口scope）；不会自动发现或连接ADB。'
        return 2
    fi
    if [ "${1:-}" = authorize ]; then
        python3 "$bundle/scripts/prepare_autoservice_adb.py" --bundle "$bundle" --host "$FIELD_HOST" --app-only --output "$FIELD_RESULTS/foundation.json"
    elif [ "${1:-}" = install ]; then
        python3 "$bundle/scripts/field_telnet_install.py" --bundle "$bundle" --host "$FIELD_HOST" --results "$FIELD_RESULTS"
    else
        python3 "$bundle/scripts/cluster_field_session.py" --bundle "$bundle" --results "$FIELD_RESULTS" --adb "$bundle/scripts/field_telnet_transport.py" --serial "$FIELD_HOST" "$@"
    fi
}
printf '%s\n' '现场函数已加载：field init；设置FIELD_HOST后按执行卡操作。尚未连接或安装设备。'
