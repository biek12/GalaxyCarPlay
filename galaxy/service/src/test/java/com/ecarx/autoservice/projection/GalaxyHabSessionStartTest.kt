package com.ecarx.autoservice.projection

import org.junit.Assert.*
import org.junit.Test

class GalaxyHabSessionStartTest {
    @Test fun liveReceiverUsesSameAppSessionWithoutRootOrProvisioning() {
        val attempts = mutableListOf<Boolean>()
        val sender = Any()
        val found = GalaxyHabSessionStart({ root -> attempts.add(root); sender }, { true }).existing()
        assertSame(sender, found)
        assertEquals(listOf(false), attempts)
    }
    @Test fun appPermissionFailureCanReuseReceiverThroughAlreadyRunningHelper() {
        val attempts = mutableListOf<Boolean>()
        val sender = Any()
        val found = GalaxyHabSessionStart({ root -> attempts.add(root); if (!root) error("device_access_denied"); sender }, { true }).existing()
        assertSame(sender, found)
        assertEquals(listOf(false, true), attempts)
    }
    @Test fun missingReceiverReturnsForLifecyclePreparationAfterTwoCleanAttempts() {
        var attempts = 0
        val found = GalaxyHabSessionStart<Any>({ ++attempts; error("no_receiver") }, { true }).existing()
        assertNull(found); assertEquals(2, attempts)
    }
    @Test fun cancellationAfterDirectFailureCannotStartRootOwner() {
        var current = true
        var attempts = 0
        val found = GalaxyHabSessionStart<Any>({ ++attempts; current = false; error("cancelled") }, { current }).existing()
        assertNull(found); assertEquals(1, attempts)
    }
    @Test fun oldOwnerNotReapedForbidsAnyNextAttemptOrProvisioning() {
        var attempts = 0
        try {
            GalaxyHabSessionStart<Any>({ ++attempts; throw GalaxyHabOwnerBusy() }, { true }).existing()
            fail("must not overlap owners")
        } catch (_: GalaxyHabOwnerBusy) { assertEquals(1, attempts) }
    }
}
