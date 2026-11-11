#!/bin/sh
# G733 fixed-target research runner. Inspect is read-only. The bench action is explicit.
set -u
PATH=/proc/boot:/bin:/usr/bin:/sbin:/usr/sbin
export PATH
HERE=/var/data/galaxy-background-field
DIR=/apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI
HMI=$DIR/cluster-hmi
HOOK=$HERE/libgalaxy_g733_background_probe.so
SELF=$HERE/g733_scene_trial.sh
LOCK=$HERE/scene-trial.lock

sum() {
    result=$(cksum "$1") || return 1
    set -- $result
    echo "$1 $2"
}
inspect() {
    case "$(sum "$HMI")" in
        '2593595783 13980616'|'649662504 13980616') ;;
        *) return 1 ;;
    esac
    [ "$(sum /lib64/libc.so.4)" = '2258488110 663145' ] &&
        [ "$(sum "$DIR/background.kzb")" = '2695975704 4177055' ] &&
        [ -r "$HOOK" ] && [ -r "$HERE/hook.cksum" ] &&
        [ "$(sum "$HOOK")" = "$(cat "$HERE/hook.cksum")" ]
}
hmi_pids() {
    pidin -F '%a %A' |
        awk '$1+0>1 && ($2=="./cluster-hmi" || $2=="cluster-hmi" ||
            $2=="/apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/cluster-hmi") {print $1}'
}
libraries() { pidin -p "$1" libs 2>/dev/null; }
has_hook() {
    libraries "$1" | grep -F 'libgalaxy_g733_background_probe.so' >/dev/null
}
stock() {
    stock_libs=$(libraries "$1") || return 1
    echo "$stock_libs" | grep PIE >/dev/null || return 1
    if echo "$stock_libs" | grep -F 'libgalaxy_g733_background_probe.so' >/dev/null; then
        return 1
    fi
    return 0
}

restore() {
    restore_ticket=$1
    [ -n "$restore_ticket" ] && [ -r "$LOCK/ticket" ] &&
        [ "$(cat "$LOCK/ticket")" = "$restore_ticket" ] || return 0
    mkdir "$LOCK/restoring" 2>/dev/null || return 0
    trap 'rm -rf "$LOCK/restoring"' 0
    # Terminate only a process with the unique trial SO mapped. Leave a racing OEM instance alone.
    n=0
    while [ "$n" -lt 15 ]; do
        set -- $(hmi_pids)
        for pid do
            if has_hook "$pid"; then
                if [ "$n" -lt 2 ]; then
                    kill -TERM "$pid" 2>/dev/null || :
                else
                    kill -KILL "$pid" 2>/dev/null || :
                fi
            fi
        done
        if [ "$#" -eq 1 ] && stock "$1" && inspect; then
            if [ "$1" = "$(cat "$LOCK/original-pid")" ]; then
                echo G733_ORIGINAL_PROCESS_RETAINED
            else
                echo G733_ORIGINAL_PROCESS_RESTORED
            fi
            rm -rf "$LOCK"
            trap - 0
            return 0
        fi
        sleep 1
        n=$((n+1))
    done
    echo G733_RESTORE_PENDING_OEM_MONITOR
    return 1
}

case "${1:-inspect}" in
    inspect)
        inspect && echo G733_MATERIALS_MATCH
        exit $?
        ;;
    restore)
        restore "${2:-}"
        exit $?
        ;;
    activate)
        [ "${2:-}" = --authorized-bench ] || exit 2
        mode=${3:-}
        case "$mode" in scan|background|sr|both|image|music) ;; *) exit 2 ;; esac
        ;;
    *) exit 2 ;;
esac

inspect || exit 10
if [ "$mode" = music ]; then
    [ "$(sum "$DIR/rightmenu.kzb")" = '4206804000 2108270' ] || exit 10
fi
probe_mode=observe
scene_mode=$mode
if [ "$mode" = image ]; then
    [ -r "$HERE/background-probe.kzb" ] && [ -r "$HERE/candidate.cksum" ] &&
        [ "$(sum "$HERE/background-probe.kzb")" = "$(cat "$HERE/candidate.cksum")" ] || exit 10
    probe_mode=replace
    scene_mode=
