package com.galaxy.autorecord.ui

import java.util.Locale

/**
 * 界面数值格式（规格 §2.5）。
 *
 * 纪律：未知一律显示"—"，不显示 0，也不把设定值当实际值打印；
 * 容量单位用 GB/MB 一位小数；剩余空间与配额分开显示，不合并成"可用"。
 */
internal object RecordFmt {
    private const val GIB = 1024.0 * 1024.0 * 1024.0
    private const val MIB = 1024.0 * 1024.0

    fun bytes(value: Long): String = when {
        value <= 0L -> "—"
        value >= GIB.toLong() -> String.format(Locale.US, "%.1f GB", value / GIB)
        else -> String.format(Locale.US, "%.0f MB", value / MIB)
    }

    /** 配额档位文字：0 表示自动档。 */
    fun quotaOptionLabel(bytes: Long): String =
        if (bytes <= 0L) "自动（32 GB 与介质可用容量 50% 取小）"
        else String.format(Locale.US, "%d GB", (bytes / GIB.toLong()).toInt())

    fun minFreeLabel(bytes: Long): String =
        String.format(Locale.US, "%d GB", (bytes / GIB.toLong()).toInt())

    fun segmentLabel(seconds: Int): String = "${seconds / 60} 分钟"

    fun mbps(bps: Long): String = String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)

    fun resolvedLabel(height: Int): String = when (height) {
        0 -> "跟随摄像头原生"
        1080 -> "1080p（1920×1080）"
        720 -> "720p（1280×720）"
        else -> "${height}p"
    }

    fun codecLabel(mime: String): String = when (mime) {
        com.galaxy.autorecord.core.RecordSettings.CODEC_H264 -> "H.264（AVC）"
        com.galaxy.autorecord.core.RecordSettings.CODEC_H265 -> "H.265（HEVC）"
        else -> mime
    }

    fun implLabel(impl: String): String = when (impl) {
        com.galaxy.autorecord.core.RecordSettings.IMPL_HARDWARE -> "硬件编码"
        com.galaxy.autorecord.core.RecordSettings.IMPL_SOFTWARE -> "软件编码"
        else -> impl
    }

    fun themeLabel(mode: String): String = when (mode) {
        com.galaxy.autorecord.core.RecordSettings.THEME_SYSTEM -> "跟随系统"
        com.galaxy.autorecord.core.RecordSettings.THEME_LIGHT -> "浅色"
        com.galaxy.autorecord.core.RecordSettings.THEME_DARK -> "深色"
        else -> mode
    }
}

/** 方向键（core 的 front/rear/left/right）到界面中文标签；不另立第二套方向顺序。 */
internal fun directionLabel(direction: String): String = when (direction) {
    "front" -> "前"
    "rear" -> "后"
    "left" -> "左"
    "right" -> "右"
    else -> direction
}