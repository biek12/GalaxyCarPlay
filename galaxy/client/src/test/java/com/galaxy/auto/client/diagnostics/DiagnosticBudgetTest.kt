package com.galaxy.auto.client.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[30])
class DiagnosticBudgetTest {
 @Test fun oversizedSystemTraceAndParametersRemainUploadableWithExplicitTruncation() {
  val event=JSONObject().put("id","test").put("kind","recovered_exit")
   .put("extra",JSONObject().put("reason",5).put("trace","栈".repeat(100000)))
   .put("parameters",JSONObject().apply{repeat(32){put("key$it","参".repeat(1000))}})
  val result=DiagnosticEvents.boundedRecord(event,null)
  assertTrue(result.toString().toByteArray(Charsets.UTF_8).size<=60*1024)
  assertTrue(result.getBoolean("truncated"))
  assertTrue(result.getJSONObject("extra").getBoolean("traceTruncated"))
  assertEquals(5,result.getJSONObject("extra").getInt("reason"))
 }
}