fi
set -- $(hmi_pids)
[ "$#" -eq 1 ] && stock "$1" || exit 11
original_pid=$1
cd "$DIR" || exit 12
mkdir "$LOCK" || exit 13
ticket="$$-$(date +%Y%m%d%H%M%S)"
echo "$ticket" >"$LOCK/ticket" || exit 14
echo "$original_pid" >"$LOCK/original-pid" || exit 14
log=$HERE/scene-"$ticket".log
(trap '' HUP; sleep 20; sh "$SELF" restore "$ticket") \
    </dev/null >"$HERE/watchdog-$ticket.log" 2>&1 &

fail() {
    echo "$1"
    sh "$SELF" restore "$ticket"
    exit 15
}
[ "$(hmi_pids)" = "$original_pid" ] && stock "$original_pid" ||
    fail G733_ORIGINAL_PROCESS_CHANGED
kill -TERM "$original_pid" || fail G733_OLD_PROCESS_STOP_FAILED
n=0
while kill -0 "$original_pid" 2>/dev/null; do
    [ "$n" -lt 400 ] || fail G733_OLD_PROCESS_EXIT_TIMEOUT
    sleep 0.01
    n=$((n+1))
done
# Launch immediately after the old PID exits. A full pidin walk here widens
# the OEM restart race. The readiness loop below rejects concurrent instances.
[ -r "$LOCK/ticket" ] && [ "$(cat "$LOCK/ticket")" = "$ticket" ] &&
    [ ! -d "$LOCK/restoring" ] || exit 15

(trap '' HUP; on -p 15 -X aps=System env \
    LD_LIBRARY_PATH=/apps/cluster/FX11_HEV/lib:/apps/cluster/common/lib:/lib64 \
    LD_PRELOAD="$HOOK" GALAXY_G733_BACKGROUND_PROBE="$probe_mode" \
    GALAXY_G733_SCENE_MODE="$scene_mode" GALAXY_G733_SR_BENCH=1 ./cluster-hmi) \
    </dev/null >"$log" 2>&1 &
n=0
while [ "$n" -lt 10 ]; do
    echo G733_WAITING_FOR_HOOK "$n"
    set -- $(hmi_pids)
    [ "$#" -le 1 ] || fail G733_MULTIPLE_HMI_INSTANCES
    if [ "$#" -eq 1 ] && has_hook "$1"; then
        if [ "$mode" = image ]; then
            if grep '^G733_CANDIDATE_BACKGROUND_OPEN$' "$log" >/dev/null &&
                grep '^G733_EGL_SWAP_AFTER_RESOURCE_OPEN$' "$log" >/dev/null; then
                echo G733_IMAGE_READY_FOR_VISUAL_CHECK "$1" "$log"
                exit 0
            fi
            if grep '^G733_CANDIDATE_REJECTED$' "$log" >/dev/null; then
                fail G733_IMAGE_REJECTED
            fi
        elif [ "$mode" = music ]; then
            if grep '^G733_MUSIC_APPLIED$' "$log" >/dev/null &&
                grep '^G733_MUSIC_EGL_SWAP$' "$log" >/dev/null &&
                ! grep '^G733_MUSIC_RESTORED$' "$log" >/dev/null; then
                echo G733_MUSIC_READY_FOR_VISUAL_CHECK "$1" "$log"
                exit 0
            fi
        elif [ "$mode" = scan ] && grep -F 'G733_SCENE_SLOTS_INSTALLED' "$log" >/dev/null; then
            if grep '^G733_SCENE_SCAN ' "$log" >/dev/null; then
                echo G733_SCAN_READY "$1" "$log"
                exit 0
            fi
        elif grep -F 'G733_SCENE_SLOTS_INSTALLED' "$log" >/dev/null &&
            grep '^G733_SCENE_APPLIED ' "$log" >/dev/null &&
            grep '^G733_SCENE_EGL_SWAP$' "$log" >/dev/null &&
            ! grep '^G733_SCENE_RESTORE' "$log" >/dev/null; then
            echo G733_HOOK_READY_FOR_VISUAL_CHECK "$1" "$log"
            exit 0
        fi
        if grep -E '^G733_SCENE_(APPLY_FAILED|OEM_SOURCE_PRESENT|NO_VISIBLE_MATCH|ROOT_UNAVAILABLE)$' "$log" >/dev/null; then
            fail G733_HOOK_REJECTED
        fi
    fi
    sleep 1
    n=$((n+1))
done
fail G733_HOOK_NOT_CONFIRMED
