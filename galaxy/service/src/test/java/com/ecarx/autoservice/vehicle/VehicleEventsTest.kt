package com.ecarx.autoservice.vehicle

import android.os.Binder
import ecarx.car.IECarXCar
import ecarx.car.hardware.property.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowServiceManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VehicleEventsTest {
    private fun clearService() {
        val field = ShadowServiceManager::class.java.getDeclaredField("binderServices").apply { isAccessible = true }
        (field.get(null) as MutableMap<*, *>).remove("ecarxcar_service")
    }
    @Test fun liveEventsReplacePollingWithoutDroppingCatalogAreasAndOldCallbacksCannotCrossEpoch() {
        val root = Binder().apply { attachInterface(null, "ecarx.car.IECarXCar") }
        val property = Binder().apply { attachInterface(null, "ecarx.car.hardware.property.IECarXCarProperty") }
        IECarXCar.Stub.service = object : IECarXCar { override fun asBinder() = root; override fun getCarService(name: String) = property }
        val id = 557874774
        var reads = 0
        var eventDuringRead = false
        IECarXCarProperty.Stub.service = object : IECarXCarProperty {
            override fun getProperty(id: Int, area: Int): ECarXCarPropertyValue {
                ++reads
                if (eventDuringRead && area == 1) ECarXCarPropertyManagerBase.callback?.onChangeEvent(ECarXCarPropertyValue(id, area, 77))
                return ECarXCarPropertyValue(id, area, 10)
            }
            override fun getPropertyList() = listOf(TestConfig(id, intArrayOf(1, 2)))
        }
        ShadowServiceManager.addBinderService("ecarxcar_service", IECarXCar::class.java, IECarXCar.Stub.service)
        val source = EcarxVehicleSource()
        val specs = listOf(SignalSpec("speed", id, "km/h"))
        try {
            source.cycle(specs, android.os.SystemClock.elapsedRealtime(), "L6")
            assertEquals(2, reads)
            val callback = checkNotNull(ECarXCarPropertyManagerBase.callback)
            callback.onChangeEvent(ECarXCarPropertyValue(id, 1, 42))
            val next = source.cycle(specs, android.os.SystemClock.elapsedRealtime(), "L6")
            assertEquals(2, reads)
            assertEquals("42.0", next.getJSONArray("samples").getJSONObject(0).getString("value"))
            assertEquals(2, next.getJSONArray("properties").length())
            source.close()
            source.cycle(specs, android.os.SystemClock.elapsedRealtime(), "L6")
            callback.onChangeEvent(ECarXCarPropertyValue(id, 1, 999))
            val final = source.cycle(specs, android.os.SystemClock.elapsedRealtime(), "L6")
            assertEquals("10.0", final.getJSONArray("samples").getJSONObject(0).getString("value"))
            eventDuringRead = true
            val raced = source.cycle(specs, android.os.SystemClock.elapsedRealtime() + 1000, "L6")
            assertEquals("77.0", raced.getJSONArray("samples").getJSONObject(0).getString("value"))
        } finally { source.close(); clearService() }
    }
}
