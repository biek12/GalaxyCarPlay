package com.ecarx.autoservice.vehicle

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 诊断扫描结果必须进入跨进程缓存。
 *
 * 修复前（48d3fde）：扫描只回条数和游标，`VehicleInventory` 只 accept `properties`，
 * 未订阅属性虽然真的读取了，分页接口（getVehicleProperties/getVehicleCatalog）一条也看不到，
 * “读尽读”只剩 helper 内部的一次读取。这里锁住“扫描行并进同一批 properties”这一约束。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VehicleScanMergeTest {
    private fun row(property: Int, area: Int) = JSONObject().apply {
        put("propertyId", property); put("areaId", area); put("responseAreaId", area)
        put("name", "p$property"); put("status", "VALID"); put("value", "7"); put("maxAgeMillis", 2000)
    }

    @Test fun scannedPropertiesBecomePageable() {
        val batch = JSONObject().put("session", "L6:1:1").put("properties", JSONArray().put(row(1, 1)))
        val scan = JSONObject().put("scan", JSONArray().put(row(2, 0)).put(row(3, 1))).put("cursor", 64)
        VehicleScanMerge.merge(batch, scan)
        assertEquals(3, batch.getJSONArray("properties").length())
        assertEquals(64, batch.getInt("scanCursor"))
        val store = VehicleInventory(); store.accept(batch)
        assertEquals(3, store.count()); assertEquals(3, store.validCount())
        // 分页结果里必须能看到那条从未被订阅的属性。
        val page = store.page(0, 32, false)
        val items = page.getParcelableArrayList<android.os.Bundle>("items").orEmpty()
        assertTrue(items.any { it.getInt("propertyId") == 2 })
    }

    @Test fun emptyScanLeavesBatchUntouched() {
        val batch = JSONObject().put("session", "s").put("properties", JSONArray())
        VehicleScanMerge.merge(batch, JSONObject().put("scan", JSONArray()))
        assertEquals(0, batch.getJSONArray("properties").length())
        assertFalse(batch.has("scanCursor"))
    }

    /** 没有 subscriptions 的纯扫描批次（properties缺失）也要能落行。 */
    @Test fun scanOnlyBatchCreatesPropertiesArray() {
        val batch = JSONObject().put("session", "L6:2:1")
        VehicleScanMerge.merge(batch, JSONObject().put("scan", JSONArray().put(row(9, 0))))
        val store = VehicleInventory(); store.accept(batch)
        assertEquals(1, store.count())
    }
}