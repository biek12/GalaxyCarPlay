package com.ecarx.autoservice.vehicle

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VehicleInventoryTest {
    private fun batch(epoch: String, text: String, property: Int = 1) = JSONObject().apply {
        put("session", epoch); put("properties", JSONArray().put(JSONObject().apply {
            put("propertyId", property); put("areaId", 1); put("value", text); put("status", "VALID")
        }))
    }
    @Test fun paginationDoesNotDropLongRawValuesAndNewSessionClearsPreviousVehicle() {
        val store = VehicleInventory(); val raw = "数据".repeat(20000)
        store.accept(batch("L6:1", raw)); store.accept(batch("L6:1", "other", 2))
        assertEquals(2, store.count())
        val row = store.page(0, 1, false).getParcelableArrayList<Bundle>("items")!![0]
        assertEquals(2048, row.getString("value")!!.length); assertTrue(row.getBoolean("truncated"))
        val chunks = (0 until raw.length step 16384).joinToString("") { store.value(1, 1, it).getString("value")!! }
        assertEquals(raw, chunks)
        store.accept(batch("L7:2", "new", 3)); assertEquals(1, store.count())
        assertEquals("UNAVAILABLE", store.value(1, 1, 0).getString("status"))
    }
    @Test fun catalogOmitsValues() {
        val store = VehicleInventory(); store.accept(batch("L6:1", "private-value"))
        assertFalse(store.page(0, 1, true).getParcelableArrayList<Bundle>("items")!![0].containsKey("value"))
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedPageIsRejected() { VehicleInventory().page(0, 1000, false) }
}
