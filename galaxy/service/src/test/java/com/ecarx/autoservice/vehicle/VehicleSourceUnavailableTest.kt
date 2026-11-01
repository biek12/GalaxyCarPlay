package com.ecarx.autoservice.vehicle

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VehicleSourceUnavailableTest {
    /**
     * 断连时必须把**订阅键**逐条报成 UNAVAILABLE，且不填0；
     * 未订阅的目录键不出现（v4不再每拍发全目录）。
     */
    @Test fun missingOemBinderReportsSubscribedKeysUnavailableWithoutInventingZero() {
        val source = EcarxVehicleSource()
        val all = VehicleCatalog.forModel("L6")
        val wanted = setOf("displaySpeed", "gear")
        val result = source.cycle(all, 123, "L6", wanted, wanted)
        assertEquals("not_connected", result.getString("discovery"))
        val samples = result.getJSONArray("samples")
        assertEquals(wanted.size, samples.length())
        for (index in 0 until samples.length()) {
            assertTrue(samples.getJSONObject(index).getString("key") in wanted)
            assertEquals("UNAVAILABLE", samples.getJSONObject(index).getString("status"))
            assertTrue(samples.getJSONObject(index).isNull("value"))
        }
        source.close(); source.close()
    }

    /** “读尽读”能力不因订阅化消失：显式请求全目录时仍逐条给出状态。 */
    @Test fun requestingWholeCatalogStillReportsEveryKey() {
        val source = EcarxVehicleSource()
        val all = VehicleCatalog.forModel("L6")
        val keys = all.mapTo(HashSet()) { it.key }
        val result = source.cycle(all, 123, "L6", keys, keys)
        assertEquals(all.size, result.getJSONArray("samples").length())
        source.close()
    }
}