package com.ecarx.autoservice.projection

import org.junit.Assert.*
import org.junit.Test

class GalaxyHabReconnectTest {
    @Test fun repeatedEarlyDisconnectsExhaustFiniteRecoveryBudget() {
        val policy = GalaxyHabReconnect()
        assertEquals(1000L, policy.failed())
        assertEquals(3000L, policy.failed())
        repeat(10) { assertNull(policy.failed()) }
    }
    @Test fun sustainedFrameSubmissionAllowsRecoveryOfANewDisconnection() {
        val policy = GalaxyHabReconnect()
        policy.failed(); policy.failed()
        policy.stableFrames()
        assertEquals(1000L, policy.failed())
    }
}
