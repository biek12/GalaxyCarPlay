package com.galaxy.autorecord.core

import org.junit.Assert.*
import org.junit.Test

class VehicleHistoryTracksTest {
    private fun pedal(vararg samples: Pair<Long,String?>) = VehicleTrace(VehicleKey.BRAKE,
        samples.map { VehicleTracePoint(it.first,it.second) },225)

    @Test fun shortLightPressesAreSeparateEvenWhenLessThanOldMergeWindow() {
        val events = pedal(0L to "0",100L to "0.5",200L to "0",300L to "2",400L to "0").intervals()
        assertEquals(listOf(100L to 200L,300L to 400L),events.map { it.startMs to it.endMs })
        assertEquals(.5f,events[0].peak!!,0f)
    }
    @Test fun pressureCanRiseAndFallWithoutSplittingOneContinuousPress() {
        val trace = pedal(0L to "0",100L to "5",200L to "80",300L to "20",400L to "0")
        val event = trace.intervals().single()
        assertEquals(100L,event.startMs);assertEquals(400L,event.endMs);assertEquals(80f,event.peak!!,0f)
        assertEquals(listOf(0f,5f,80f,20f,0f),trace.points.map { it.percent() })
    }
    @Test fun unknownSamplesAndLargeGapsNeverBridgePresses() {
        val events = pedal(0L to "3",100L to null,200L to "5",300L to "5",900L to "8",1000L to "0").intervals()
        assertEquals(listOf(0L to 100L,200L to 300L,900L to 1000L),events.map { it.startMs to it.endMs })
    }
    @Test fun outOfRangeRawEncodingIsNotInventedAsPercent() {
        assertNull(VehicleTracePoint(0,"4608").percent())
        assertNull(VehicleTracePoint(0,"NaN").percent())
        assertNull(VehicleTracePoint(0,"-1").percent())
    }
    @Test fun sourceTimesRemainAlignedInsteadOfCallbackArrivalTimes() {
        val samples=listOf(VehicleSample(800,mapOf("brake" to "20"),mapOf("brake" to 100)),
            VehicleSample(900,mapOf("brake" to "0"),mapOf("brake" to 200)))
        val trace=VehicleHistoryTracks.build(samples,1000,100).single()
        assertEquals(listOf(100L,200L),trace.points.map { it.timeMs })
        assertEquals(100L,trace.intervals().single().startMs)
    }
    @Test fun binaryLightStaysAnIntervalAndDifferentLightsHaveSeparateTracks() {
        val samples=listOf(VehicleSample(0,mapOf("lowBeam" to "1","highBeam" to "0")),
            VehicleSample(100,mapOf("lowBeam" to "1","highBeam" to "1")),
            VehicleSample(200,mapOf("lowBeam" to "0","highBeam" to "1")))
        val traces=VehicleHistoryTracks.build(samples,1000,100)
        assertEquals(listOf(VehicleKey.LOW_BEAM,VehicleKey.HIGH_BEAM),traces.map { it.key })
        assertEquals(200L,traces.first().intervals().single().endMs)
    }
}
