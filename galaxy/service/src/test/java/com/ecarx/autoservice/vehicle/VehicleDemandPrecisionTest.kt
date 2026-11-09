package com.ecarx.autoservice.vehicle

import com.galaxy.auto.contract.AutoContract
import org.junit.Assert.*
import org.junit.Test

class VehicleDemandPrecisionTest {
    @Test fun contractAllows100msWithoutChangingSlowDefaults() {
        assertTrue(AutoContract.feedIntervalValid(100))
        assertFalse(AutoContract.feedIntervalValid(99))
        assertEquals(250,AutoContract.DEFAULT_FEED_INTERVAL_MS)
    }
    @Test fun mixedPeriodsWakeAt250RatherThan300() {
        val demand=VehicleDemand()
        demand.assign(mapOf("brake" to 100,"soc" to 250,"gear" to 1000))
        assertEquals(0L,demand.nextDelayMillis(1000))
        demand.markRead(demand.keys(),1000)
        assertEquals(100L,demand.nextDelayMillis(1000))
        demand.markRead(setOf("brake"),1100)
        demand.markRead(setOf("brake"),1200)
        assertEquals(50L,demand.nextDelayMillis(1200))
        assertEquals(setOf("soc"),demand.due(1250))
        demand.assign(emptyMap())
        assertNull(demand.nextDelayMillis(1251))
    }
}
