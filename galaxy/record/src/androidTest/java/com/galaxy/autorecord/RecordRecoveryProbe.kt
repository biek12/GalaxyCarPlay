package com.galaxy.autorecord

import android.app.Instrumentation
import android.content.Intent
import android.os.*
import com.galaxy.autorecord.core.*
import com.galaxy.autorecord.runtime.RecordSessionHub

/** 固定模拟器：真实Camera2录制→销毁页面→后台持续写盘。进程外脚本随后注入崩溃验证自拉起。 */
internal object RecordRecoveryProbe {
 fun start(instrumentation:Instrumentation,report:Bundle) {
  val context=instrumentation.targetContext
  check(BuildConfig.EMULATOR_CAMERA && Build.HARDWARE in setOf("ranchu","goldfish"))
  check(context.getSharedPreferences("emulator.record.test",0).edit().putBoolean("enabled",true).commit())
  check(context.getSharedPreferences("record.ui",0).edit().putBoolean("record.ui.onboarded",true).commit())
  val settings=RecordSettings(segmentSeconds=4,requestedHeight=720,fps=15,encoderImplementation=RecordSettings.IMPL_SOFTWARE,
   minFreeBytes=1024*1024,quotaBytes=1024L*1024*1024,background=true,recoverAfterCrash=true)
  PrefsSettingsStore(context).save(settings)
  val activity=instrumentation.startActivitySync(Intent(context,com.galaxy.autorecord.ui.RecordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  val hub=RecordSessionHub.get(context)
  instrumentation.runOnMainSync{hub.startRequested(listOf("front"),settings)}
  fun await(seconds:Long,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+seconds*1000
   while(SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(100)}
   error("recovery_fixture_not_recording:${hub.snapshot}")}
  await(25){hub.snapshot.state==RecordingState.RECORDING&&hub.snapshot.segmentIndex>=2&&hub.snapshot.bytesWritten>0}
  val before=hub.snapshot.bytesWritten
  instrumentation.runOnMainSync{activity.finish()}
  await(15){hub.snapshot.state==RecordingState.RECORDING&&hub.snapshot.bytesWritten>before+4096}
  report.putString("recoverySession","PASS: real Camera2 recording continues after Activity.finish; foreground camera service owns session")
  report.putInt("pid",Process.myPid())
 }
}
