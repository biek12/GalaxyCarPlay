package com.galaxy.autorecord.core

import java.io.File

/**
 * 真机录制环境探测（预检阶段 1 的实际检查项）。
 *
 * 每项检查都返回**可读原因**而不是布尔值：界面要显示"哪一路/哪个卷/哪个编码器 + 为什么"，
 * 规格 §2.5 明确错误必须带来源。检查只读，不做任何"顺手把参数改小"的动作。
 */
class DeviceRecordingEnvironment(
    private val volumes: StorageVolumes,
    /** 每次预检都取"当前"来源：CAMERA 权限授予后会在运行时从不可用切到直连。 */
    private val cameraSourceProvider: () -> CameraChannelSource,
    /** 方位标定来源（实测默认假设 A，改数据不改代码）。 */
    private val layoutProvider: () -> CameraLayout = { CameraLayout.DEFAULT },
    /** 录制引擎是否已落地；B3 之前恒为 false。 */
    override val engineAvailable: Boolean = false,
) : RecordingEnvironment {

    override fun channels(): Map<String, ChannelStatus> = cameraSourceProvider().statuses()

    /**
     * 标定必须可用：四路都要映射到实测可用 cameraId 且互不重复。
     * 映射坏了会让四路画面装错方向——这比"少录一路"更严重，所以直接拦在预检。
     */
    override fun checkCameraLayout(): PreflightFailure? {
        val layout = layoutProvider()
        if (layout.isValid) return null
        val bad = layout.badDirections()
        val dup = layout.duplicates()
        val detail = buildString {
            if (bad.isNotEmpty()) append("未映射或映射到不可用 ID：${bad.joinToString("/") { directionLabel(it) }}")
            if (dup.isNotEmpty()) {
                if (isNotEmpty()) append("；")
                append("重复的 cameraId：${dup.joinToString()}")
            }
            if (isEmpty()) append("方位标定不合法")
        }
        return PreflightFailure(null, "camera-layout", detail)
    }

    override fun checkEncoder(settings: RecordSettings): PreflightFailure? {
        RetentionPlan.unsupportedReason(settings.requestedHeight, settings.fps)?.let {
            return PreflightFailure(null, "codec", it)
        }
        val encoders = CodecCapabilities.encoders(settings.codecMime)
        if (encoders.isEmpty()) {
            return PreflightFailure(
                direction = null,
                stage = "codec",
                detail = "本机未报告 ${codecName(settings.codecMime)} 编码器",
            )
        }
        val picked = CodecCapabilities.pick(settings.codecMime, settings.encoderImplementation)
            ?: return PreflightFailure(
                direction = null,
                stage = "codec",
                detail = "有 ${codecName(settings.codecMime)} 编码器，但没有${implName(settings.encoderImplementation)}，" +
                    "按设置拒绝创建（不回退另一种实现）",
            )
        val (width, height) = RetentionPlan.resolution(settings.requestedHeight)
        val aligned = CodecCapabilities.alignAndCheck(picked, width, height, settings.fps)
            ?: return PreflightFailure(
                direction = null,
                stage = "codec",
                detail = "${picked.name} 不支持 ${width}×${height}@${settings.fps}fps",
            )
        if (aligned[0] <= 0 || aligned[1] <= 0) {
            return PreflightFailure(null, "codec", "编码尺寸对齐结果无效")
        }
        CodecCapabilities.format(picked, settings)?.let { return null }
        return PreflightFailure(null, "codec", "编码器不支持所选码率、帧率或输入格式，未自动更换编码器")
    }

    override fun checkStorage(settings: RecordSettings): PreflightFailure? {
        val volume = resolveVolume(settings)
            ?: return PreflightFailure(null, "storage", "找不到目标存储卷，请重新选择存储位置")
        if (!volume.mounted) {
            return PreflightFailure(null, "storage", "「${volume.label}」已移除，未自动切换介质")
        }
        val dir = volume.appDir
            ?: return PreflightFailure(null, "storage", "「${volume.label}」上本应用没有可用目录")
        if (!volume.writable) {
            return PreflightFailure(null, "storage", "「${volume.label}」为只读")
        }
        if (!volumes.probeWritable(dir)) {
            return PreflightFailure(null, "storage", "「${volume.label}」真写探测失败（写 1 字节 + fsync 未通过）")
        }
        val need = RetentionPlan.segmentBytes(
            settings.segmentSeconds,
            settings.requestedHeight,
            settings.fps,
            settings.bitrateMode,
        )
        val quota = settings.effectiveQuotaBytes(volume.totalBytes)
        if (quota < need || quota > volume.totalBytes) {
            return PreflightFailure(null, "storage", "录像配额不足一段或超过目标卷容量，请调整配额与画质")
        }
        if (volume.freeBytes < need + settings.minFreeBytes) {
            return PreflightFailure(
                null,
                "storage",
                "「${volume.label}」剩余不足：需 ${formatBytes(need + settings.minFreeBytes)}，" +
                    "当前 ${formatBytes(volume.freeBytes)}",
            )
        }
        return null
    }

    private fun resolveVolume(settings: RecordSettings): VolumeInfo? {
        val uuid = settings.storageVolumeUuid
        return runCatching { volumes.resolve(uuid) }.getOrNull()
    }

    private fun codecName(mime: String): String = when (mime) {
        RecordSettings.CODEC_H264 -> "H.264"
        RecordSettings.CODEC_H265 -> "H.265"
        else -> mime
    }

    private fun implName(impl: String): String = when (impl) {
        RecordSettings.IMPL_HARDWARE -> "硬件编码器"
        RecordSettings.IMPL_SOFTWARE -> "软件编码器"
        else -> "该实现"
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format(java.util.Locale.US, "%.1f GB", gb)
        else String.format(java.util.Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
    }
}
