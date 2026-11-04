package com.galaxy.autorecord.storage

import com.galaxy.autorecord.core.ChannelRecord
import com.galaxy.autorecord.core.SegmentManifest
import java.io.File

/**
 * 真实写入器（下一批的 MediaCodec/MediaMuxer writer）在每路收尾后回填的结果。
 *
 * 本批不实现、也不假装接通录像引擎；本数据类只定义"引擎必须交回什么"的契约：
 * 实际帧数、真实 PTS 区间、真实编码器名/实现三态与分辨率，供 [SegmentTransaction] 校验，
 * **不接受仅传一个成功 Boolean 就编造元数据**。
 *
 * `transactionId` 用于事务身份绑定（R1-4）：旧段迟到或他事务的同方向结果不得被本事务接受。
 */
data class ChannelWriteResult(
    /** 所属事务身份；必须与 [SegmentTransaction.transactionId] 一致，否则提交被拒。 */
    val transactionId: String,
    val direction: String,
    /** 实际编码 MIME，例如 "video/avc"。 */
    val codecMime: String,
    /** create() 之后回读的真实编码器名（不是请求名），必须非空。 */
    val encoderName: String,
    /** "hardware" | "software" | "unknown" 三态。 */
    val implementation: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Long,
    /** GOP 秒数，必须 > 0。 */
    val gopSeconds: Int,
    /** 实际写入帧数，必须 > 0。 */
    val frameCount: Long,
    /** 首帧PTS，相对本段startedAtMonotonicNanos的微秒偏移；写入器须由真实帧时间换算，不能填系统uptime或帧号。 */
    val firstPtsUs: Long,
    /** 同一段相对域的末帧PTS；多帧须大于首帧，单帧可相等。 */
    val lastPtsUs: Long,
)

/** 单路提交的判定结果。 */
data class ChannelCommitOutcome(
    val direction: String,
    val succeeded: Boolean,
    /** 失败时的具名原因；成功时为 null。 */
    val reason: String?,
    /** 成功时的真实通道记录；失败时为 null。 */
    val channel: ChannelRecord?,
) {
    val failed: Boolean get() = !succeeded

    companion object {
        fun succeeded(direction: String, channel: ChannelRecord) =
            ChannelCommitOutcome(direction, true, null, channel)

        fun failed(direction: String, reason: String) =
            ChannelCommitOutcome(direction, false, reason, null)
    }
}

/** [SegmentTransaction.finish] / [SegmentTransaction.abort] 的整体结果。 */
data class FinishOutcome(
    val segmentDir: File,
    /** 最终清单 state：complete / partial / failed。 */
    val state: String,
    /** 最终清单是否成功落盘。false 时视频证据仍在，但不得视为 complete。 */
    val manifestWritten: Boolean,
    val completedDirections: List<String>,
    val failedDirections: List<String>,
    /** 失败原因（清单写入失败/异常等）；成功时为 null。 */
    val error: String?,
) {
    val isComplete: Boolean
        get() = state == SegmentManifest.STATE_COMPLETE && manifestWritten
}

/** [SegmentTransaction.begin] 返回的段目录信息。 */
data class SegmentDirInfo(
    val segmentDir: File,
    val directions: List<String>,
)
