#!/usr/bin/env python3
"""比较两次只读频率采样摘要；过程重启或订阅集合不同则明确标记不可直接归因。"""
import argparse
import json
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('before',type=Path)
parser.add_argument('after',type=Path)
args=parser.parse_args()
def read(path):
    summary=json.loads((path/'summary.json').read_text())
    rows=[json.loads(x) for x in (path/'samples.jsonl').read_text().splitlines() if x.strip()]
    keys=set()
    for row in rows:
        for pair in row.get('demand','').split(','):
            name=pair.strip().split('@')[0]
            if name:keys.add(name)
    return summary,rows,keys
before,br,bk=read(args.before)
after,ar,ak=read(args.after)
result={'sameSubscribedKeys':bk==ak,'beforeDemandStable':before.get('demandUnchanged'),
        'afterDemandStable':after.get('demandUnchanged'),'beforeProcessesStable':before.get('processesStable'),'afterProcessesStable':after.get('processesStable'),'packages':{},'collectorBefore':before.get('collectorPerSecond'),
        'collectorAfter':after.get('collectorPerSecond')}
for package,base in before.get('packages',{}).items():
    new=after.get('packages',{}).get(package,{})
    delta={}
    for name in ('cpuPercentOneCoreMedian','cpuPercentOneCoreP95'):
        a,b=base.get(name),new.get(name)
        delta[name]={'before':a,'after':b,'deltaPercentagePoints':b-a if a is not None and b is not None else None}
    for name in ('pssKb','rssKb','threads','fd'):
        a,b=base.get(name,{}).get('p50'),new.get(name,{}).get('p50')
        delta[name]={'beforeMedian':a,'afterMedian':b,'delta':b-a if a is not None and b is not None else None}
    result['packages'][package]=delta
result['note']='同车型/固件、同订阅键、同录制与预览条件下比较；计划周期不是实测端到端延迟，需同时核对侧录源时间和回调延迟。'
print(json.dumps(result,ensure_ascii=False,indent=2))
