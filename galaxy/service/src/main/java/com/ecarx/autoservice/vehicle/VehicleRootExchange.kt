package com.ecarx.autoservice.vehicle

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * helper 线程上的车辆订阅读取与停止。
 *
 * 只解释订阅键、到期键和诊断扫描，不写 Root 帧。
 * 失败抛给调用方；本类型不关闭套接字。读取和停止都在 source 锁内完成。
 */
internal object VehicleRootExchange {
    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        val out = HashSet<String>(length())
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }

    /**
     * 订阅键决定注册与读取；scan 为 true 时额外推进一轮全目录诊断扫描。
     * 扫描行必须并进同一回复的 properties。调用方持有 helper 的连接线程。
     */
    fun read(
        source: EcarxVehicleSource,
        specs: List<SignalSpec>,
        model: String,
        request: JSONObject,
    ): JSONObject =
        synchronized(source) {
            val now = SystemClock.elapsedRealtime()
            val keys = request.optJSONArray("keys").toStringSet()
            val due =
                request
                    .optJSONArray("due")
                    .toStringSet()
                    .intersect(keys)
            val reply =
                source.cycle(
                    specs,
                    now,
                    model,
                    keys,
                    due,
                    request.optBoolean("scan"),
                )
            if (request.optBoolean("scan")) {
                val scan = source.scanCycle(specs, now, model)
                reply.put("scanCount", scan.optJSONArray("scan")?.length() ?: 0)
                VehicleScanMerge.merge(reply, scan)
            }
            reply
        }

    /** 关闭 helper 内的车辆源。调用方持有 helper 的连接线程。 */
    fun stop(source: EcarxVehicleSource): JSONObject =
        synchronized(source) {
            source.close()
            JSONObject().put("state", "vehicle_stopped")
        }
}
