package com.shilapi.xcertplay

import android.os.Binder
import android.os.Parcel
import com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GalaxyL6NavigationValuesTest {
    @Test fun l6ArrowVocabularyIncludesRoundaboutsAndDoesNotGuessUnknownTypes() {
        assertEquals(102, GalaxyL6NavigationValues.turnId(1, 0))
        assertEquals(103, GalaxyL6NavigationValues.turnId(2, 0))
        assertEquals(121, GalaxyL6NavigationValues.turnId(28, 0))
        assertEquals(139, GalaxyL6NavigationValues.turnId(46, 0))
        assertNull(GalaxyL6NavigationValues.turnId(254, 0))
        assertNull(GalaxyL6NavigationValues.nonNegativeInt(Long.MAX_VALUE))
    }
    @Test fun dimReceivesMetersAndRemainingSecondsWithEtaLastAndNoUnknownWrites() {
        val writes = mutableListOf<Pair<Int, Int>>()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(GalaxyL6DimNavigationPort.DESCRIPTOR)
                writes += code to data.readInt(); reply?.writeNoException(); return true
            }
        }
        val value = CarPlayNavigationSnapshot(true, "road", 1, 0, 150, 1000, 300, 1700000000)
        GalaxyL6DimNavigationPort.publishGuidance(binder, value)
        assertEquals(listOf(2 to 102, 7 to 1000, 8 to 150, 12 to 300), writes)
        writes.clear()
        GalaxyL6DimNavigationPort.publishGuidance(binder, CarPlayNavigationSnapshot(true))
        GalaxyL6DimNavigationPort.publishGuidance(binder, value.copy(active = false))
        assertTrue(writes.isEmpty())
    }
}
