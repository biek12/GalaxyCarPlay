#!/usr/bin/env python3
"""对已连接设备只读采样仪表标定字段；不安装、不授权、不改变巡航/回收/方控状态。"""
import argparse
from datetime import datetime
from zoneinfo import ZoneInfo
import json
import math
from pathlib import Path
import subprocess
import time

FIELDS = {'gear','driveMode','displaySpeed','cruiseState','cruiseSetSpeed','regenLevel','electricPowerRaw','electricPowerStateRaw','drivePowerKw','regenPowerKw','tractionPowerKw',
          'fuelRange','evRange','fuel','soc','fuelConsumption','electricConsumption','outsideTemperature',
          'tire.frontLeft','tire.frontRight','tire.rearLeft','tire.rearRight'}
STATES = {'off','standby','active','low','medium','high','auto','P','R','N','D','纯电','增程','性能','智能'}

def parse(raw):
    for line in raw.splitlines():
        if not line.strip().startswith('CLUSTER_FIELDS='): continue
        data=json.loads(line.strip().removeprefix('CLUSTER_FIELDS='))
        if data.get('schema') != 1: raise ValueError('仪表标定契约版本不符')
        fields={}
        for key,row in data.get('fields',{}).items():
            if key not in FIELDS or not isinstance(row,dict): continue
            value=row.get('value')
            if not ((type(value) in (int,float) and math.isfinite(value)) or (isinstance(value,str) and value in STATES)):
                value=None
            status=row.get('status')
            fields[key]={'value':value,'status':status if status in {'VALID','STALE','UNAVAILABLE','DENIED','UNSUPPORTED','ERROR'} else 'UNAVAILABLE',
                         'unit':row.get('unit') if row.get('unit') in {'kW','raw','km/h','km','%','°C','kPa','state','L/100km','kWh/100km'} else None,
                         'ageMillis':row.get('ageMillis') if type(row.get('ageMillis')) is int else None}
        return {'fields':fields,'menuSequence':data.get('menuSequence') if type(data.get('menuSequence')) is int else None}
    raise ValueError('没有仪表标定数据：请核对AutoService为本轮版本且已经运行；脚本不会自动启动/授权')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',default='adb');parser.add_argument('--serial',required=True)
    parser.add_argument('--label',required=True,choices=['parked','cruise-off','cruise-standby','cruise-active','regen-low','regen-medium','regen-high','regen-auto','accelerating','coasting','recovering'])
    parser.add_argument('--duration',type=int,default=30);parser.add_argument('--interval',type=float,default=.5)
    parser.add_argument('--output',required=True,type=Path);args=parser.parse_args()
    if not 1<=args.duration<=300 or not .2<=args.interval<=5: parser.error('duration须为1..300秒，interval须为0.2..5秒')
    report={'recorded_at':datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),'label':args.label,
            'read_only':True,'units_and_regen_direction_verified':False,'samples':[],'passed':False}
    start=time.monotonic()
    try:
        while time.monotonic()-start<args.duration:
            result=subprocess.run([args.adb,'-s',args.serial,'shell','dumpsys','activity','service','com.ecarx.autoservice/.AutoService','--cluster-fields'],capture_output=True,text=True,timeout=15)
            if result.returncode: raise ValueError('只读标定采样失败；不保存原始ADB输出')
            sample=parse(result.stdout);sample['elapsedSeconds']=round(time.monotonic()-start,3)
            report['samples'].append(sample)
            time.sleep(min(args.interval,max(0,args.duration-(time.monotonic()-start))))
        report['passed']=True
    finally:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2,allow_nan=False)+'\n')
    print(f'已保存{len(report["samples"])}条只读标定样本；不代表单位/回收方向已核实。')

if __name__=='__main__': main()
