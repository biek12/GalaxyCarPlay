package com.ecarx.autoservice.vehicle

import android.os.Bundle
import org.json.JSONObject

/** 现场标定仅导出明确列出的仪表数值；读缓存，不触发车控、额外读属性或认证流程。
 *  v4下“缓存”是当前订阅采集的结果，因此未订阅的键不出现——这是设计而非缺失，
 *  全目录可读范围仍可在诊断页按属性翻页核对。 */
internal object ClusterFieldDiagnostics {
    private val keys = listOf("gear", "driveMode", "displaySpeed", "cruiseState", "cruiseSetSpeed", "regenLevel", "electricPowerRaw", "electricPowerStateRaw", "drivePowerKw", "regenPowerKw", "tractionPowerKw",
        "fuelRange", "evRange", "fuel", "soc", "fuelConsumption", "electricConsumption", "outsideTemperature",
        "tire.frontLeft", "tire.frontRight", "tire.rearLeft", "tire.rearRight")
    fun collect(snapshot: Bundle, now: Long): JSONObject {
        val vehicle = snapshot.getBundle("vehicle") ?: Bundle()
        val values = JSONObject()
        for (key in keys) {
            val item = vehicle.getBundle(key) ?: continue
            val text = item.getString("value")
            val numeric = text?.toDoubleOrNull()?.takeIf { it.isFinite() }
            val state = text?.takeIf { it in setOf("off", "standby", "active", "low", "medium", "high", "auto", "P", "R", "N", "D", "纯电", "增程", "性能", "智能") }
            val age = now - item.getLong("receivedAt")
            val status = item.getString("status").takeIf { it in setOf("VALID", "STALE", "UNAVAILABLE", "DENIED", "UNSUPPORTED", "ERROR") } ?: "UNAVAILABLE"
            values.put(key, JSONObject().put("value", numeric ?: state ?: JSONObject.NULL)
                .put("status", if (status == "VALID" && age !in 0..2000) "STALE" else status)
                .put("ageMillis", age).put("unit", item.getString("unit").orEmpty().take(24)))
        }
        return JSONObject().put("schema", 1).put("sampledAtUptimeMillis", now)
            .put("menuSequence", snapshot.getLong("menuSequence")).put("fields", values)
    }
}
