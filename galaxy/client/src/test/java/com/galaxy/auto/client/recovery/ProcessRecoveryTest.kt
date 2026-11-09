package com.galaxy.auto.client.recovery
import android.content.Context
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[30])
class ProcessRecoveryTest {
 @Test fun explicitStopRejectsAlreadyQueuedGenerationAndRearmRejectsOldAlarm() {
  val app=RuntimeEnvironment.getApplication()
  val target=Intent().setClassName(app.packageName,app.packageName+".OwnedService")
  ProcessRecovery.arm(app,target)
  val prefs=app.getSharedPreferences("process-recovery",Context.MODE_PRIVATE)
  val old=Intent(target).putExtra(ProcessRecovery.EXTRA_GENERATION,prefs.getString("generation",null))
  assertFalse(ProcessRecovery.accepts(app,old)) // 本进程活服务不能重复打开。
  ProcessRecovery.release(app,true)
  assertTrue(ProcessRecovery.accepts(app,old))
  ProcessRecovery.arm(app,target,true)
  assertFalse(ProcessRecovery.accepts(app,old))
  val next=Intent(target).putExtra(ProcessRecovery.EXTRA_GENERATION,prefs.getString("generation",null))
  ProcessRecovery.release(app,false)
  assertFalse(ProcessRecovery.accepts(app,next))
  assertFalse(prefs.getBoolean("wanted",true))
 }
 @Test fun foreignAppComponentCannotBecomeRecoveryTarget() {
  val app=RuntimeEnvironment.getApplication()
  assertThrows(IllegalArgumentException::class.java){ProcessRecovery.arm(app,Intent().setClassName("other.app","other.app.Service"))}
 }
}
