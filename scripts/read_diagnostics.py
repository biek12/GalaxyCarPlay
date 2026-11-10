#!/usr/bin/env python3
"""读取自有服务器最近100条脱敏诊断；登记凭据只从私有文件读取，不输出。"""
import argparse,json,urllib.request,urllib.parse,ssl,os,sys
from remote_car import NoRedirect
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--origin',default=os.environ.get('GALAXY_ORIGIN', 'https://bridge.example.invalid/galaxy'))
p.add_argument('--enrollment-file',type=Path,required=True)
p.add_argument('--device-id-file',type=Path,required=True)
p.add_argument('--app',default='')
p.add_argument('--kind',default='')
a=p.parse_args()
query=urllib.parse.urlencode(dict(device=a.device_id_file.read_text().strip(),app=a.app,kind=a.kind))
req=urllib.request.Request(a.origin+'/telemetry/events?'+query,headers={'X-Galaxy-Enrollment':a.enrollment_file.read_text().strip()})
ca=os.environ.get('GALAXY_CA_FILE') or ('/etc/ssl/cert.pem' if sys.platform=='darwin' else None)
opener=urllib.request.build_opener(urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=ca)),NoRedirect())
with opener.open(req,timeout=15) as r:
 data=json.load(r)
for event in data.get('events',[]):
 print(json.dumps(event,ensure_ascii=False,indent=2))
