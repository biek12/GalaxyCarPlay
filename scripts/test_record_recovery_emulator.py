#!/usr/bin/env python3
"""固定隔离AVD：真实录制退出页面、native/Java进程死亡自拉起、主动停止与关闭恢复后的不重启。"""
import json,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ADB='/Users/anjon/android/sdk/platform-tools/adb'
RUN=ROOT/'.work/record-recovery';RUN.mkdir(parents=True,exist_ok=True)
def adb(*args,check=True):
 r=subprocess.run([ADB,'-s','emulator-5568',*args],capture_output=True,text=True,check=check)
 return r.stdout
assert adb('shell','getprop','ro.kernel.qemu').strip()=='1'
assert adb('emu','avd','name').splitlines()[0].strip()=='AutoRecord_Offline'
adb('shell','appops','set','com.galaxy.autorecord','SYSTEM_ALERT_WINDOW','allow')
def status():
 try:return json.loads(adb('exec-out','run-as','com.galaxy.autorecord','cat','cache/record-session-test-status.json'))
 except (ValueError,subprocess.CalledProcessError):return {}
def control(action):return adb('shell','am','broadcast','-n','com.galaxy.autorecord/.runtime.RecordEmulatorControlReceiver','-a','record.test.'+action)
def wait_active(old_pid=None,timeout=100):
 end=time.monotonic()+timeout;seen=[]
 while time.monotonic()<end:
  s=status()
  if s and (not seen or s!=seen[-1]):seen.append(s)
  if str(s.get('pid','')) in adb('shell','pidof','com.galaxy.autorecord',check=False).split() and s.get('state')=='RECORDING' and s.get('wanted') and s.get('bytes',0)>0 and s.get('segmentIndex',0)>=2 and (old_pid is None or s['pid']!=old_pid):
   return s,seen
  time.sleep(.5)
 raise AssertionError(f'recording did not resume: {seen[-5:]}')
start=adb('shell','am','instrument','-w','-e','action','recovery-session','com.galaxy.autorecord.test/com.galaxy.autorecord.RecordOfflineInstrumentation')
(RUN/'start.txt').write_text(start)
assert 'INSTRUMENTATION_RESULT: result=PASS' in start,start
initial,_=wait_active();results={'activityDestroyedRecordingContinues':True,'initialPid':initial['pid']}
for kind in ['NATIVE','JAVA']:
 before,_=wait_active();at=time.monotonic();control('CRASH_'+kind)
 after,seen=wait_active(before['pid'])
 results[kind.lower()]={'oldPid':before['pid'],'newPid':after['pid'],'resumedInSeconds':round(time.monotonic()-at,2),'actualBytes':after['bytes'],'segmentIndex':after['segmentIndex']}
 (RUN/(kind.lower()+'-status.json')).write_text(json.dumps(seen,indent=2))
 print(kind+' recovery PASS',json.dumps(results[kind.lower()]),flush=True)
control('STOP')
end=time.monotonic()+20
while time.monotonic()<end:
 s=status()
 if s.get('state')=='IDLE' and not s.get('wanted'):break
 time.sleep(.25)
else:raise AssertionError('explicit stop did not finish: '+str(status()))
control('CRASH_NATIVE')
time.sleep(40)
assert not adb('shell','pidof','com.galaxy.autorecord',check=False).strip(),'explicitly stopped app restarted'
results['explicitStopThenNativeCrashDidNotRestart']=True
# 新一轮明确启动后关闭恢复：正常录制继续，崩溃后不能被旧sticky/闹钟再次启动。
start=adb('shell','am','instrument','-w','-e','action','recovery-session','com.galaxy.autorecord.test/com.galaxy.autorecord.RecordOfflineInstrumentation')
assert 'INSTRUMENTATION_RESULT: result=PASS' in start,start
before,_=wait_active();control('DISABLE_RECOVERY');time.sleep(2)
s=status();assert s.get('state')=='RECORDING' and s['bytes']>before['bytes']
control('CRASH_NATIVE');time.sleep(40)
assert not adb('shell','pidof','com.galaxy.autorecord',check=False).strip(),'disabled recovery app restarted'
results['recoveryDisabledContinuedRecordingThenDidNotRestart']=True
results['scope']='single Camera2/software AVC on fixed isolated Android11 AVD; no vehicle actions'
(RUN/'summary.json').write_text(json.dumps(results,indent=2))
print(json.dumps(results,indent=2),flush=True)
