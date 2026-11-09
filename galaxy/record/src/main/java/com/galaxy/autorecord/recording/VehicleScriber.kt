package com.galaxy.autorecord.recording

import com.galaxy.autorecord.core.SegmentLayout
import com.galaxy.autorecord.core.VehicleFeed
import com.galaxy.autorecord.core.VehicleKey
import com.galaxy.autorecord.core.VehicleRecord
import com.galaxy.autorecord.core.VehicleValueStatus
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File

/**
 * 车辆样本落盘：`vehicles.jsonl` 只追加、一行一样本（技术口径 §6：sidecar 是真相）。
 *
 * 格式（与 [com.galaxy.autorecord.core.VehicleHistory] 读取端一致）：
 * `{"t":1200,"v":{"displaySpeed":"42","gear":"D"}}`
 * `t` 为相对片段起点的毫秒偏移；原始样本属用户录像数据，只进本文件，不写诊断日志。
 *
 * 缺失/不可用的键不写（不补零、不用"—"冒充）；断电最多丢尾行（每行 flush）。
 * 同时把样本喂给 [EventDetector] 做事件判定（同一时间轴）。
 */
class VehicleScriber(
    private val segmentDir: File,
    private val segmentStartMonoNanos: Long,
    private val intervalMs: Int,
    private val feed: VehicleFeed,
    private val keys: List<VehicleKey>,
    private val detector: EventDetector,
) {
    private val file = File(segmentDir, SegmentLayout.FILE_VEHICLES)
    private val writer: BufferedWriter = file.bufferedWriter()
    var sampleCount: Long = 0L
        private set

    /** 采样一次（由引擎按 intervalMs 节拍调用）。 */
    fun sample(nowMonoNanos: Long, recorded: com.galaxy.autorecord.core.VehicleSnapshot? = null) {
        val snapshot = recorded ?: runCatching { feed.snapshot() }.getOrNull() ?: return
        val tMs = (nowMonoNanos - segmentStartMonoNanos) / 1_000_000L
        if (tMs < 0) return
        val values = linkedMapOf<String, String>()
        val sampled = JSONObject()
        val quality = JSONObject()
        for (vk in keys) {
            val value = snapshot.values[vk] ?: continue
            quality.put(vk.key, value.status.name)
            if (value.sampledAtMillis > 0) sampled.put(vk.key, value.sampledAtMillis - segmentStartMonoNanos / 1_000_000)
            if (value.status == VehicleValueStatus.UNKNOWN || value.status == VehicleValueStatus.UNAVAILABLE) continue
            if (value.value.isBlank() || value.value == "—") continue
            values[vk.key] = value.value
        }
        if (values.isEmpty() && recorded == null) return
        val line = JSONObject().apply {
            put("t", tMs)
            put("v", JSONObject().apply { values.forEach { (k, v) -> put(k, v) } })
            put("a", sampled); put("q", quality); put("intervalMs", snapshot.intervalMs)
        }.toString()
        synchronized(this) {
            writer.write(line)
            writer.newLine()
            writer.flush() // 断电最多丢尾行
            sampleCount++
        }
        // 事件判定：刹车/油门百分数 + 横向辅助/巡航状态
        detector.onSample(
            tMs = tMs,
            brakePct = EventDetector.parsePercent(snapshot.values[VehicleKey.BRAKE]?.value),
            accelPct = EventDetector.parsePercent(snapshot.values[VehicleKey.ACCELERATOR]?.value),
            adasActive = EventDetector.parseActive(snapshot.values[VehicleKey.ADAS_LATERAL]?.value),
            cruiseActive = EventDetector.parseActive(snapshot.values[VehicleKey.CRUISE_STATE]?.value),
        )
    }

    /** 收尾：关闭文件并返回清单用的车辆记录。 */
    fun close(): VehicleRecord {
        synchronized(this) { runCatching { writer.flush() }; runCatching { writer.close() } }
        return VehicleRecord(
            intervalMs = intervalMs,
            keys = keys.map { it.key },
            sampleCount = sampleCount,
            file = SegmentLayout.FILE_VEHICLES,
            clockDomain = "elapsedRealtimeNanos",
        )
    }
}
