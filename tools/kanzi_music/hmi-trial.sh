#!/bin/sh
# 仅用于用户另行授权的驻车现场试验；不进APK，不修改启动项或监控清单。
# 本文件含构建占位符，须先由prepare_kanzi_music_field.py生成绑定版本。
set -u
HERE=/otaupdate/galaxy-kanzi-field
ORIGINAL=/apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/cluster-hmi
BACKUP=$HERE/cluster-hmi-original.backup
CANDIDATE=/var/data/galaxy-kanzi-field/cluster-hmi-candidate
GUARD=/var/data/galaxy-kanzi-field/path-guard
LOCK=$HERE/hmi-trial.lock
OLD='@ORIGINAL_CKSUM@'
NEW='@CANDIDATE_CKSUM@'
GUARD_SUM='@GUARD_CKSUM@'
SUM=/usr/bin/cksum
[ -x "$SUM" ] || SUM=cksum
# 不依赖非本车可用的id命令；由已核验的QNX入口启动，文件匹配门控先于任何原厂写入。
match() { actual=$($SUM "$1") || return 1; expected=$2; set -- $actual; [ "$1 $2" = "$expected" ]; }
pids() { pidin -F '%a %A' | awk '($2 == "./cluster-hmi" || $2 == "cluster-hmi") && $1+0>1 {print $1}'; }
mode=${1:-inspect}
if [ "$mode" = inspect ]; then
    match "$BACKUP" "$OLD" && match "$CANDIDATE" "$NEW" && match "$GUARD" "$GUARD_SUM" && echo TRIAL_MATERIALS_MATCH
    exit $?
fi
if [ "$mode" = restore ]; then
    ticket=${2:-}
    [ -d "$LOCK" ] && [ -n "$ticket" ] && [ "$(cat "$LOCK/ticket")" = "$ticket" ] || exit 0
    mkdir "$LOCK/restoring" 2>/dev/null || exit 0
    trap 'rm -rf "$LOCK/restoring"' 0
    match "$BACKUP" "$OLD" || exit 21
    if ! match "$ORIGINAL" "$NEW" && ! match "$ORIGINAL" "$OLD"; then echo RESTORE_TARGET_CHANGED; exit 22; fi
    if match "$ORIGINAL" "$NEW"; then "$GUARD" unmap || exit 23; fi
    match "$ORIGINAL" "$OLD" || exit 24
    stopped_pids=''
    if [ -f "$LOCK/activated" ]; then
        stopped_pids=$(pids)
        for pid in $stopped_pids; do kill -TERM "$pid" || true; done
    fi
    # 使用原厂已存在的APP_MONITOR恢复原启动参数；不新增第二个HMI启动者。
    n=0
    while [ "$n" -lt 15 ]; do
        for pid in $(pids); do
            case " $stopped_pids " in *" $pid "*) continue;; esac
            libraries=$(pidin -p "$pid" libs) || continue
            echo "$libraries" | grep PIE >/dev/null || continue
            if ! echo "$libraries" | grep libgalaxy_kanzi_music >/dev/null; then
                match "$ORIGINAL" "$OLD" || exit 24
                echo ORIGINAL_HMI_RESTORED
                rm -rf "$LOCK" || exit 25
                trap - 0
                exit 0
            fi
        done
        sleep 1; n=$((n+1))
    done
    echo RESTORE_PENDING_OEM_MONITOR
    exit 26
fi
[ "$mode" = activate-60s ] && [ "${2:-}" = --authorized ] || exit 2
match "$ORIGINAL" "$OLD" && match "$BACKUP" "$OLD" && match "$CANDIDATE" "$NEW" || exit 10
match "$GUARD" "$GUARD_SUM" && [ -x "$GUARD" ] || exit 11
set -- $(pids); [ "$#" = 1 ] || exit 12
original_pid=$1
mkdir "$LOCK" || exit 13
ticket="$$-$(date +%Y%m%d%H%M%S)"
echo "$ticket" > "$LOCK/ticket" || exit 14
# 独立计时器在任何原厂写入之前启动；旧代计时器不能恢复新代试验。
(trap '' HUP; sleep 60; sh "$HERE/hmi-trial.sh" restore "$ticket") </dev/null >"$HERE/hmi-watchdog.log" 2>&1 &
fail() { sh "$HERE/hmi-trial.sh" restore "$ticket"; exit 15; }
# 单文件内存路径映射：不写只读/apps；QNX重启也会清除此映射。
"$GUARD" map && match "$ORIGINAL" "$NEW" || fail
echo ACTIVE > "$LOCK/activated" || fail
kill -TERM "$original_pid" || fail
echo TRIAL_ACTIVE_MEMORY_ALIAS_WATCHDOG_60_SECONDS
