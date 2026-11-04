package com.galaxy.autorecord.core

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * 编码能力探测。硬/软/未知三态只用 isHardwareAccelerated()/isSoftwareOnly() 判定，
 * 不按 OMX.qcom./c2.android. 名字前缀猜（见技术口径 §1）。
 */
data class EncoderInfo(
    val name: String,
    val mime: String,
    val hardware: Boolean,
    val software: Boolean,
    val alias: Boolean,
    val widthAlignment: Int,
    val heightAlignment: Int,
    val sizeRange: String,
    val fpsRange: String,
    val bitrateRange: String,
    val profileCount: Int,
) {
    /** 界面用语：硬/软/未知三态。 */
    val implementationLabel: String
        get() = when {
            hardware && !software -> "硬件"
            software -> "软件"
            else -> "未知"
        }

    val supportedKey: String get() = "$mime|$implementationLabel"
}

object CodecCapabilities {
    /** 枚举某 MIME 的编码器；任何单个组件的异常不得中断整张表。 */
    fun encoders(mime: String): List<EncoderInfo> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .distinctBy { it.name }
            .mapNotNull { info -> runCatching { toEncoderInfo(info, mime) }
                .onFailure { android.util.Log.w("AutoRecordCodec", "编码器能力读取失败 ${info.name}", it) }.getOrNull() }
    }.getOrElse { android.util.Log.w("AutoRecordCodec", "编码器枚举失败", it); emptyList() }

    fun hasEncoder(mime: String): Boolean = encoders(mime).isNotEmpty()

    fun hardwareEncoders(mime: String): List<EncoderInfo> =
        encoders(mime).filter { it.hardware && !it.software }

    fun softwareEncoders(mime: String): List<EncoderInfo> =
        encoders(mime).filter { it.software }

    /**
     * 选择具体编码器：硬件实现优先取非别名组件；软件实现必须有真正的软件编码器。
     * 找不到时返回 null——调用方必须报错并保持原设置，禁止回退到另一种实现。
     */
    fun pick(mime: String, implementation: String): EncoderInfo? {
        if (implementation !in setOf(RecordSettings.IMPL_HARDWARE, RecordSettings.IMPL_SOFTWARE)) return null
        val all = encoders(mime)
        val pool = if (implementation == RecordSettings.IMPL_SOFTWARE) all.filter { it.software } else all.filter { it.hardware && !it.software }
        return pool.firstOrNull { !it.alias } ?: pool.firstOrNull()
    }

    /**
     * 分辨率是否被该编码器支持：先按对齐修正宽高，再查 VideoCapabilities。
     * 返回对齐后的真实尺寸；不支持时返回 null（由界面置灰并说明原因）。
     */
    fun alignAndCheck(encoder: EncoderInfo, width: Int, height: Int, fps: Int): IntArray? {
        val w = alignDown(width, encoder.widthAlignment)
        val h = alignDown(height, encoder.heightAlignment)
        if (w <= 0 || h <= 0) return null
        val supported = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.name == encoder.name }
                ?.getCapabilitiesForType(encoder.mime)
                ?.videoCapabilities
                ?.areSizeAndRateSupported(w, h, fps.toDouble())
        }.getOrNull() ?: return null
        return if (supported) intArrayOf(w, h) else null
    }

    fun alignDown(value: Int, alignment: Int): Int {
        if (alignment <= 1) return value
        return value - value % alignment
    }

    /** 一份格式贯穿预检与后续编码；码率不静默夹紧，输入 Surface/YUV 按真实能力选择。 */
    fun format(encoder: EncoderInfo, settings: RecordSettings): MediaFormat? = runCatching {
        val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { it.name == encoder.name }
            .getCapabilitiesForType(encoder.mime)
        val (width, height) = RetentionPlan.resolution(settings.requestedHeight)
        val size = alignAndCheck(encoder, width, height, settings.fps) ?: return null
        val video = caps.videoCapabilities ?: return null
        val bitrate = BitratePlan.forMode(settings.bitrateMode, size[0], size[1], settings.fps)
        if (bitrate !in 1..Int.MAX_VALUE.toLong() || !video.bitrateRange.contains(bitrate.toInt())) return null
        val color = listOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            .firstOrNull { it in caps.colorFormats } ?: return null
        MediaFormat.createVideoFormat(encoder.mime, size[0], size[1]).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate.toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, settings.gopSeconds)
        }.takeIf { caps.isFormatSupported(it) }
    }.getOrNull()

    private fun toEncoderInfo(info: MediaCodecInfo, mime: String): EncoderInfo {
        val caps = info.getCapabilitiesForType(mime)
        val video = caps.videoCapabilities
        return EncoderInfo(
            name = info.name,
            mime = mime,
            hardware = info.isHardwareAccelerated,
            software = info.isSoftwareOnly,
            alias = info.isAlias,
            widthAlignment = video?.widthAlignment ?: 2,
            heightAlignment = video?.heightAlignment ?: 2,
            sizeRange = video?.supportedWidths?.toString()?.plus(" × ")?.plus(video.supportedHeights?.toString()) ?: "未报告",
            fpsRange = video?.supportedFrameRates?.toString() ?: "未报告",
            bitrateRange = video?.bitrateRange?.toString() ?: "未报告",
            profileCount = caps.profileLevels?.size ?: 0,
        )
    }
}

/**
 * 码率估算：bps ≈ bpp × W × H × fps，bpp 取 0.08（工程约定，非实测）。
 * 结果必须再按编码器 bitrateRange 夹紧；四路合计用于与介质写入吞吐比较。
 */
object BitratePlan {
    const val BPP = 0.08
    const val TRACKS = 4

    fun autoBps(width: Int, height: Int, fps: Int): Long =
        (BPP * width.toDouble() * height.toDouble() * fps.toDouble()).toLong().coerceAtLeast(500_000L)

    fun forMode(mode: String, width: Int, height: Int, fps: Int): Long {
        val auto = autoBps(width, height, fps)
        val scale = when (mode) {
            RecordSettings.BITRATE_LOW -> 0.5
            RecordSettings.BITRATE_HIGH -> 1.5
            else -> 1.0
        }
        return (auto * scale).toLong()
    }

    fun fourTrackTotal(trackBps: Long): Long = trackBps * TRACKS
}
