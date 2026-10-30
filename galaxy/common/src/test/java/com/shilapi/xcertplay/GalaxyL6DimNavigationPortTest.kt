package com.shilapi.xcertplay

import android.os.Binder
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GalaxyL6DimNavigationPortTest {
    @Test fun transactionsUseTheL6DescriptorAndVerifiedStatusAndRoadCodes() {
        val seen = mutableListOf<Pair<Int, Any?>>()
        val target = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(GalaxyL6DimNavigationPort.DESCRIPTOR)
                val value: Any? = when (code) {
                    GalaxyL6DimNavigationPort.TRANSACTION_NAVIGATION_STATUS,
                    GalaxyL6DimNavigationPort.TRANSACTION_TBT_EVENT -> data.readInt()
                    GalaxyL6DimNavigationPort.TRANSACTION_NEXT_GUIDANCE_NAME -> data.readString()
                    else -> return false
                }
                seen += code to value
                reply?.writeNoException()
                return true
            }
        }

        GalaxyL6DimNavigationPort.transactBinder(target, GalaxyL6DimNavigationPort.TRANSACTION_NAVIGATION_STATUS) {
            writeInt(GalaxyL6DimNavigationPort.STATUS_START)
        }
        GalaxyL6DimNavigationPort.transactBinder(target, GalaxyL6DimNavigationPort.TRANSACTION_TBT_EVENT) {
            writeInt(GalaxyL6DimNavigationPort.TBT_EVENT_START)
        }
        GalaxyL6DimNavigationPort.transactBinder(target, GalaxyL6DimNavigationPort.TRANSACTION_NEXT_GUIDANCE_NAME) {
            writeString("路名")
        }
        GalaxyL6DimNavigationPort.transactBinder(target, GalaxyL6DimNavigationPort.TRANSACTION_TBT_EVENT) {
            writeInt(GalaxyL6DimNavigationPort.TBT_EVENT_STOP)
        }
        GalaxyL6DimNavigationPort.transactBinder(target, GalaxyL6DimNavigationPort.TRANSACTION_NAVIGATION_STATUS) {
            writeInt(GalaxyL6DimNavigationPort.STATUS_STOP)
        }

        assertEquals(
            listOf(1 to 1, 11 to 0, 23 to "路名", 11 to 1, 1 to 0),
            seen,
        )
        assertTrue(GalaxyL6DimNavigationPort.SERVICE_PACKAGE == "com.autolink.diminteraction")
    }
}
