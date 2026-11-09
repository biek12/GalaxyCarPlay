package com.galaxy.autorecord.core

/** 源值和源时钟；缺失用null，绝不补0或用插值制造短踩。 */
data class VehicleTracePoint(val timeMs: Long, val value: String?) {
    private val percentValue = value?.trim()?.removeSuffix("%")?.toFloatOrNull()
        ?.takeIf { it.isFinite() && it in 0f..100f }
    val booleanValue: Boolean? = when (value?.trim()?.lowercase()) {
        "true", "on", "active", "enabled" -> true
        "false", "off", "inactive", "disabled" -> false
        else -> when (value?.toDoubleOrNull()) { 1.0 -> true; 0.0 -> false; else -> null }
    }
    fun percent(): Float? = percentValue
}

data class VehicleTrace(val key: VehicleKey, val points: List<VehicleTracePoint>, val maxGapMs: Long) {
    val kind: EventKind = when (key) {
        VehicleKey.BRAKE -> EventKind.BRAKE
        VehicleKey.ACCELERATOR -> EventKind.ACCEL
        VehicleKey.STEERING, VehicleKey.LEFT_TURN, VehicleKey.RIGHT_TURN -> EventKind.TURN
        else -> if (key.group == VehicleGroup.LIGHT) EventKind.LIGHT else EventKind.VEHICLE_STATE
    }
    val pedal = key == VehicleKey.BRAKE || key == VehicleKey.ACCELERATOR
    val binary = key.group == VehicleGroup.LIGHT || key in setOf(VehicleKey.AUTO_HOLD, VehicleKey.PARKING_BRAKE, VehicleKey.ADAS_LATERAL, VehicleKey.CRUISE_STATE)
    fun active(point: VehicleTracePoint): Boolean = when {
        pedal -> (point.percent() ?: 0f) > 0f
        binary -> point.booleanValue == true
        else -> point.value != null
    }
    /** 不使用阈值、最短时长或邻近合并。抬起、缺失、超时分别终止一个动作。 */
    fun intervals(): List<EventMark> {
        val out = mutableListOf<EventMark>()
        var start: Long? = null
        var end = 0L
        var peak: Float? = null
        fun close() {
            start?.let { out += EventMark(kind, it, end.coerceAtLeast(it), peak, "raw_trace_v1",
                mapOf(key.key to (peak?.let { value -> "$value%" } ?: "开启"))) }
            start = null; peak = null
        }
        points.forEachIndexed { index, point ->
            if (start != null && index > 0 && point.timeMs - points[index-1].timeMs > maxGapMs) close()
            if (active(point)) {
                if (start == null) start = point.timeMs
                end = point.timeMs
                if (pedal) point.percent()?.let { peak = maxOf(peak ?: it, it) }
            } else {
                if (start != null && index > 0 && point.timeMs - points[index-1].timeMs <= maxGapMs) end = point.timeMs
                close()
            }
        }
        close()
        return out
    }
}

object VehicleHistoryTracks {
    fun build(samples: List<VehicleSample>, durationMs: Long, intervalMs: Int): List<VehicleTrace> {
        val keys = (samples.flatMap { it.values.keys } + samples.flatMap { it.quality.keys }).toSet()
        return VehicleKey.entries.filter { it.key in keys }.map { key ->
            val points = java.util.TreeMap<Long, VehicleTracePoint>()
            for (sample in samples) {
                val valid = sample.quality[key.key]?.let { it == "LIVE" } ?: (sample.values[key.key] != null)
                val at = if (valid) sample.sampledAtMs[key.key] ?: sample.timeMs else sample.timeMs
                // 段前的已知状态只从本段实际观察时刻起显示，不把迟到值伪造到0秒。
                val time = if (at < 0) sample.timeMs else at
                if (time in 0..durationMs) points[time] = VehicleTracePoint(time, if (valid) sample.values[key.key] else null)
            }
            VehicleTrace(key, points.values.toList(), (intervalMs * 2L + 25).coerceAtLeast(125))
        }
    }
}
