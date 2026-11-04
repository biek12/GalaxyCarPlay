package com.galaxy.autorecord.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 片段清单 `segment.json`（段目录 sidecar，真相来源之一）。
 *
 * 依据 docs/AutoRecord编码与存储技术口径-20261103.md §6 与页面规格 §11：
 * - 每方向一条 channel 记录，缺路/部分有效必须显式表达，不能用其他方向顶替；
 * - 记录实际使用的编码器名与硬/软/未知三态（事后可复查"这段到底用什么编的"）；
 * - 事件规则版本随段记录，历史片段按记录值解释，不回算；
 * - 时间同时记墙钟与单调时钟锚点，回放与事件对齐只用单调时钟。
 */
data class SegmentManifest(
    val schemaVersion: Int = SegmentLayout.SCHEMA_VERSION,
    /** 段标识＝段目录名，全局唯一（时间戳 + 段号）。 */
    val segmentId: String,
    val startedAtWallMillis: Long,
    val startedAtMonotonicNanos: Long,
    val durationMillis: Long,
    val state: String,
    val storageVolumeUuid: String?,
    val channels: List<ChannelRecord>,
    val vehicle: VehicleRecord?,
    val events: EventsRecord?,
    val appVersionName: String,
) {
    /** 完整性判定：只有全部方向 complete 且 state=complete 才算完整片段。 */
    val isComplete: Boolean
        get() = state == STATE_COMPLETE && channels.isNotEmpty() && channels.all { it.status == CHANNEL_COMPLETE }

    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("segmentId", segmentId)
        put("startedAtWallMillis", startedAtWallMillis)
        put("startedAtMonotonicNanos", startedAtMonotonicNanos)
        put("durationMillis", durationMillis)
        put("state", state)
        put("storageVolumeUuid", storageVolumeUuid ?: JSONObject.NULL)
        put("appVersionName", appVersionName)
        put("channels", JSONArray().apply {
            channels.forEach { ch ->
                put(JSONObject().apply {
                    put("direction", ch.direction)
                    put("file", ch.file)
                    put("codecMime", ch.codecMime)
                    put("encoderName", ch.encoderName)
                    put("implementation", ch.implementation)
                    put("width", ch.width)
                    put("height", ch.height)
                    put("fps", ch.fps)
                    put("bitrateBps", ch.bitrateBps)
                    put("gopSeconds", ch.gopSeconds)
                    put("frameCount", ch.frameCount)
                    put("firstPtsUs", ch.firstPtsUs)
                    put("lastPtsUs", ch.lastPtsUs)
                    put("status", ch.status)
                    put("missingRanges", JSONArray().apply { ch.missingRanges.forEach { put(it) } })
                })
            }
        })
        put("vehicle", vehicle?.let { v ->
            JSONObject().apply {
                put("intervalMs", v.intervalMs)
                put("keys", JSONArray().apply { v.keys.forEach { put(it) } })
                put("sampleCount", v.sampleCount)
                put("file", v.file)
                put("clockDomain", v.clockDomain)
            }
        } ?: JSONObject.NULL)
        put("events", events?.let { e ->
            JSONObject().apply {
                put("ruleVersion", e.ruleVersion)
                put("count", e.count)
                put("file", e.file)
            }
        } ?: JSONObject.NULL)
    }

    companion object {
        const val STATE_WRITING = "writing"
        const val STATE_COMPLETE = "complete"
        const val STATE_PARTIAL = "partial"
        const val STATE_FAILED = "failed"

        const val CHANNEL_COMPLETE = "complete"
        const val CHANNEL_MISSING = "missing"
        const val CHANNEL_PARTIAL = "partial"

        fun fromJson(raw: String): SegmentManifest? = runCatching {
            val o = JSONObject(raw)
            val channels = o.getJSONArray("channels").let { arr ->
                (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    ChannelRecord(
                        direction = c.getString("direction"),
                        file = c.getString("file"),
                        codecMime = c.getString("codecMime"),
                        encoderName = c.getString("encoderName"),
                        implementation = c.getString("implementation"),
                        width = c.getInt("width"),
                        height = c.getInt("height"),
                        fps = c.getInt("fps"),
                        bitrateBps = c.getLong("bitrateBps"),
                        gopSeconds = c.getInt("gopSeconds"),
                        frameCount = c.getLong("frameCount"),
                        firstPtsUs = c.getLong("firstPtsUs"),
                        lastPtsUs = c.getLong("lastPtsUs"),
                        status = c.getString("status"),
                        missingRanges = c.optJSONArray("missingRanges")?.let { r ->
                            (0 until r.length()).map { r.getLong(it) }
                        } ?: emptyList(),
                    )
                }
            }
            val vehicle = o.optJSONObject("vehicle")?.let { v ->
                VehicleRecord(
                    intervalMs = v.getInt("intervalMs"),
                    keys = v.optJSONArray("keys")?.let { k -> (0 until k.length()).map { k.getString(it) } } ?: emptyList(),
                    sampleCount = v.getLong("sampleCount"),
                    file = v.getString("file"),
                    clockDomain = v.getString("clockDomain"),
                )
            }
            val events = o.optJSONObject("events")?.let { e ->
                EventsRecord(ruleVersion = e.getString("ruleVersion"), count = e.getInt("count"), file = e.getString("file"))
            }
            SegmentManifest(
                schemaVersion = o.getInt("schemaVersion"),
                segmentId = o.getString("segmentId"),
                startedAtWallMillis = o.getLong("startedAtWallMillis"),
                startedAtMonotonicNanos = o.getLong("startedAtMonotonicNanos"),
                durationMillis = o.getLong("durationMillis"),
                state = o.getString("state"),
                storageVolumeUuid = if (o.isNull("storageVolumeUuid")) null else o.getString("storageVolumeUuid"),
                channels = channels,
                vehicle = vehicle,
                events = events,
                appVersionName = o.getString("appVersionName"),
            )
        }.getOrNull()
    }
}

data class ChannelRecord(
    val direction: String,
    val file: String,
    val codecMime: String,
    /** create() 之后回读的真实编码器名，不是请求名。 */
    val encoderName: String,
    /** hardware / software / unknown */
    val implementation: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Long,
    val gopSeconds: Int,
    val frameCount: Long,
    val firstPtsUs: Long,
    val lastPtsUs: Long,
    val status: String,
    /** 缺路区间：[startPtsUs, endPtsUs] 平铺；奇数长度即损坏，按 missing 处理。 */
    val missingRanges: List<Long> = emptyList(),
)

data class VehicleRecord(
    val intervalMs: Int,
    val keys: List<String>,
    val sampleCount: Long,
    val file: String,
    /** 单调时钟域标识，例如 "elapsedRealtimeNanos"。 */
    val clockDomain: String,
)

data class EventsRecord(
    val ruleVersion: String,
    val count: Int,
    val file: String,
)