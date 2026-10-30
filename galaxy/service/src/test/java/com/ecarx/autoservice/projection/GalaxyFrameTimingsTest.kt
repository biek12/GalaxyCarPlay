package com.ecarx.autoservice.projection
import org.junit.Assert.*
import org.junit.Test
class GalaxyFrameTimingsTest {
    @Test fun boundedWindowExcludesOldSpikesAndDoesNotInventMissingSamples(){
        val metrics=GalaxyFrameTimings(100)
        assertEquals("stageSamples=0",metrics.metrics("stage"))
        metrics.recordMicros(999999);metrics.recordMicros(-1)
        for(i in 1L..100)metrics.recordMicros(i)
        val report=metrics.metrics("stage")
        assertEquals("stageSamples=100 stageP50=50 stageP95=95 stageP99=99",report)
    }
}
