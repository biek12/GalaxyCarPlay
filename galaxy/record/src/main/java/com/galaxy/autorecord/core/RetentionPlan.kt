package com.galaxy.autorecord.core

import java.util.Locale

/**
 * 保留窗口与容量换算（规格 v1.1 §5 P6 容量口径表、§14.3）。
 *
 * 为什么需要这个对象：配额以 GB 表达对用户没有意义。按技术口径的码率公式换算后，
 * 四路 1080p25 约 16.6 Mbps ≈ 2.07 MB/s，32 GB 只能留约 4.3 小时——**隔夜取证必然丢失**。
 * 所以界面必须显示"可回溯 ≈ X 小时"，并且这个数字只能有一处实现，不允许各页自己算。
 *
 * 全部数值是**估算**（公式估算，未按本车实测校准），界面必须标注"估算"。
 */
object RetentionPlan {

    /**
     * 跟随摄像头原生时的分辨率＝**实测**相机 JPEG 输出 1280×800。
     *
     * 这曾经按 1080p 假设过，是错的：实测本车相机只有 1280×800（见 CameraProfile）。
     * 尺寸差一倍会让"可回溯 ≈ X 小时"给出错误答案，所以统一从 CameraProfile 取。
     */
    const val ASSUMED_NATIVE_HEIGHT = CameraProfile.JPEG_HEIGHT
    const val ASSUMED_NATIVE_WIDTH = CameraProfile.JPEG_WIDTH

    /** 允许的编码高度：0=跟随原生；720=下采样；**1080 不可用**（原生只有 800，禁止上采样）。 */
    val SUPPORTED_HEIGHTS: List<Int> = listOf(0, 720)

    fun isSupportedHeight(height: Int): Boolean = height in SUPPORTED_HEIGHTS

    fun isSupportedFps(fps: Int): Boolean = fps in CameraProfile.FPS_RANGE

    /** 不支持时给用户的**可读原因**（置灰必须带原因，不能只是点不动）。 */
    fun unsupportedReason(height: Int, fps: Int): String? = when {
        !isSupportedHeight(height) ->
            "摄像头 JPEG 输出仅 ${CameraProfile.JPEG_WIDTH}×${CameraProfile.JPEG_HEIGHT}，不支持 ${height}p（禁止上采样）"
        !isSupportedFps(fps) ->
            "实测帧率上限 ${CameraProfile.MAX_FPS}fps，不支持 ${fps}fps"
        else -> null
    }

    /** 受保护配额占配额的比例（默认 50%，待实测固化；见规格 §14.3）。 */
    const val PROTECTED_QUOTA_PERCENT = 50

    /** 高度 → (宽, 高)。只支持 1080p/720p/原生假想 1080p，其余按 16:9 折算。 */
    fun resolution(height: Int): Pair<Int, Int> = when (height) {
        0 -> ASSUMED_NATIVE_WIDTH to ASSUMED_NATIVE_HEIGHT
        1080 -> 1920 to 1080
        720 -> 1280 to 720
        else -> {
            val h = height.coerceAtLeast(2)
            ((h * 16) / 9) to h
        }
    }

    /** 单路码率（bps）：技术口径 §3 的 `0.08 × W × H × fps`，再按档位取系数。 */
    fun trackBps(height: Int, fps: Int, bitrateMode: String): Long {
        val (width, h) = resolution(height)
        return BitratePlan.forMode(bitrateMode, width, h, fps)
    }

    /** 四路合计（bps）。 */
    fun totalBps(height: Int, fps: Int, bitrateMode: String): Long =
        BitratePlan.fourTrackTotal(trackBps(height, fps, bitrateMode))

    /** 一个片段的预估字节数。 */
    fun segmentBytes(segmentSeconds: Int, height: Int, fps: Int, bitrateMode: String): Long =
        totalBps(height, fps, bitrateMode) / 8L * segmentSeconds.coerceAtLeast(1)

    /** 配额能容纳的录像时长（毫秒）。 */
    fun backtraceMillis(quotaBytes: Long, height: Int, fps: Int, bitrateMode: String): Long {
        val bps = totalBps(height, fps, bitrateMode)
        if (bps <= 0L || quotaBytes <= 0L) return 0L
        return quotaBytes * 8_000L / bps
    }

    /** 受保护配额（锁定段 + 事件段共用上限）。 */
    fun protectedQuotaBytes(quotaBytes: Long): Long =
        if (quotaBytes <= 0L) 0L else quotaBytes / 100L * PROTECTED_QUOTA_PERCENT

    /** "≈ 4 小时 20 分"；不足 1 小时只给分钟；为 0 时返回"—"（不显示 0 小时）。 */
    fun durationLabel(millis: Long): String {
        if (millis <= 0L) return "—"
        val totalMinutes = millis / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours <= 0L -> "${minutes} 分"
            minutes == 0L -> "${hours} 小时"
            else -> "${hours} 小时 ${minutes} 分"
        }
    }

    /** 完整可读的保留窗口，例如"可回溯 ≈ 4 小时 20 分（估算）"。 */
    fun backtraceLabel(quotaBytes: Long, height: Int, fps: Int, bitrateMode: String): String {
        val millis = backtraceMillis(quotaBytes, height, fps, bitrateMode)
        if (millis <= 0L) return "可回溯 ≈ —"
        return "可回溯 ≈ ${durationLabel(millis)}（估算）"
    }

    /** 低于 2 小时需要转警示色（规格 §2.2）。 */
    fun isShortWindow(millis: Long): Boolean = millis in 1L..(2L * 60 * 60 * 1000)

    /** 四路合计码率的可读串。 */
    fun totalBpsLabel(height: Int, fps: Int, bitrateMode: String): String =
        String.format(Locale.US, "%.1f Mbps", totalBps(height, fps, bitrateMode) / 1_000_000.0)
}
