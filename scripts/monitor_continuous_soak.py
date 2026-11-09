#!/usr/bin/env python3
"""固定隔离AVD的只读长录指标快照，不接受车辆地址/serial。"""
import json,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
adb='/Users/anjon/android/sdk/platform-tools/adb'
def run(*args):
 return subprocess.check_output([adb,'-s','emulator-5568',*args],timeout=10,text=True).strip()
assert run('shell','getprop','ro.kernel.qemu')=='1'
assert run('emu','avd','name').splitlines()[0]=='AutoRecord_Offline'
paths=run('shell','run-as','com.galaxy.autorecord','find','cache','-name','resources.jsonl').splitlines()
paths=[p for p in paths if p.startswith('cache/continuous-soak-')]
if not paths:
 print(json.dumps({'state':'no_soak_samples'}));raise SystemExit()
path=paths[-1]
rows=[json.loads(line) for line in run('exec-out','run-as','com.galaxy.autorecord','cat',path).splitlines() if line.strip()]
(root/'.work/continuous-soak/live-resources.jsonl').write_text(''.join(json.dumps(row)+'\n' for row in rows))
if not rows:
 print(json.dumps({'state':'no_soak_samples_yet'}));raise SystemExit()
a,b=rows[0],rows[-1]
summary=dict(b)
if len(rows)>1:
 summary['processCpuPercent']=round(100*(b['cpuMs']-a['cpuMs'])/(b['atMs']-a['atMs']),1)
 summary['encodedMiB']=round(b['encodedBytes']/1024**2,1)
 summary['diskMiB']=round(b['diskBytes']/1024**2,1)
 summary['pssMiB']=round(b['pssKb']/1024,1)
 summary['metrics']=len(rows)
print(json.dumps(summary,ensure_ascii=False))
# 编码器可能运行在独立media进程；另存系统CPU快照，避免把App CPU冒充完整编码CPU。
cpu=run('shell','dumpsys','cpuinfo')
(root/'.work/continuous-soak/system-cpu.txt').write_text(cpu)
for line in cpu.splitlines():
 if any(name in line for name in ['com.galaxy.autorecord','media.codec','media.swcodec','media.omx','TOTAL']):print(line)

