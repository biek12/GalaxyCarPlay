#!/usr/bin/env python3
"""隔离AVD只读收集已提交分段元数据，循环删除前保留累计帧计数；不拉取视频。"""
import io,json,re,subprocess,tarfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
adb='/Users/anjon/android/sdk/platform-tools/adb';target=[adb,'-s','emulator-5568']
def text(*args):return subprocess.check_output(target+list(args),timeout=15,text=True).strip()
assert text('shell','getprop','ro.kernel.qemu')=='1'
assert text('emu','avd','name').splitlines()[0]=='AutoRecord_Offline'
resources=text('shell','run-as','com.galaxy.autorecord','find','cache','-name','resources.jsonl').splitlines()
resources=[p for p in resources if re.fullmatch(r'cache/continuous-soak-\d+/resources.jsonl',p)]
# 选择最新修改的样本文件，跨模拟器冷启动不能按nanoTime数值排序。
latest=max(resources,key=lambda p:int(text('shell','run-as','com.galaxy.autorecord','stat','-c','%Y',p)))
base=latest.rsplit('/',1)[0]
paths=text('shell','run-as','com.galaxy.autorecord','find',base+'/AutoRecord/segments','-name','continuity.json').splitlines()
paths=[p for p in paths if p.startswith(base+'/') and p.endswith('/continuity.json')]
file=root/'.work/continuous-soak/continuity-journal.json'
state=json.loads(file.read_text()) if file.exists() else {}
if state.get('source')!=base:state={'source':base,'segments':{}}
if paths:
 data=subprocess.check_output(target+['exec-out','run-as','com.galaxy.autorecord','tar','-cf','-',*paths],timeout=20)
 with tarfile.open(fileobj=io.BytesIO(data)) as archive:
  for member in archive.getmembers():
   if member.isfile() and member.name.endswith('/continuity.json'):
    record=json.load(archive.extractfile(member))
    if record.get('channels'):state['segments'][member.name.split('/')[-2]]=record
file.write_text(json.dumps(state,indent=2))
rows=sorted(state['segments'].values(),key=lambda r:r['plannedStartUs'])
counts=sum(c['frames'] for r in rows for c in r['channels'])
print(json.dumps({'segmentsCollected':len(rows),'encodedFramesCommitted':counts,'source':base}))
