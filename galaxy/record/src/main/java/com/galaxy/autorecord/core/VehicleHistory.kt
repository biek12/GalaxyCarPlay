package com.galaxy.autorecord.core

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 单个历史车辆样本。
 *
 * @param timeMs 相对片段起点的毫秒偏移（统一会话时间轴）
 * @param values 键 → 原始值（保留单位与来源语义，不做换算）
 */
data class VehicleSample(
    val timeMs: Long,
    val values: Map<String, String>,
)

/**
 * `vehicles.jsonl` 读取（P3 历史数据条、P4 数据项数）。
 *
 * 格式（录制侧写入，一行一个样本）：
 * `{"t":1200,"v":{"displaySpeed":42,"gear":"D"}}`
 * 容错：时间字段接受 `t`/`ts`/`time`；值既接受 `v` 子对象也接受扁平键。
 *
 * 纪律（规格 R05-3/4）：回放**只读录像当时保存的样本**，不叠加当前实时值；
 * 找不到样本就返回"缺失"，不补零、不跨断流区间无限沿用。
 */
object VehicleHistory {
    private const val TAG = "AutoRecordVehHistory"

    /** 读取上限：单段最多 60 秒 / 250ms 间隔 ≈ 240 条，留 10 倍余量防止异常文件把内存打满。 */
    private const val MAX_SAMPLES = 4000

    fun read(segmentDir: File): List<VehicleSample> {
        val file = File(segmentDir, SegmentLayout.FILE_VEHICLES)
        if (!file.exists()) return emptyList()
        val samples = mutableListOf<VehicleSample>()
        runCatching {
            file.useLines { lines ->
                lines.takeWhile { samples.size < MAX_SAMPLES }.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEach
                    parseLine(trimmed)?.let { samples += it }
                }
            }
        }.onFailure { Log.w(TAG, "读取 vehicles.jsonl 失败", it) }
        return samples.sortedBy { it.timeMs }
    }

    private fun parseLine(line: String): VehicleSample? = runCatching {
        val json = JSONObject(line)
        val time = when {
            json.has("t") -> json.optLong("t")
            json.has("ts") -> json.optLong("ts")
            json.has("time") -> json.optLong("time")
            else -> 0L
        }
        val values = mutableMapOf<String, String>()
        val nested = json.optJSONObject("v")
        if (nested != null) {
            nested.keys().forEach { key -> values[key] = nested.optString(key) }
        } else {
            json.keys().forEach { key ->
                if (key !in setOf("t", "ts", "time")) values[key] = json.optString(key)
            }
        }
        VehicleSample(time, values)
    }.getOrNull()

    /** 取 `timeMs` 之前最近的一个样本；超过 [maxAgeMs] 视为缺失（不无限沿用）。 */
    fun sampleAt(samples: List<VehicleSample>, timeMs: Long, maxAgeMs: Long): VehicleSample? {
        if (samples.isEmpty()) return null
        val candidate = samples.lastOrNull { it.timeMs <= timeMs } ?: return null
        return if (timeMs - candidate.timeMs <= maxAgeMs) candidate else null
    }
}
