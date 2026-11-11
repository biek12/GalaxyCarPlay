#!/bin/sh
# Explicit product lifecycle entry, never run by offline verification.
# No OEM file, mount, startup list or window layer is changed.
set -u
PATH=/proc/boot:/bin:/usr/bin:/sbin:/usr/sbin
export PATH
HERE=/var/data/autoservice-cluster
DIR=/apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI
SO=$HERE/libgalaxy_g733_cluster.so
LOCK=$HERE/runtime.lock
SELF=$HERE/g733_hook_runtime.sh
CHECK=$HERE/health
pids() {
    pidin -F '%a %A' | awk '$1+0>1 && ($2=="./cluster-hmi" || $2=="cluster-hmi" || $2=="/apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/cluster-hmi") {print $1}'
}
owned() { pidin -p "$1" libs 2>/dev/null | grep -F "$SO" >/dev/null; }
restore_owned() {
    ticket=$1
    [ -r "$LOCK/ticket" ] && [ "$(cat "$LOCK/ticket")" = "$ticket" ] || return 0
    mkdir "$LOCK/stopping" 2>/dev/null || return 0
    n=0
    while [ "$n" -lt 15 ]; do
        set -- $(pids)
        for pid do
            if owned "$pid"; then
                if [ "$n" -lt 2 ]; then kill -TERM "$pid" 2>/dev/null || :
                else kill -KILL "$pid" 2>/dev/null || :; fi
            fi
        done
        if [ "$#" -eq 1 ] && ! owned "$1" && pidin -p "$1" libs 2>/dev/null | grep PIE >/dev/null; then
            rm -rf "$LOCK"
            echo G733_RUNTIME_STOCK_RESTORED
            return 0
        fi
        n=$((n+1)); sleep 1
    done
    rmdir "$LOCK/stopping" 2>/dev/null || :
    echo G733_RUNTIME_RESTORE_PENDING
    return 1
}
case "${1:-inspect}" in
    inspect)
        for item in hmi libc background rightmenu hook; do
            case "$item" in
                hmi) file=$DIR/cluster-hmi ;; libc) file=/lib64/libc.so.4 ;;
                background) file=$DIR/background.kzb ;; rightmenu) file=$DIR/rightmenu.kzb ;; hook) file=$SO ;;
            esac
            set -- $(cksum "$file")
            [ "$#" -ge 2 ] && [ "$1 $2" = "$(cat "$HERE/$item.cksum")" ] || exit 10
        done
        echo G733_RUNTIME_MATERIALS_MATCH
        exit 0 ;;
    status)
        set -- $(pids)
        [ "$#" -eq 1 ] || exit 1
        if owned "$1"; then echo G733_RUNTIME_OWNED; else echo G733_RUNTIME_STOCK; fi
        exit 0 ;;
    stop) restore_owned "${2:-$(cat "$LOCK/ticket" 2>/dev/null)}"; exit $? ;;
    guard)
        ticket=${2:-}; ready=0; n=0; misses=0
        while [ -r "$LOCK/ticket" ] && [ "$(cat "$LOCK/ticket")" = "$ticket" ]; do
            set -- $(pids)
            if [ "$#" -eq 1 ] && owned "$1" && "$CHECK" hook-health; then ready=1; misses=0
            else
                misses=$((misses+1))
                if [ "$ready" -eq 1 ] && [ "$misses" -ge 4 ]; then restore_owned "$ticket"; exit $?; fi
                if [ "$ready" -eq 0 ] && [ "$n" -ge 10 ]; then restore_owned "$ticket"; exit $?; fi
            fi
            n=$((n+1)); sleep 1
        done
        exit 0 ;;
    activate) [ "${2:-}" = --authorized-runtime ] || exit 2 ;;
    *) exit 2 ;;
esac
sh "$SELF" inspect || exit 10
[ -x "$CHECK" ] || exit 10
background_mode=$(cat "$HERE/background.mode" 2>/dev/null)
case "$background_mode" in replace|observe) ;; *) exit 10 ;; esac
set -- $(pids)
[ "$#" -eq 1 ] && ! owned "$1" || exit 11
original=$1
mkdir "$LOCK" || exit 12
ticket="$$-$(date +%Y%m%d%H%M%S)"
echo "$ticket" > "$LOCK/ticket" || exit 13
# Start independent recovery before touching the current process.
(trap '' HUP; exec sh "$SELF" guard "$ticket") </dev/null >"$HERE/runtime-guard.log" 2>&1 &
rm -f /otaupdate/autoservice-media/hook-status.bin
kill -TERM "$original" || { restore_owned "$ticket"; exit 14; }
n=0
while kill -0 "$original" 2>/dev/null; do
    [ "$n" -lt 400 ] || { restore_owned "$ticket"; exit 15; }
    sleep 0.01
    n=$((n+1))
done
# Same process-exit handoff and APS/library environment already exercised by the parked runner.
[ -r "$LOCK/ticket" ] && [ "$(cat "$LOCK/ticket")" = "$ticket" ] && [ ! -d "$LOCK/stopping" ] || exit 15
(cd "$DIR" || exit 18; trap '' HUP; exec on -p 15 -X aps=System env LD_LIBRARY_PATH=/apps/cluster/FX11_HEV/lib:/apps/cluster/common/lib:/lib64 LD_PRELOAD="$SO" GALAXY_G733_BACKGROUND_PROBE="$background_mode" GALAXY_G733_SCENE_MODE=runtime ./cluster-hmi) </dev/null >"$HERE/runtime.log" 2>&1 &
n=0
while [ "$n" -lt 8 ]; do
    set -- $(pids)
    [ "$#" -le 1 ] || { restore_owned "$ticket"; exit 17; }
    if [ "$#" -eq 1 ] && owned "$1" && "$CHECK" hook-health; then
        echo G733_RUNTIME_UI_HEARTBEAT "$ticket"
        exit 0
    fi
    echo G733_RUNTIME_STARTING
    n=$((n+1)); sleep 1
done
restore_owned "$ticket"
exit 16
