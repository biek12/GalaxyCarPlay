#!/usr/bin/env python3
"""Exercise the actual product shell's ownership/restore and guard decisions offline."""
import subprocess,tempfile
from pathlib import Path
STUBS=r'''
pids() { cat "$HERE/pids"; }
owned() { [ "$1" = 51 ] && grep -qx 51 "$HERE/pids"; }
pidin() { grep -qx "$2" "$HERE/pids" || return 1; echo PIE; }
kill() {
    echo "$1 $2" >>"$HERE/kills"
    [ "$2" = 51 ] || return 1
    if [ "$1" = -KILL ] || [ "$(cat "$HERE/ignore-term")" = 0 ]; then
        awk '$1 != 51' "$HERE/pids" >"$HERE/remain"; mv "$HERE/remain" "$HERE/pids"
    fi
}
sleep() { :; }
'''
def main():
    source=Path(__file__).with_name('g733_hook_runtime.sh').read_text()
    dispatch='case "${1:-inspect}" in'
    with tempfile.TemporaryDirectory(prefix='cluster-lifecycle-') as folder:
        root=Path(folder);runner=root/'g733_hook_runtime.sh'
        runner.write_text(source.replace('HERE=/var/data/autoservice-cluster',f'HERE={root}').replace(dispatch,STUBS+'\n'+dispatch))
        health=root/'health';health.write_text('#!/bin/sh\nexit 1\n');health.chmod(0o700)
        def run(args,pids,ignore=False):
            import shutil
            shutil.rmtree(root/'runtime.lock',ignore_errors=True)
            (root/'runtime.lock').mkdir();(root/'runtime.lock/ticket').write_text('ticket\n')
            (root/'pids').write_text(''.join(f'{p}\n' for p in pids));(root/'kills').write_text('');(root/'ignore-term').write_text(str(int(ignore)))
            result=subprocess.run(['sh',str(runner),*args],capture_output=True,text=True,timeout=10)
            return result,(root/'kills').read_text().splitlines()
        r,k=run(['activate'],[50]);assert r.returncode==2 and not k
        r,k=run(['stop','old-ticket'],[50,51]);assert r.returncode==0 and not k
        r,k=run(['stop','ticket'],[52,51]);assert r.returncode==0 and k==['-TERM 51']
        r,k=run(['stop','ticket'],[52,51],True);assert r.returncode==0 and k==['-TERM 51','-TERM 51','-KILL 51']
        r,k=run(['stop','ticket'],[50]);assert r.returncode==0 and not k
        r,k=run(['stop','ticket'],[51]);assert r.returncode==1 and 'RESTORE_PENDING' in r.stdout
        # No healthy UI ever arrives: independent guard expires and restores only its owner.
        r,k=run(['guard','ticket'],[52,51]);assert r.returncode==0 and k==['-TERM 51']
        # One good UI check followed by permanent failure must also trigger restoration.
        health.write_text(f'#!/bin/sh\nif [ ! -f "{root}/health-seen" ]; then touch "{root}/health-seen"; exit 0; fi\nexit 1\n')
        r,k=run(['guard','ticket'],[51,52]);assert r.returncode==0 and k==['-TERM 51']
        assert '-TERM 52' not in k
    print('Eight product Hook ownership/guard/restore cases passed')
if __name__=='__main__':main()
