package com.galaxy.autorecord.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 驾驶事件（只用于回放定位与保留保护）。
 *
 * **文案红线**：这是"事件标记"，不是碰撞检测，也不做事故认定。产品只按已记录的
 * 制动/油门/横向辅助等信号阈值打标，不推断责任。任何界面与文档不得写成"碰撞/事故/定责"。
 */
enum class EventKind(val label: String, val key: String) {
    BRAKE("刹车", "brake"),
    ACCEL("油门", "accel"),
    ADAS_ON("横向辅助启用", "adas_on"),
    ADAS_OFF("横向辅助退出", "adas_off"),
    CRUISE_ON("巡航启用", "cruise_on"),
    CRUISE_OFF("巡航退出", "cruise_off"),
    ;

    companion object {
        fun of(raw: String?): EventKind? = entries.firstOrNull { it.key == raw }
    }
}

/**
 * 一条事件。区间事件用 [endMs] 表示持续；离散事件（启用/退出）[endMs] == [startMs]。
 *
 * @param peak 区间事件的峰值（刹车/油门百分比）；离散事件为 null
 * @param ruleVersion 产生该事件的规则版本，历史片段按记录值解释，不回算
 */
data class EventMark(
    val kind: EventKind,
    val startMs: Long,
    val endMs: Long,
    val peak: Float? = null,
    val ruleVersion: String = "",
) {
    val isDiscrete: Boolean get() = endMs <= startMs
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    fun timeLabel(): String {
        val totalSeconds = startMs / 1000
        return String.format(java.util.Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}

/**
 * `events.json` 解析（录制侧写入，本侧只读）。
 *
 * 容错口径：文件不存在 = 本段无事件（不是错误）；文件存在但解析失败 = 抛 [IllegalStateException]，
 * 由调用方在 P4 里显示"事件数据损坏"，不得静默当成 0 条。
 */
object EventReader {
    private const val TAG = "AutoRecordEvents"

    fun read(segmentDir: File): List<EventMark> {
        val file = File(segmentDir, SegmentLayout.FILE_EVENTS)
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrElse {
            Log.w(TAG, "读取 events.json 失败", it)
            throw IllegalStateException("事件文件不可读")
        }
        if (text.isBlank()) return emptyList()
        return runCatching {
            // 兼容裸数组与 {ruleVersion, events:[...]} 两种写法，都是本产品自己能产出的形状
            val trimmed = text.trim()
            val array = if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONObject(trimmed).optJSONArray("events") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val kind = EventKind.of(item.optString("type")) ?: continue
                    val start = item.optLong("startMs", 0L)
                    add(
                        EventMark(
                            kind = kind,
                            startMs = start,
                            endMs = item.optLong("endMs", start),
                            peak = if (item.has("peak")) item.optDouble("peak").toFloat() else null,
                            ruleVersion = item.optString("ruleVersion", ""),
                        ),
                    )
                }
            }.sortedBy { it.startMs }
        }.getOrElse {
            Log.w(TAG, "events.json 解析失败", it)
            throw IllegalStateException("事件数据损坏")
        }
    }

    /** 事件摘要用的顺序：先按时间正序，界面自己决定倒序展示。 */
    fun summarize(events: List<EventMark>, limit: Int = 10): List<EventMark> =
        events.sortedByDescending { it.startMs }.take(limit)
}
