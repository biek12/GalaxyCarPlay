#!/usr/bin/env python3
"""核验本机隔离长录结果、累计帧归属及资源区间；不把模拟器结果外推实车。"""
import json,re,statistics
from pathlib import Path
root=Path(__file__).resolve().parents[1]
run=root/'.work/continuous-soak'
text=(run/'result.txt').read_text()
assert 'INSTRUMENTATION_RESULT: result=PASS' in text,'long test not PASS yet'
line=next(x for x in text.splitlines() if x.startswith('INSTRUMENTATION_RESULT: soak=PASS '))
values={k:int(v) for k,v in re.findall(r'(\w+)=(\d+)',line)}
assert values['seconds']>=1800 and values['quota']==1024**3
assert values['realEncodedBytes']>1536*1024**2 and values['rotations']>=5
assert values['producer']==values['input']==values['output']==values['committed'] and values['backpressure']==0
assert values['retained']<=values['quota'] and values['peakDiskBytes']<=values['quota']
journal=json.loads((run/'continuity-journal.json').read_text())
segments=sorted(journal['segments'].values(),key=lambda r:r['plannedStartUs'])
committed=sum(c['frames'] for s in segments for c in s['channels'])
assert committed==values['output'],f'journal {committed} != encoded output {values["output"]}'
last={};gaps=[];skews=[]
for s in segments:
 for c in s['channels']:
  name=c['direction']
  if name in last:
   gap=c['firstSourcePtsUs']-last[name];assert gap>0;gaps.append(gap)
  last[name]=c['lastSourcePtsUs'];skews.append(c['firstSourcePtsUs']-s['plannedStartUs'])
assert max(gaps,default=0)==values['maxBoundaryGapUs']
assert max(gaps,default=0)<1_000_000,'whole-second source gap observed'
rows=[json.loads(l) for l in (run/'resources.jsonl').read_text().splitlines() if l.strip()]
steady=[r for r in rows if r['atMs']>=60000]
assert all(0<=r['producerFrames']-r['codecInput']<=6 and 0<=r['codecInput']-r['codecOutput']<=8 and r['backpressure']==0 for r in rows)
def describe(key):
 v=[r[key] for r in steady];return {'min':min(v),'median':statistics.median(v),'max':max(v)}
summary={'scope':'isolated emulator, single software AVC, synthetic moving 2x2 blocks; no vehicle or hardware performance acceptance',
 'durationSeconds':values['seconds'],'quotaBytes':values['quota'],'encodedBytes':values['realEncodedBytes'],
 'rotations':values['rotations'],'retainedBytes':values['retained'],'peakDiskBytes':values['peakDiskBytes'],
 'segments':len(segments),'framesProduced':values['producer'],'framesEncodedAndCommitted':committed,'backpressure':values['backpressure'],
 'temporaryInputWaits':values['inputWaits'],'effectiveFps':round(committed/values['seconds'],3),
 'boundaryGapUs':{'min':min(gaps),'median':statistics.median(gaps),'max':max(gaps)},
 'firstFrameOffsetFromPlannedStartUs':{'min':min(skews),'median':statistics.median(skews),'max':max(skews)},
 'steadyPssKiB':describe('pssKb'),'steadyThreads':describe('threads'),'steadyFds':describe('fds'),
 'processCpuPercentOneCore':round(100*(rows[-1]['cpuMs']-rows[0]['cpuMs'])/(rows[-1]['atMs']-rows[0]['atMs']),2),
 'encodedQueuePeakBytes':max(r['queuePeakBytes'] for r in rows),'resourceSamples':len(rows),
 'note':'App CPU excludes media.codec; independent system/top snapshots retained. Source frames are timestamped, no fabricated CFR timestamps.'}
(run/'summary.json').write_text(json.dumps(summary,indent=2,ensure_ascii=False))
print(json.dumps(summary,indent=2,ensure_ascii=False))
