package com.galaxy.autorecord.recording
import org.junit.Assert.*
import org.junit.Test
class OutputWatchdogTest {
 @Test fun healthyDirectionCannotMaskAnotherDirectionWhichNeverProduced() {
  assertEquals(listOf("rear"),OutputWatchdog.stalled(9_000_000,1_000_000,mapOf("front" to 8_900_000,"rear" to 0)))
 }
 @Test fun recoveryBeforeTimeoutAndActiveOutputsAreNotFalselyFailed() {
  assertTrue(OutputWatchdog.stalled(8_999_999,1_000_000,mapOf("front" to 0)).isEmpty())
  assertTrue(OutputWatchdog.stalled(60_000_000,1_000_000,mapOf("front" to 59_900_000,"rear" to 59_000_000)).isEmpty())
 }
}
