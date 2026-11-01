package com.ecarx.autoservice.vehicle

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** 库内保存完整属性值；跨进程分页限制条数与值预览长度，长值可按字符偏移读完，不静默丢弃。 */
internal class VehicleInventory {
    private val rows = sortedMapOf<Long, JSONObject>()
    private var valid = 0
    private fun key(property: Int, area: Int) = (property.toLong() shl 32) or (area.toLong() and 0xffffffffL)
    private var session = ""
    @Synchronized fun accept(batch: JSONObject) {
        val next = batch.optString("session")
        if (next != session) { rows.clear(); valid = 0; session = next }
        val values = batch.optJSONArray("properties") ?: return
        for (index in 0 until values.length()) {
            val value = values.getJSONObject(index)
            val previous = rows.put(key(value.getInt("propertyId"), value.getInt("areaId")), value)
            if (previous?.optString("status") == "VALID") --valid
            if (value.optString("status") == "VALID") ++valid
        }
    }
    @Synchronized fun count() = rows.size
    @Synchronized fun inspectKnown(propertyId: Int): String {
        require(propertyId in setOf(557874774, 561024567, 557874335))
        return rows.values.firstOrNull { it.optInt("propertyId") == propertyId }?.toString().orEmpty()
    }
    @Synchronized fun validCount() = valid
    @Synchronized fun clear() { rows.clear(); valid = 0; session = "" }
    @Synchronized fun page(offset: Int, limit: Int, catalog: Boolean): Bundle {
        require(offset >= 0 && limit in 1..32)
        val selected = rows.values.drop(offset).take(limit).map { value -> Bundle().apply {
            for (key in listOf("name", "origin", "type", "status", "error")) putString(key, value.optString(key))
            for (key in listOf("propertyId", "areaId", "responseAreaId", "access")) putInt(key, value.optInt(key, -1))
            putLong("receivedAt", value.optLong("receivedAt")); putString("session", session)
            val age = android.os.SystemClock.elapsedRealtime() - value.optLong("receivedAt")
            if (value.optString("status") == "VALID" && age !in 0..value.optLong("maxAgeMillis", 2000)) putString("status", "STALE")
            if (!catalog) {
                val raw = value.optString("value")
                putString("value", raw.take(2048)); putInt("valueLength", raw.length); putBoolean("truncated", raw.length > 2048)
            }
        } }
        return Bundle().apply { putInt("total", rows.size); putInt("offset", offset)
            putParcelableArrayList("items", ArrayList(selected)); putString("session", session) }
    }
    @Synchronized fun value(propertyId: Int, areaId: Int, offset: Int): Bundle {
        require(offset >= 0)
        val row = rows[key(propertyId, areaId)]
        val raw = row?.optString("value").orEmpty()
        return Bundle().apply { putString("value", raw.drop(offset).take(16384)); putInt("length", raw.length)
            putString("status", row?.optString("status") ?: "UNAVAILABLE"); putLong("receivedAt", row?.optLong("receivedAt") ?: 0) }
    }
}
