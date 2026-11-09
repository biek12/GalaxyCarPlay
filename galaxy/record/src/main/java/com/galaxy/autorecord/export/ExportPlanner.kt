package com.galaxy.autorecord.export

import com.galaxy.autorecord.core.SegmentLayout
import com.galaxy.autorecord.core.SegmentManifest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 导出计划（B5）：跨段 PTS 重排的唯一实现。
 *
 * 规则（页面规格 §14.4-3）：合并导出的 PTS 必须**重排为连续时间轴**，并在旁路
 * `export.json` 写映射表（源段 → 导出时间区间），保证可回溯到原始段；
 * **不得**修改源文件时间来伪造连续；段内相对间隔不变（只做常量平移）。
 *
 * 口径：
 * - 平移量 offset(k) = 前一段导出末 PTS + [STEP_US]（一个采样间隔量级的步进）；
 * - 各路 PTS 同域（段相对），路间首帧偏移**保留**，不归零（需求 §7："不能把四路各自首帧
 *   全部重置为同一个 0"）；
 * - 导出时间区间以毫秒记入 [exportJson]，与文件内 PTS（微秒）同源换算。
 */
object ExportPlanner {

    /** 段间步进：一个 25fps 帧间隔（40ms），保证合并后单调且肉眼无感。 */
    const val STEP_US = 40_000L

    data class SegmentSpan(
        val segmentId: String,
        val offsetUs: Long,
        /** 本段内容在导出时间轴上的时长（微秒，取所选方向最晚末帧；无帧为 0）。 */
        val durationUs: Long,
    ) {
        val exportStartMs: Long get() = offsetUs / 1000
        val exportEndMs: Long get() = (offsetUs + durationUs) / 1000
    }

    data class Plan(
        val spans: List<SegmentSpan>,
        /** 导出总时长（微秒）。 */
        val totalDurationUs: Long,
    ) {
        /** 源样本 PTS（段相对微秒）→ 导出时间轴 PTS（微秒）。 */
        fun toExportPts(segmentIndex: Int, sourcePtsUs: Long): Long =
            spans[segmentIndex].offsetUs + sourcePtsUs
    }

    /**
     * 生成导出计划。
     *
     * @param manifests 与 segments 同序的清单（解析失败的段用 null，时长回退 0 并如实体现在映射里）
     * @param directions 参与导出的方向（单路=1 个；四宫格=4 个）
     */
    fun plan(manifests: List<SegmentManifest?>, directions: List<String>): Plan {
        require(manifests.isNotEmpty()) { "导出计划至少需要一个段" }
        val spans = mutableListOf<SegmentSpan>()
        var cursorUs = 0L
        manifests.forEachIndexed { index, manifest ->
            val durationUs = manifest?.channels
                ?.filter { it.direction in directions && it.status == SegmentManifest.CHANNEL_COMPLETE }
                ?.maxOfOrNull { it.lastPtsUs }
                ?: 0L
            if (index > 0) cursorUs += STEP_US
            spans += SegmentSpan(
                segmentId = manifest?.segmentId ?: "segment-$index",
                offsetUs = cursorUs,
                durationUs = durationUs,
            )
            cursorUs += durationUs
        }
        return Plan(spans, cursorUs)
    }

    /** 读取段清单；文件缺失/损坏返回 null（不猜、不编造时长）。 */
    fun readManifest(segmentDir: File): SegmentManifest? {
        val file = File(segmentDir, SegmentLayout.FILE_MANIFEST)
        if (!file.isFile) return null
        return SegmentManifest.fromJson(runCatching { file.readText() }.getOrNull() ?: return null)
    }

    /** 旁路映射表 `export.json`：源段 → 导出时间区间（可回溯）。 */
    fun exportJson(plan: Plan, directions: List<String>, grid: Boolean, overlay: Boolean): ByteArray {
        val root = JSONObject().apply {
            put("schemaVersion", 1)
            put("layout", if (grid) "grid" else "single")
            put("directions", JSONArray(directions))
            put("overlayData", overlay)
            put("note", "导出时间轴为连续重排；源段未被修改。段内 PTS 相对间隔不变。")
            put("segments", JSONArray().apply {
                plan.spans.forEach { span ->
                    put(JSONObject().apply {
                        put("segmentId", span.segmentId)
                        put("exportStartMs", span.exportStartMs)
                        put("exportEndMs", span.exportEndMs)
                    })
                }
            })
        }
        return root.toString().toByteArray()
    }
}
