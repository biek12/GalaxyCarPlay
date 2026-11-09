#!/usr/bin/env python3
"""同签名自更新：先传包，再安排遵守automatic的一次性Root补拉起，安装只提交一次。"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import time
from remote_car import Car, resolve_session


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk',type=Path)
    parser.add_argument('--session',type=Path)
    args=parser.parse_args()
    car=Car(json.loads(resolve_session(args.session).read_text()))
    payload=args.apk.read_bytes();digest=hashlib.sha256(payload).hexdigest();artifact=digest[:32]
    car.call('/bridge/artifact'+car.query+'&id='+artifact+'&sha256='+digest,method='PUT',data=payload)
    script='''#!/system/bin/sh
sleep 4
attempt=0
while [ "$attempt" -lt 18 ]; do
 attempt=$((attempt+1))
 f=/data/user/0/com.ecarx.autoservice/shared_prefs/platform.xml
 if grep -q 'name="automatic" value="false"' "$f"; then echo user_disabled; exit 0; fi
 p=$(pm path com.ecarx.autoservice | sed -n 's/^package://p')
 actual=$(sha256sum "$p" 2>/dev/null | cut -d' ' -f1)
 if [ "$actual" = "EXPECTED" ]; then
  if dumpsys activity services com.ecarx.autoservice/.AutoService | grep -q 'isForeground=true'; then echo foreground_verified; exit 0; fi
  echo bounded_start
  am start-foreground-service -n com.ecarx.autoservice/.AutoService -a recovery
 fi
 sleep 5
done
echo recovery_window_finished
'''.replace('EXPECTED',digest)
    path='/data/local/tmp/galaxy-web-update-recover.sh';log='/data/local/tmp/galaxy-web-update-recover.log'
    encoded=base64.b64encode(script.encode()).decode()
    result=car.job(dict(kind='shell',transport='ROOT',command="umask 077; printf '%s' '"+encoded+"' | base64 -d > "+path+"; chmod 700 "+path+"; /system/bin/setsid /system/bin/sh "+path+" < /dev/null > "+log+" 2>&1 &"))
    if result.get('exitCode')!=0:raise RuntimeError('recovery_not_armed')
    try:
        response=car.job(dict(kind='install',artifact=artifact,package='com.ecarx.autoservice',sha256=digest),wait=50)
        print(json.dumps({'installResponse':response.get('error','received')}))
    except TimeoutError:
        print('Self-update interrupted response; not resubmitting installation')
    for attempt in range(6):
        try:
            result=car.job(dict(kind='shell',transport='ROOT',command='p=$(pm path com.ecarx.autoservice | cut -d: -f2); sha256sum "$p"; cat '+log),wait=35)
            if digest in result.get('output','').split():
                print(json.dumps({'package':'com.ecarx.autoservice','sha256':digest,'verified':True,'recovery':result.get('output','').splitlines()[-1]}))
                return
        except TimeoutError:pass
        time.sleep(2)
    raise RuntimeError('self_update_not_verified_do_not_repeat_install')
if __name__=='__main__':main()
