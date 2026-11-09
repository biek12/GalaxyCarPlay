package com.ecarx.autoservice.diagnostics

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[30])
class NativeCrashContextTest {
 @Test fun onlyOwnPidAndTimeWindowBacktraceIsRetainedWithoutRegistersOrPaths() {
  val prefix="1700000000.100 100 101 F DEBUG   : "
  val lines=listOf(
   "1699999900.100 100 101 F DEBUG   : pid: 42, tid: 43 >>> com.galaxy.autorecord <<<",
   "1699999900.100 100 101 F DEBUG   : #00 pc 00112233 /private/old/libold.so",
   prefix+"pid: 99, tid: 100 >>> other.app <<<",
   prefix+"#00 pc 00112233 /private/other/libother.so",
   prefix+"pid: 42, tid: 43, name: record-frames >>> com.galaxy.autorecord <<<",
   prefix+"signal 11 (SIGSEGV), code 1, fault addr 0x1234",
   prefix+"x0 secret-register-value",
   prefix+"#00 pc 00112233 /data/app/private/libown.so (OwnCopy+24)",
   prefix+"pid: 100, tid: 101 >>> other.app <<<",
   prefix+"#00 pc 00112233 /private/other/libother.so")
  val result=NativeCrashContext.parse(lines.asSequence(),"com.galaxy.autorecord",42,1700000001000)
  assertTrue(result.getBoolean("available"));assertEquals(1,result.getJSONArray("frames").length())
  val text=result.toString()
  assertTrue(text.contains("libown.so"));assertTrue(text.contains("OwnCopy+24"))
  assertFalse(text.contains("libother"));assertFalse(text.contains("libold"))
  assertFalse(text.contains("secret-register"));assertFalse(text.contains("/data/app/private"))
 }
 @Test fun MissingEvidenceIsExplicitAndNeverGuessed() {
  assertFalse(NativeCrashContext.parse(emptySequence(),"com.galaxy.autorecord",42,1700000001000).getBoolean("available"))
 }
}
