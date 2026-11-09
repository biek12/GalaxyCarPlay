package com.galaxy.auto.client.diagnostics
import org.junit.Assert.*
import org.junit.Test
class DiagnosticEventsTest {
 @Test fun credentialsAddressesPathsAndLongIdentifiersAreRemovedFromMessages() {
  val raw="token=secretValue Bearer bearerValue password=passwordValue https://example.test/private /data/user/0/secret 12345678901 aaaa1111bbbb2222cccc3333dddd4444"
  val safe=DiagnosticEvents.safe(raw)
  for(s in listOf("secretValue","bearerValue","passwordValue","example.test","/data/user","12345678901","aaaa1111"))assertFalse(s,safe.contains(s))
 }
 @Test fun utf8BudgetBoundsMultibyteSystemTrace() {
  val raw="崩溃栈".repeat(20000)
  val bounded=DiagnosticEvents.boundedUtf8(raw,32768)
  assertTrue(bounded.toByteArray(Charsets.UTF_8).size<=32768)
  assertTrue(bounded.isNotEmpty());assertTrue(raw.startsWith(bounded))
 }
 @Test fun sourceClassAndErrorMeaningRemainUseful() {
  val safe=DiagnosticEvents.safe("MediaCodec.configure invalid width=1280 height=720")
  assertTrue(safe.contains("MediaCodec.configure"));assertTrue(safe.contains("width=1280"))
 }
}
