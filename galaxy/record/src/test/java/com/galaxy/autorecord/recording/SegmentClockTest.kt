package com.galaxy.autorecord.recording
import org.junit.Assert.*
import org.junit.Test
class SegmentClockTest {
 @Test fun partialMinuteFillsAtLeastOneFullMinuteAndThenStaysAligned() {
  val wall=(12*3600L+15*60+46)*1000
  val c=SegmentClock(wall,1_000_000,60)
  assertEquals(74_000_000,c.endUs(1)-c.startUs(1))
  assertEquals((12*3600L+17*60)*1000,c.wall(2))
  for(i in 2..10000){assertEquals(c.endUs(i-1),c.startUs(i));assertEquals(60_000_000,c.endUs(i)-c.startUs(i))}
 }
 @Test fun exactMinuteAndShortStressSegmentsDoNotGainAnExtraMinute() {
  val c=SegmentClock(60_000,100,60);assertEquals(60_000_000,c.endUs(1)-100)
  val s=SegmentClock(61_123,100,4);assertEquals(4_000_000,s.endUs(1)-100)
 }
}
