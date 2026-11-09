package com.galaxy.autorecord.runtime
import android.content.*
import android.os.*
import com.galaxy.autorecord.BuildConfig
import com.galaxy.autorecord.core.PrefsSettingsStore
/** 仅emulator APK，shell DUMP权限隔离。生产/Field包无此组件。 */
class RecordEmulatorControlReceiver:BroadcastReceiver() {
 override fun onReceive(context:Context,intent:Intent?) {
  check(BuildConfig.EMULATOR_CAMERA && Build.HARDWARE in setOf("ranchu","goldfish"))
  check(context.getSharedPreferences("emulator.record.test",0).getBoolean("enabled",false))
  when(intent?.action){
   "record.test.STOP"->RecordSessionHub.get(context).stopRequested()
   "record.test.CRASH_NATIVE"->Process.sendSignal(Process.myPid(),11)
   "record.test.CRASH_JAVA"->Thread({throw IllegalStateException("synthetic_recovery_crash")},"recovery-crash-probe").start()
   "record.test.DISABLE_RECOVERY"->{val store=PrefsSettingsStore(context);store.save(store.load().copy(recoverAfterCrash=false));RecordSessionHub.get(context).settingsChanged()}
  }
 }
}
