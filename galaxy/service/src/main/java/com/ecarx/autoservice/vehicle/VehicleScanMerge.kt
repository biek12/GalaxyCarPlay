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
 * 只做数组拼接，不改写行内容、不补默认值：扫描与订阅两条来源的行格式一致。
 */
internal object VehicleScanMerge {
    /** @return 同一个[batch]对象（便于链式使用）；扫描为空时原样返回。 */
    fun merge(batch: JSONObject, scan: JSONObject): JSONObject {
        val items = scan.optJSONArray("scan") ?: return batch
        if (items.length() == 0) return batch
        val properties = batch.optJSONArray("properties") ?: JSONArray().also { batch.put("properties", it) }
        for (index in 0 until items.length()) properties.put(items.getJSONObject(index))
        batch.put("scanCursor", scan.optInt("cursor", 0))
        return batch
    }
}