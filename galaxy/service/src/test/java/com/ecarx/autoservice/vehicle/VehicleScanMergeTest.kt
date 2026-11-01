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

    private fun event(action: Int, key: Int, sequence: Long) = JSONObject().apply {
        put("action", action); put("key", key); put("at", sequence); put("sequence", sequence)
    }

    /**
     * 扫描窗口内排空的方控事件必须按 sequence 顺序接在订阅那批之后。
     * 修复前 merge 只并属性行：`scanCycle` 排空的 keyEvents 被丢弃，那段窗口内到达的
     * DOWN/UP 边沿永久丢失（DOWN 丢了下一次 UP 因 downAt==null 不再切页）。
     */
    @Test fun scanEventsAppendAfterSubscriptionEventsInSequenceOrder() {
        val batch = JSONObject().put("session", "L6:1:1").put("properties", JSONArray())
            .put("keyEvents", JSONArray().put(event(0, 6, 1)))
        val scan = JSONObject().put("scan", JSONArray().put(row(5, 0))).put("cursor", 32)
            .put("keyEvents", JSONArray().put(event(1, 6, 2)))
        VehicleScanMerge.merge(batch, scan)
        val events = batch.getJSONArray("keyEvents")
        assertEquals(2, events.length())
        assertEquals(1L, events.getJSONObject(0).getLong("sequence"))
        assertEquals(2L, events.getJSONObject(1).getLong("sequence"))
        assertEquals(0, events.getJSONObject(0).getInt("action"))
        assertEquals(1, events.getJSONObject(1).getInt("action"))
    }

    /** 扫描行空、但窗口内仍有事件时也不能整段跳过（merge 的早退不能挡在事件之前）。 */
    @Test fun eventsStillMergeWhenScanRowsAreEmpty() {
        val batch = JSONObject().put("session", "L6:1:1").put("properties", JSONArray())
        val scan = JSONObject().put("scan", JSONArray()).put("cursor", 0)
            .put("keyEvents", JSONArray().put(event(0, 6, 7)))
        VehicleScanMerge.merge(batch, scan)
        assertEquals(1, batch.getJSONArray("keyEvents").length())
        assertEquals(0, batch.getJSONArray("properties").length())
    }
}
