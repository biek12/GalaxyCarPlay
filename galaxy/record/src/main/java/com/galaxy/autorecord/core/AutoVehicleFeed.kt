package com.galaxy.autorecord.core

import android.os.Bundle
import com.galaxy.auto.contract.SignalStatus
import com.galaxy.auto.contract.VehicleSignal

/** L6/L7 的已核验 AutoService 目录映射；字段来源与状态枚举记录在数据对齐台账，不猜 ID。 */
object RecordVehicleAdapter {
    fun keys(key: VehicleKey): List<String> = when (key) {
        VehicleKey.ADAS_LATERAL -> listOf("property.10057")
        VehicleKey.ODOMETER -> listOf("odometer")
        VehicleKey.DOOR_STATE -> (10017..10020).map { "property.$it" }
        VehicleKey.SEAT_BELT -> listOf("driverSeatbeltWarning")
        VehicleKey.TIRE_PRESSURE -> listOf("tire.frontLeft", "tire.frontRight", "tire.rearLeft", "tire.rearRight")
        else -> listOf(key.key)
    }
    fun selected(settings: RecordSettings) = VehicleKey.entries.filter { it.minimalSet || it.key in settings.extraVehicleGroups }
}

/** 纯快照适配器，推/拉同源；每次读取按服务的单调时钟重算新鲜度，断线不显示旧有效值。 */
class AutoVehicleFeed(private val now: () -> Long) : VehicleFeed {
    private var values = Bundle()
    private var connected = false
    private var reason = "等待 AutoService 授权与订阅"
    private var interval = 500
    @Synchronized fun accept(bundle: Bundle) {
        VehicleKey.entries.flatMap(RecordVehicleAdapter::keys).distinct().forEach { key ->
            val next = bundle.getBundle(key)?.let(VehicleSignal::parse)?.takeIf { it.key == key } ?: return@forEach
            val old = values.getBundle(key)?.let(VehicleSignal::parse)
            if (old == null || next.epoch > old.epoch || (next.epoch == old.epoch && next.receivedAt >= old.receivedAt)) {
                values.putBundle(key, bundle.getBundle(key)!!.deepCopy())
            }
        }
    }
    @Synchronized fun connected(value: Boolean, message: String = "") {
        connected = value; reason = message
        if (!value) values = Bundle()
    }
    @Synchronized override fun snapshot(): VehicleSnapshot {
        val at = now()
        return VehicleSnapshot(VehicleKey.entries.associateWith { key ->
            val names = RecordVehicleAdapter.keys(key)
            val signals = names.mapNotNull { name -> values.getBundle(name)?.let(VehicleSignal::parse) }
            val status = when {
                !connected -> VehicleValueStatus.UNAVAILABLE
                names.isEmpty() -> VehicleValueStatus.UNAVAILABLE
                signals.size != names.size -> VehicleValueStatus.UNKNOWN
                signals.any { it.effectiveStatus(at) == SignalStatus.STALE } -> VehicleValueStatus.STALE
                signals.any { it.effectiveStatus(at) != SignalStatus.VALID || it.value == null } -> VehicleValueStatus.UNAVAILABLE
                else -> VehicleValueStatus.LIVE
            }
            // 缺失/不可用不补零；过期值也不伪装当前值，回放原始记录另走历史模型。
            val value = if (status == VehicleValueStatus.LIVE) {
                if (key == VehicleKey.ADAS_LATERAL) when (signals.single().value?.toDoubleOrNull()) {
                    0.0, 1.0 -> "off"
                    2.0 -> "active"
                    else -> "—"
                } else signals.joinToString(" / ") { it.value.orEmpty() }
            } else "—"
            VehicleValue(key, value,
                status, signals.minOfOrNull { it.receivedAt } ?: 0)
        }, connected, interval, reason)
    }
    @Synchronized override fun setInterval(intervalMs: Int) { interval = intervalMs }
    @Synchronized override fun release() { connected(false, "已停止订阅") }
}
