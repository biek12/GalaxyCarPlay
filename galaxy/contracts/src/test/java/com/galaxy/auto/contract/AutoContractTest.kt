package com.galaxy.auto.contract

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class AutoContractTest {
    @Test fun metadataIsBoundedAndUnknownFieldsNeverCrossBoundary() {
        val safe = AutoContract.sanitizeMedia(Bundle().apply {
            putString("title", "x".repeat(2000)); putString("shell", "forbidden")
            putLong("elapsed", Long.MAX_VALUE); putLong("duration", -500)
            putStringArrayList("lyrics", ArrayList(List(150) { "y".repeat(600) }))
            putLongArray("lyricTimes", longArrayOf(10))
        })
        assertEquals(1024, safe.getString("title")!!.length)
        assertFalse(safe.containsKey("shell"))
        assertEquals(86400000L, safe.getLong("elapsed")); assertEquals(-1L, safe.getLong("duration"))
        assertEquals(120, safe.getStringArrayList("lyrics")!!.size)
        assertEquals(512, safe.getStringArrayList("lyrics")!![0].length)
        assertFalse(safe.containsKey("lyricTimes"))
    }
    @Test fun freshnessUsesPerFieldLocalClockAndRejectsClockRollback() {
        val field = VehicleSignal("speed", "12", "km/h", "source", 1, 100, 999999, 3, SignalStatus.VALID)
        assertEquals(SignalStatus.VALID, field.effectiveStatus(2100))
        assertEquals(SignalStatus.STALE, field.effectiveStatus(2101))
        assertEquals(SignalStatus.STALE, field.effectiveStatus(99))
        assertEquals(SignalStatus.REJECTED, field.copy(status = SignalStatus.REJECTED).effectiveStatus(100))
        assertEquals(999999L, field.bundle(100).getLong("sourceTimestamp"))
    }
    @Test fun serviceIdentityIsUserSelectedAndContractsRemainVersioned() {
        assertEquals("com.ecarx.autoservice", AutoContract.SERVICE_PACKAGE)
        assertEquals(1, AutoContract.VERSION)
        assertFalse(AutoContract.roleValid("root-shell"))
    }
}
