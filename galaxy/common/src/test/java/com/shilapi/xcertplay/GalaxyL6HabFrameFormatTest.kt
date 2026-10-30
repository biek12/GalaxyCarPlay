package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class GalaxyL6HabFrameFormatTest {
    @Test fun autoDisplay362VirtualClusterArgumentsRemainVersionedSampleEvidence() {
        val profile = GalaxyL6HabSampleTarget.autoDisplayV362Sample()
        assertEquals("virtual_cluster", profile.virtualScreenId)
        assertEquals(1, profile.nativeDisplayId)
        assertEquals(1920, profile.width)
        assertEquals(532, profile.height)
        assertEquals(1, profile.zOrder)
        assertEquals(0x580259, profile.nativeOpenMagic)
        assertEquals(30, profile.fps)
        assertEquals(6, profile.slots)
        assertEquals(10_000, profile.openTimeoutMillis)
    }

    @Test fun invalidNativeRequestBoundsAreRejected() {
        val sample = GalaxyL6HabSampleTarget.autoDisplayV362Sample()
        val invalidCases = listOf<() -> Unit>(
            { sample.copy(nativeDisplayId = -1) },
            { sample.copy(width = 15) },
            { sample.copy(nativeOpenMagic = -1) },
            { sample.copy(fps = 61) },
            { sample.copy(slots = 1) },
            { sample.copy(openTimeoutMillis = 60_001) },
        )
        invalidCases.forEach(::assertRejected)
    }

    @Test fun zOrderIsNotABooleanTargetFlag() {
        val sample = GalaxyL6HabSampleTarget.autoDisplayV362Sample()
        assertEquals(0, sample.copy(zOrder = 0).zOrder)
        assertEquals(5, sample.copy(zOrder = 5).zOrder)
        assertEquals(-1, sample.copy(zOrder = -1).zOrder)
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Invalid sample target was accepted")
        } catch (_: IllegalArgumentException) {
            // Expected: invalid native request inputs must never be normalized into a fallback.
        }
    }

}
