#!/usr/bin/env python3
"""只读 AutoService/AutoRecord 频率改动性能对比；不改订阅、不安装、不访问车辆样本。"""
import argparse
import json
import math
import re
import time
from pathlib import Path
from remote_car import Car

PACKAGES = ('com.ecarx.autoservice', 'com.galaxy.autorecord')

def command():
    parts = ['date +%s', 'cat /proc/uptime']
    for package in PACKAGES:
        parts.append('echo PACKAGE=' + package)
        parts.append('p=$(pidof ' + package + '); if [ -n "$p" ]; then cat /proc/$p/stat; cat /proc/$p/status | grep -E "VmRSS|Threads"; echo FD=$(ls /proc/$p/fd | wc -l); dumpsys meminfo "$p" | grep "TOTAL PSS"; fi')
    parts.append('dumpsys activity service com.ecarx.autoservice/.AutoService | grep -E "^[[:space:]]*(vehicleStats|vehicleDemand|feeds|AUTOSERVICE_DIAGNOSTIC)="')
    parts.append('dumpsys activity service com.galaxy.autorecord/.runtime.RecordSessionService')
    parts.append('p=$(pidof com.galaxy.autorecord); if [ -n "$p" ]; then logcat -d -t 120 --pid="$p" -v brief | grep "AutoRecordFeed" | tail -3; fi')
    return '\n'.join(parts)

def parse(output):
    row = {'packages': {}}
    for block in output.split('PACKAGE=')[1:]:
        package, _, body = block.partition('\n')
        values = {}
        stat = next((line for line in body.splitlines() if re.match(r'^\d+ \(', line)), '')
        if stat:
            pid, _, tail = stat.partition(') ')
            fields = tail.split()
            values.update(pid=int(pid.split()[0]), ticks=int(fields[11]) + int(fields[12]), startTicks=int(fields[19]))
        for name, pattern in [('rssKb', r'VmRSS:\s+(\d+)'), ('threads', r'Threads:\s+(\d+)'), ('fd', r'FD=\s*(\d+)'), ('pssKb', r'TOTAL PSS:\s+(\d+)')]:
            match = re.search(pattern, body)
            if match: values[name] = int(match.group(1))
        row['packages'][package] = values
    match = re.search(r'^\s*vehicleStats=(.*)$', output, re.M)
    if match:
        row['vehicleStats'] = {key: int(value) for key, value in re.findall(r'(ticks|reads|bundles|feeds|period|cycleTotalMs|overruns)=(\d+)', match.group(1))}
        row['vehicleStatsText'] = match.group(1)
    row['recording'] = re.search(r'state=([^\n]+)', output).group(1) if 'state=' in output else 'unavailable'
    row['feedTiming'] = [{'packets':int(a),'windowMs':int(b),'maxCollectionToCallbackMs':int(c),'maxCallbackWorkMs':int(d)} for a,b,c,d in re.findall(r'推送 (\d+)包/(\d+)ms 采集至回调最大(\d+)ms 回调处理最大(\d+)ms',output)]
    row['demand'] = re.search(r'^\s*vehicleDemand=(.*)$', output, re.M).group(1) if 'vehicleDemand=' in output else ''
    lines = output.splitlines()
    if len(lines) > 1:
        try: row['uptime'] = float(lines[1].split()[0])
        except (ValueError, IndexError): pass
    return row

def percentile(values, fraction):
    values = sorted(values)
    return values[min(len(values)-1, max(0, math.ceil(len(values)*fraction)-1))] if values else None

def summary(rows):
    result = {'samples': len(rows), 'packages': {}}
    for package in PACKAGES:
        cpu = []
        for before, after in zip(rows, rows[1:]):
            a, b = before['packages'].get(package, {}), after['packages'].get(package, {})
            elapsed = after.get('uptime', 0) - before.get('uptime', 0)
            if elapsed > 0 and a.get('pid') == b.get('pid') and a.get('startTicks') == b.get('startTicks') and 'ticks' in a and 'ticks' in b:
                cpu.append((b['ticks'] - a['ticks']) / elapsed)  # Android USER_HZ=100; one core=100%
        metrics = {'cpuPercentOneCoreMedian': percentile(cpu, .5), 'cpuPercentOneCoreP95': percentile(cpu, .95)}
        for metric in ('pssKb', 'rssKb', 'threads', 'fd'):
            values = [r['packages'].get(package, {}).get(metric) for r in rows]
            values = [v for v in values if v is not None]
            metrics[metric] = {'first': values[0] if values else None, 'last': values[-1] if values else None, 'p50': percentile(values, .5), 'max': max(values) if values else None}
        result['packages'][package] = metrics
    if len(rows) > 1:
        a,b=rows[0],rows[-1]
        result['processesStable']=all(all('pid' in r['packages'].get(p,{}) for r in rows) and len({(r['packages'][p]['pid'],r['packages'][p]['startTicks']) for r in rows})==1 for p in PACKAGES)
        elapsed=b.get('uptime',0)-a.get('uptime',0)
        result['durationSeconds']=elapsed
        result['demandUnchanged']=len({r['demand'] for r in rows})==1
        result['collectorPerSecond']={k:(b.get('vehicleStats',{}).get(k,0)-v)/elapsed for k,v in a.get('vehicleStats',{}).items() if k in ('ticks','reads','bundles','feeds','overruns') and elapsed>0 and result['processesStable']}
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session',type=Path,required=True)
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--label',required=True)
    parser.add_argument('--seconds',type=int,default=180)
    parser.add_argument('--interval',type=int,default=5)
    args=parser.parse_args()
    if args.seconds<10 or args.interval<2: parser.error('seconds >=10 and interval >=2 required')
    args.out.mkdir(parents=True,exist_ok=True)
    car=Car(json.loads(args.session.read_text()));rows=[];deadline=time.monotonic()+args.seconds
    while True:
        response=car.job(dict(kind='shell',transport='ROOT',command=command()),wait=60)
        if response.get('timedOut') or response.get('truncated') or response.get('exitCode')!=0: raise RuntimeError('incomplete remote sample')
        row=parse(response.get('output',''));row['label']=args.label
        rows.append(row)
        (args.out/'samples.jsonl').write_text(''.join(json.dumps(x,ensure_ascii=False)+'\n' for x in rows))
        (args.out/'summary.json').write_text(json.dumps(summary(rows),ensure_ascii=False,indent=2))
        if time.monotonic()>=deadline:break
        time.sleep(min(args.interval,max(0,deadline-time.monotonic())))
    print(json.dumps(summary(rows),ensure_ascii=False,indent=2))
if __name__=='__main__':main()
