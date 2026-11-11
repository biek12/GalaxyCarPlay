#!/usr/bin/env python3
"""Run the actual runner's recovery decisions against synthetic process state."""
from pathlib import Path
import subprocess
import tempfile


STUBS = r"""
inspect() { return 0; }
hmi_pids() { cat "$HERE/pids"; }
libraries() {
    grep -qx "$1" "$HERE/pids" || return 1
    echo PIE
    if [ "$1" = 51 ]; then echo libgalaxy_g733_background_probe.so; fi
}
kill() {
    echo "$1 $2" >>"$HERE/kills"
    [ "$2" = 51 ] || return 1
    if [ "$1" = -KILL ] || [ "$(cat "$HERE/ignore-term")" = 0 ]; then
        awk '$1 != 51' "$HERE/pids" >"$HERE/remaining"
        mv "$HERE/remaining" "$HERE/pids"
    fi
}
sleep() { :; }
"""


def main():
    source = Path(__file__).with_name("g733_scene_trial.sh").read_text(encoding="utf-8")
    dispatch = 'case "' + chr(36) + '{1:-inspect}" in'
    if source.count(dispatch) != 1:
        raise ValueError("Cannot replace process access before runner dispatch")
    with tempfile.TemporaryDirectory(prefix="g733-restore-") as directory:
        base = Path(directory)
        runner = base / "trial.sh"
        runner.write_text(source.replace("HERE=/var/data/galaxy-background-field", f"HERE={base}")
                          .replace(dispatch, STUBS + "\n" + dispatch), encoding="utf-8")

        def run(args, pids, ignore_term=False):
            (base / "pids").write_text("".join(f"{p}\n" for p in pids), encoding="ascii")
            (base / "kills").write_text("", encoding="ascii")
            (base / "ignore-term").write_text(str(int(ignore_term)), encoding="ascii")
            lock = base / "scene-trial.lock"
            lock.mkdir(exist_ok=True)
            (lock / "original-pid").write_text("50\n", encoding="ascii")
            (lock / "ticket").write_text("ticket\n", encoding="ascii")
            result = subprocess.run(["sh", str(runner), *args], capture_output=True, text=True)
            return (result.returncode, result.stdout.strip(),
                    (base / "kills").read_text(encoding="ascii").splitlines())

        assert run(["activate"], [50]) == (2, "", [])
        assert run(["activate", "--authorized-bench", "image"], [50]) == (10, "", [])
        assert run(["activate", "--authorized-bench", "music"], [50]) == (10, "", [])
        assert run(["restore", "old-ticket"], [50, 51]) == (0, "", [])
        assert run(["restore", "ticket"], [52, 51]) == (
            0, "G733_ORIGINAL_PROCESS_RESTORED", ["-TERM 51"])
        assert run(["restore", "ticket"], [52, 51], True) == (
            0, "G733_ORIGINAL_PROCESS_RESTORED", ["-TERM 51", "-TERM 51", "-KILL 51"])
        assert run(["restore", "ticket"], [50]) == (0, "G733_ORIGINAL_PROCESS_RETAINED", [])
        assert run(["restore", "ticket"], [51]) == (
            1, "G733_RESTORE_PENDING_OEM_MONITOR", ["-TERM 51"])
    print("Eight mocked QNX controller cases passed")


if __name__ == "__main__":
    main()
