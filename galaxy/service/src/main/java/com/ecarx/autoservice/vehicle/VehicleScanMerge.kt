package com.ecarx.autoservice.vehicle

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把[EcarxVehicleSource.scanCycle]的诊断扫描结果并入本拍批次。
 *
 * 扫描每拍只读32项，其中绝大多数没有订阅。若只取条数/游标，这些属性虽然真的读了车机，
 * 却进不了`VehicleInventory`，跨进程分页接口（`getVehicleProperties`/`getVehicleCatalog`）
 * 就永远看不到它们——"读尽读"只剩helper内部的一次读取。因此扫描行必须并进同一批
 * `properties`，由库按(propertyId, areaId)落行，原始值、区域、状态和新鲜度随之可查。
 *
 * 同理，扫描窗口内排空的方控/菜单事件也必须交出去：`cycle()`先排空、`scanCycle()`后排空，
 * 两者共用同一个严格递增的`keySequence`，按序追加即可；只并属性行会把这段时间内到达的
 * 按键边沿丢掉（DOWN丢失后其UP因`downAt==null`不再切页，按键被静默吞掉），app 与 root
 * 两种采集身份都受影响。
 *
 * 只做数组拼接，不改写行内容、不补默认值：扫描与订阅两条来源的行格式一致。
 */
internal object VehicleScanMerge {
    /** @return 同一个[batch]对象（便于链式使用）。 */
    fun merge(batch: JSONObject, scan: JSONObject): JSONObject {
        // 先并事件：扫描行和事件是两条独立输出，任一条为空都不能让另一条被跳过。
        scan.optJSONArray("keyEvents")?.takeIf { it.length() > 0 }?.let { events ->
            val merged = batch.optJSONArray("keyEvents") ?: JSONArray().also { batch.put("keyEvents", it) }
            for (index in 0 until events.length()) merged.put(events.getJSONObject(index))
        }
        scan.optJSONArray("scan")?.takeIf { it.length() > 0 }?.let { items ->
            batch.put("scanCursor", scan.optInt("cursor", 0))
            val properties = batch.optJSONArray("properties") ?: JSONArray().also { batch.put("properties", it) }
            for (index in 0 until items.length()) properties.put(items.getJSONObject(index))
        }
        return batch
    }
}