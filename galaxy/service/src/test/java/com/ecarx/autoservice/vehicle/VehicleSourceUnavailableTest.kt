package com.ecarx.autoservice.vehicle

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VehicleSourceUnavailableTest {
    @Test fun missingOemBinderKeepsAllFieldsUnavailableWithoutInventingZero() {
        val source = EcarxVehicleSource()
        val result = source.cycle(VehicleCatalog.forModel("L6"), 123, "L6")
        assertEquals("not_connected", result.getString("discovery"))
        val samples = result.getJSONArray("samples")
        assertEquals(VehicleCatalog.forModel("L6").size, samples.length())
        for (index in 0 until samples.length()) {
            assertEquals("UNAVAILABLE", samples.getJSONObject(index).getString("status"))
            assertTrue(samples.getJSONObject(index).isNull("value"))
        }
        source.close(); source.close()
    }
}
