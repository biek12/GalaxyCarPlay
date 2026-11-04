package com.galaxy.autorecord.ui

import android.view.View
import android.widget.LinearLayout
import com.galaxy.autorecord.core.BitratePlan
import com.galaxy.autorecord.core.CodecCapabilities
import com.galaxy.autorecord.core.EncoderInfo
import com.galaxy.autorecord.core.RecordSettings

/**
 * P7 编码与画质（本轮真实实现）。
 *
 * 纪律（规格 §6、§6.1）：
 * - 硬/软/未知三态一律用 [EncoderInfo.implementationLabel]（来自 isHardwareAccelerated/isSoftwareOnly），
 *   不按组件名前缀猜；
 * - H.265 用 [CodecCapabilities.hasEncoder] 判定，本机没有就把选项置灰并写明原因；
 * - 找到的实际编码器用 [CodecCapabilities.pick]，为 null 时报错并**保持原设置，不回退到另一种实现**；
 * - 尺寸用 [CodecCapabilities.alignAndCheck] 显示对齐后的真实值；
 * - 码率只用 [BitratePlan] 估算并标注"估算"，不写成实测；
 * - 文案不写任何能力承诺（是否好用要等本车实测），也不写"已实测通过"。
 */
internal class CodecSettingsPage(private val host: RecordActivity, recordUi: RecordUi) : RecordPage(host, recordUi) {
    override val title: String = "编码与画质"

    private class ProbeResult(
        val avc: List<EncoderInfo>,
        val hevc: List<EncoderInfo>,
        val hevcAvailable: Boolean,
        val encoder: EncoderInfo?,
        val requestWidth: Int,
        val requestHeight: Int,
        val alignedWidth: Int,
        val alignedHeight: Int,
        val alignedAccepted: Boolean,
        val atMillis: Long,
    )

    private lateinit var codecRow: SettingRow
    private lateinit var implRow: SettingRow
    private lateinit var resolutionRow: SettingRow
    private lateinit var fpsRow: SettingRow
    private lateinit var bitrateRow: SettingRow
    private lateinit var gopRow: SettingRow
    private lateinit var audioRow: SettingRow
    private lateinit var encoderRow: SettingRow
    private lateinit var sizeRow: SettingRow
    private lateinit var probeRow: SettingRow
    private lateinit var encoderListContainer: LinearLayout

    private var probe: ProbeResult? = null
    private var probing = false

    override fun build(): View = ui.pageScroll { column ->
        column.addView(ui.sectionTitle("画质预设"), LinearLayout.LayoutParams(-1, -2))
        column.addView(buildPresetCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })

        column.addView(ui.sectionTitle("视频格式"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        column.addView(buildFormatCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })

        column.addView(ui.sectionTitle("编码实现"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        column.addView(buildImplCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })

        column.addView(ui.sectionTitle("画质"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        column.addView(buildQualityCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })

        column.addView(ui.sectionTitle("当前生效组合"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        column.addView(buildCapabilityCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        column.addView(
            ui.chip("查看完整编码器清单（诊断）", false) { host.openSubPage(SubPage.DIAGNOSE) },
            LinearLayout.LayoutParams(-2, ui.dp(44)).apply { topMargin = ui.dp(8) },
        )

        column.addView(
            ui.note(
                "编码器信息全部来自本机 MediaCodecList 的只读枚举；「编码器创建成功」不等于录制成功，" +
                    "只有出帧 + 写盘 + 段收尾改名完成才算。",
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) },
        )
        probe(reason = null)
    }

    /**
     * 画质预设（v1.1 RV-06）：把"分辨率/帧率/码率"的组合收成三档，并在选择处直接显示
     * 四路合计码率与可回溯时长——让用户看见"画质 ↔ 能存多久"的交换，而不是给四个旋钮。
     * 预设是**写入器不是状态**：选择即批量写入既有字段，不新增持久化键。
     */
    private fun buildPresetCard(): View {
        val card = ui.card()
        val body = ui.column().apply { setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12)) }
        card.addView(body, LinearLayout.LayoutParams(-1, -2))

        val current = currentPreset()
        val row = WrapRow(ui.context, ui.dp(8), ui.dp(8))
        PRESETS.forEach { preset ->
            row.addView(ui.chip(preset.label, current == preset.id) { applyPreset(preset) }, LinearLayout.LayoutParams(-2, ui.dp(44)))
        }
        body.addView(row, LinearLayout.LayoutParams(-1, -2))

        val quota = host.settings.effectiveQuotaBytes(0L)
        PRESETS.forEach { preset ->
            val bps = com.galaxy.autorecord.core.RetentionPlan.totalBps(preset.height, preset.fps, RecordSettings.BITRATE_AUTO)
            val millis = com.galaxy.autorecord.core.RetentionPlan.backtraceMillis(quota, preset.height, preset.fps, RecordSettings.BITRATE_AUTO)
            body.addView(
                ui.text(
                    "${preset.label}：四路合计 ${String.format(java.util.Locale.US, "%.1f Mbps", bps / 1_000_000.0)}" +
                        " · 32 GB ${com.galaxy.autorecord.core.RetentionPlan.durationLabel(millis)}（估算）",
                    RecordRole.DESCRIPTION,
                ),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) },
            )
        }
        body.addView(
            ui.note("预设只改分辨率与帧率；码率仍按自动档计算，高档码率要等介质写入吞吐实测后开放。"),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) },
        )
        return card
    }

    private data class Preset(val id: String, val label: String, val height: Int, val fps: Int)

    private fun currentPreset(): String? {
        val s = host.settings
        return PRESETS.firstOrNull { it.height == s.requestedHeight && it.fps == s.fps }?.id
    }

    private fun applyPreset(preset: Preset) {
        host.updateSettings { it.copy(requestedHeight = preset.height, fps = preset.fps) }
        // 预设会同时改多个字段，直接重建页面比逐行回填更不容易出现"显示与设置不一致"
        host.recreate()
    }

    private fun buildFormatCard(): View {
        val card = ui.card()
        codecRow = SettingRow(ui, "编码格式", "H.264 默认；H.265 需本机报告 HEVC 编码器").apply {
            clickableRow("选择编码格式") { openCodecDialog() }
        }
        audioRow = SettingRow(ui, "音轨", "首版默认关闭，是否录音待用户确认")
        audioRow.setValue(if (host.settings.audio) "开启" else "关闭")
        val container = SettingRow(ui, "封装", "")
        container.setValue("MP4")
        card.addView(codecRow, LinearLayout.LayoutParams(-1, -2))
        addSeparator(card)
        card.addView(container, LinearLayout.LayoutParams(-1, -2))
        addSeparator(card)
        card.addView(audioRow, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun buildImplCard(): View {
        val card = ui.card()
        implRow = SettingRow(
            ui, "编码实现", "软件编码通常占用更多 CPU；支持路数和帧率以设备能力与实测为准",
        ).apply {
            clickableRow("选择编码实现") { openImplDialog() }
        }
        card.addView(implRow, LinearLayout.LayoutParams(-1, -2))
        val noteBody = ui.column().apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(14)) }
        noteBody.addView(
            ui.text(
                "四路并发编码需要单独实测。设备未报告所选类型的编码器时会明确报错，" +
                    "不会自动切换为另一种编码实现。",
                RecordRole.DESCRIPTION,
            ),
            LinearLayout.LayoutParams(-1, -2),
        )
        card.addView(noteBody, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun buildQualityCard(): View {
        val card = ui.card()
        resolutionRow = SettingRow(ui, "分辨率", "默认跟随摄像头原生；只降采样，不上采样放大").apply {
            clickableRow("选择分辨率") { openResolutionDialog() }
        }
        fpsRow = SettingRow(ui, "帧率", "超出摄像头与编码器范围的取值会被本机拒绝").apply {
            clickableRow("选择帧率") { openFpsDialog() }
        }
        bitrateRow = SettingRow(ui, "码率", "按分辨率/帧率估算；高档位待介质写入吞吐实测后固化").apply {
            clickableRow("选择码率") { openBitrateDialog() }
        }
        gopRow = SettingRow(ui, "关键帧间隔", "影响拖动 seek 精度").apply {
            clickableRow("选择关键帧间隔") { openGopDialog() }
        }
        listOf(resolutionRow, fpsRow, bitrateRow, gopRow).forEachIndexed { index, row ->
            if (index > 0) addSeparator(card)
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        return card
    }

    private fun buildCapabilityCard(): View {
        val card = ui.card()
        val header = ui.column().apply { setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(6)) }
        header.addView(ui.text("候选编码器清单", RecordRole.LABEL), LinearLayout.LayoutParams(-1, -2))
        header.addView(
            ui.text("名称 + 硬/软/未知 + 是否别名；未知表示系统既未标记硬件也未标记软件", RecordRole.DESCRIPTION),
            LinearLayout.LayoutParams(-1, -2),
        )
        card.addView(header, LinearLayout.LayoutParams(-1, -2))
        encoderListContainer = ui.column()
        card.addView(encoderListContainer, LinearLayout.LayoutParams(-1, -2))
        addSeparator(card)
        encoderRow = SettingRow(ui, "将使用的实际编码器", "按当前编码格式 + 实现选出的组件名")
        card.addView(encoderRow, LinearLayout.LayoutParams(-1, -2))
        addSeparator(card)
        sizeRow = SettingRow(ui, "请求尺寸 / 对齐后尺寸", "编码前必须按编码器的宽高对齐修正")
        card.addView(sizeRow, LinearLayout.LayoutParams(-1, -2))
        addSeparator(card)
        probeRow = SettingRow(ui, "探测", "只在你点按或换选项时运行，不常驻后台")
        card.addView(probeRow, LinearLayout.LayoutParams(-1, -2))
        val buttonBody = ui.column().apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(16)) }
        buttonBody.addView(
            ui.secondaryButton("重新探测") { probe(reason = "手动") },
            LinearLayout.LayoutParams(-2, ui.dp(48)),
        )
        card.addView(buttonBody, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun addSeparator(card: LinearLayout) {
        card.addView(ui.rowSeparator(), LinearLayout.LayoutParams(-1, ui.dp(1).coerceAtLeast(1)).apply {
            marginStart = ui.dp(20)
            marginEnd = ui.dp(20)
        })
    }

    // ---- 探测 ----

    /** 探测只做只读枚举；不创建编码器、不打开摄像头。 */
    private fun probe(reason: String?) {
        if (probing) return
        probing = true
        val snapshot = host.settings
        probeRow.setValue("探测中…")
        probeRow.setFeedback("")
        host.io.run({
            val avc = CodecCapabilities.encoders(RecordSettings.CODEC_H264)
            val hevc = CodecCapabilities.encoders(RecordSettings.CODEC_H265)
            val hevcAvailable = CodecCapabilities.hasEncoder(RecordSettings.CODEC_H265)
            val encoder = CodecCapabilities.pick(snapshot.codecMime, snapshot.encoderImplementation)
            val request = requestSize(snapshot.requestedHeight)
            val aligned = if (encoder != null && request != null) {
                CodecCapabilities.alignAndCheck(encoder, request[0], request[1], snapshot.fps)
            } else {
                null
            }
            val alignedWidth = when {
                aligned != null -> aligned[0]
                encoder != null && request != null -> CodecCapabilities.alignDown(request[0], encoder.widthAlignment)
                else -> 0
            }
            val alignedHeight = when {
                aligned != null -> aligned[1]
                encoder != null && request != null -> CodecCapabilities.alignDown(request[1], encoder.heightAlignment)
                else -> 0
            }
            ProbeResult(
                avc = avc,
                hevc = hevc,
                hevcAvailable = hevcAvailable,
                encoder = encoder,
                requestWidth = request?.get(0) ?: 0,
                requestHeight = request?.get(1) ?: 0,
                alignedWidth = alignedWidth,
                alignedHeight = alignedHeight,
                alignedAccepted = aligned != null,
                atMillis = System.currentTimeMillis(),
            )
        }) { result ->
            probing = false
            result.onSuccess {
                probe = it
                renderAll(reason)
            }.onFailure {
                probeRow.setValue("探测失败")
                probeRow.setFeedback("枚举编码器失败：${it.javaClass.simpleName} ${it.message ?: ""}", error = true)
            }
        }
    }

    private fun requestSize(height: Int): IntArray? = when (height) {
        0 -> null
        1080 -> intArrayOf(1920, 1080)
        720 -> intArrayOf(1280, 720)
        else -> intArrayOf(height * 16 / 9, height)
    }

    // ---- 渲染 ----

    private fun renderAll(reason: String?) {
        val settings = host.settings
        val current = probe
        val mime = settings.codecMime
        codecRow.setValue(RecordFmt.codecLabel(mime))
        codecRow.setDescription(
            when {
                current == null -> "尚未取得本机编码器清单"
                mime == RecordSettings.CODEC_H265 && !current.hevcAvailable -> "本机未报告 HEVC 编码器"
                else -> "H.264 默认；H.265 需本机报告 HEVC 编码器"
            },
        )
        implRow.setValue(RecordFmt.implLabel(settings.encoderImplementation))
        resolutionRow.setValue(RecordFmt.resolvedLabel(settings.requestedHeight))
        fpsRow.setValue("${settings.fps} fps")
        gopRow.setValue("${settings.gopSeconds} 秒")
        renderBitrate()
        renderEncoderList()
        renderEncoderRow()
        renderSizeRow()
        if (reason != null) {
            probeRow.setValue(if (current == null) "无结果" else "完成")
            probeRow.setFeedback(
                "已重新探测（$reason）：${current?.let { summarize(it, mime) } ?: "无结果"}；" +
                    "时间 ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}",
            )
        } else if (current != null) {
            probeRow.setValue("完成")
        }
    }

    private fun summarize(result: ProbeResult, mime: String): String {
        val list = if (mime == RecordSettings.CODEC_H265) result.hevc else result.avc
        return "本机报告 ${list.size} 个候选编码器"
    }

    private fun renderBitrate() {
        val settings = host.settings
        val current = probe
        val label = when (settings.bitrateMode) {
            RecordSettings.BITRATE_LOW -> "低"
            RecordSettings.BITRATE_MID -> "中"
            RecordSettings.BITRATE_HIGH -> "高"
            else -> "自动"
        }
        if (current == null || current.requestWidth == 0 || current.requestHeight == 0) {
            bitrateRow.setValue(label)
            bitrateRow.setDescription("跟随摄像头原生尺寸：原生分辨率尚未取得，四路合计留空（按原生尺寸计算）")
            return
        }
        val trackBps = BitratePlan.forMode(settings.bitrateMode, current.requestWidth, current.requestHeight, settings.fps)
        val total = BitratePlan.fourTrackTotal(trackBps)
        bitrateRow.setValue("$label · ${RecordFmt.mbps(trackBps)}/路")
        bitrateRow.setDescription("四路合计估算 ${RecordFmt.mbps(total)}；估算值，非实测")
    }

    private fun renderEncoderList() {
        encoderListContainer.removeAllViews()
        val mime = host.settings.codecMime
        val current = probe
        if (current == null) {
            encoderListContainer.addView(
                paddedText("尚未探测：点「重新探测」枚举本机编码器", warning = false),
                LinearLayout.LayoutParams(-1, -2),
            )
            return
        }
        val list = if (mime == RecordSettings.CODEC_H265) current.hevc else current.avc
        if (list.isEmpty()) {
            encoderListContainer.addView(
                paddedText("本机未报告 ${RecordFmt.codecLabel(mime)} 的编码器", warning = true),
                LinearLayout.LayoutParams(-1, -2),
            )
            return
        }
        list.forEachIndexed { index, encoder ->
            val row = SettingRow(
                ui,
                encoder.name,
                "${encoder.implementationLabel} · ${if (encoder.alias) "别名组件" else "非别名"} · " +
                    "对齐 ${encoder.widthAlignment}×${encoder.heightAlignment} · profile ${encoder.profileCount}",
            )
            encoderListContainer.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (index < list.lastIndex) addSeparator(encoderListContainer)
        }
    }

    private fun renderEncoderRow() {
        val current = probe
        val settings = host.settings
        val encoder = current?.encoder
        if (current == null) {
            encoderRow.setValue("—")
            encoderRow.setFeedback("")
            return
        }
        if (encoder == null) {
            encoderRow.setValue("无")
            encoderRow.setDescription("")
            encoderRow.setFeedback(
                "本机未报告可用的 ${RecordFmt.implLabel(settings.encoderImplementation)} 编码器" +
                    "（${RecordFmt.codecLabel(settings.codecMime)}）：不会自动回退到另一种实现，请改选编码格式或实现",
                error = true,
            )
        } else {
            // 值槽只放短状态；组件全名放副标题，长名换行显示而不是被压缩截断。
            encoderRow.setValue(encoder.implementationLabel)
            encoderRow.setDescription(encoder.name)
            encoderRow.setFeedback("别名组件：${if (encoder.alias) "是" else "否"}")
        }
    }

    private fun renderSizeRow() {
        val current = probe ?: run {
            sizeRow.setValue("—")
            return
        }
        if (current.requestWidth == 0) {
            sizeRow.setValue("跟随原生")
            sizeRow.setFeedback("原生尺寸要等摄像头能力接入后才有真实值，本页不猜")
            return
        }
        val request = "${current.requestWidth}×${current.requestHeight}"
        sizeRow.setValue("$request → ${current.alignedWidth}×${current.alignedHeight}")
        sizeRow.setFeedback(
            if (current.alignedAccepted) {
                "编码器接受了该尺寸与帧率组合（不含实际出帧验证）"
            } else {
                "该编码器未接受 ${request}@${host.settings.fps}fps：对齐后为 " +
                    "${current.alignedWidth}×${current.alignedHeight}，改分辨率或帧率后才能用"
            },
            error = !current.alignedAccepted,
        )
    }

    private fun paddedText(value: String, warning: Boolean): View =
        ui.text(value, RecordRole.DESCRIPTION).apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(12))
            if (warning) setTextColor(ui.warning)
        }

    // ---- 交互 ----

    private fun openCodecDialog() {
        val current = probe
        val hevcAvailable = current?.hevcAvailable ?: false
        val options = listOf(
            ChoiceOption("h264", RecordFmt.codecLabel(RecordSettings.CODEC_H264)),
            ChoiceOption(
                "h265",
                RecordFmt.codecLabel(RecordSettings.CODEC_H265),
                enabled = hevcAvailable,
                reason = if (hevcAvailable) "" else "本机未报告 HEVC 编码器，选中也无法创建，故置灰",
            ),
        )
        val currentId = if (host.settings.codecMime == RecordSettings.CODEC_H265) "h265" else "h264"
        ui.choiceDialog("编码格式", options, currentId) { id ->
            val mime = if (id == "h265") RecordSettings.CODEC_H265 else RecordSettings.CODEC_H264
            host.updateSettings { it.copy(codecMime = mime) }
            codecRow.setFeedback("已保存，下一段生效（当前片段不中断）")
            probe(reason = "编码格式变更")
        }
    }

    private fun openImplDialog() {
        val current = probe
        val mime = host.settings.codecMime
        val softwarePool = (if (mime == RecordSettings.CODEC_H265) current?.hevc else current?.avc)
            ?.filter { it.software } ?: emptyList()
        val hardwarePool = (if (mime == RecordSettings.CODEC_H265) current?.hevc else current?.avc)
            ?.filter { it.hardware && !it.software } ?: emptyList()
        val options = listOf(
            ChoiceOption(
                "hardware",
                "硬件编码",
                enabled = hardwarePool.isNotEmpty(),
                reason = if (hardwarePool.isEmpty()) "本机未报告该格式的硬件编码器" else "",
            ),
            ChoiceOption(
                "software",
                "软件编码",
                enabled = softwarePool.isNotEmpty(),
                reason = if (softwarePool.isEmpty()) {
                    "本机未报告 ${RecordFmt.codecLabel(mime)} 的软件编码器"
                } else {
                    "需自取 YUV、代价更高；路数上限由本车实测决定"
                },
            ),
        )
        val currentId = if (host.settings.encoderImplementation == RecordSettings.IMPL_SOFTWARE) "software" else "hardware"
        ui.choiceDialog("编码实现", options, currentId) { id ->
            val impl = if (id == "software") RecordSettings.IMPL_SOFTWARE else RecordSettings.IMPL_HARDWARE
            host.updateSettings { it.copy(encoderImplementation = impl) }
            implRow.setFeedback("已保存，下一段生效（当前片段不中断）")
            probe(reason = "编码实现变更")
        }
    }

    private fun openResolutionDialog() {
        val options = listOf(
            ChoiceOption(
                "0",
                "跟随摄像头原生（${com.galaxy.autorecord.core.CameraProfile.JPEG_WIDTH}×${com.galaxy.autorecord.core.CameraProfile.JPEG_HEIGHT}）",
                reason = "不缩放，直接使用摄像头实测输出尺寸",
            ),
            ChoiceOption(
                "1080",
                "1080p（1920×1080）",
                enabled = false,
                reason = com.galaxy.autorecord.core.RetentionPlan.unsupportedReason(1080, host.settings.fps) ?: "不支持",
            ),
            ChoiceOption("720", "720p（1280×720）", reason = "下采样，可拉长回溯窗口"),
        )
        ui.choiceDialog("分辨率", options, host.settings.requestedHeight.toString()) { id ->
            host.updateSettings { it.copy(requestedHeight = id.toInt()) }
            resolutionRow.setFeedback("已保存，下一段生效（当前片段不中断）")
            probe(reason = "分辨率变更")
        }
    }

    private fun openFpsDialog() {
        val options = RecordSettings.FPS_OPTIONS.map { fps ->
            val reason = com.galaxy.autorecord.core.RetentionPlan.unsupportedReason(host.settings.requestedHeight, fps)
            ChoiceOption(fps.toString(), "$fps fps", enabled = reason == null, reason = reason ?: "")
        }
        ui.choiceDialog("帧率", options, host.settings.fps.toString()) { id ->
            host.updateSettings { it.copy(fps = id.toInt()) }
            fpsRow.setFeedback("已保存，下一段生效（当前片段不中断）")
            probe(reason = "帧率变更")
        }
    }

    private fun openBitrateDialog() {
        val current = host.settings.bitrateMode
        val options = listOf(
            ChoiceOption(RecordSettings.BITRATE_AUTO, "自动（按分辨率/帧率计算）"),
            ChoiceOption(RecordSettings.BITRATE_LOW, "低（自动的 0.5 倍）"),
            ChoiceOption(RecordSettings.BITRATE_MID, "中（自动的 1 倍）"),
            ChoiceOption(RecordSettings.BITRATE_HIGH, "高（自动的 1.5 倍）"),
        )
        ui.choiceDialog("码率", options, current) { id ->
            host.updateSettings { it.copy(bitrateMode = id) }
            renderBitrate()
            bitrateRow.setFeedback("已保存，下一段生效（当前片段不中断）")
        }
    }

    private fun openGopDialog() {
        val options = RecordSettings.GOP_OPTIONS.map { ChoiceOption(it.toString(), "$it 秒") }
        ui.choiceDialog("关键帧间隔", options, host.settings.gopSeconds.toString()) { id ->
            host.updateSettings { it.copy(gopSeconds = id.toInt()) }
            gopRow.setFeedback("已保存，下一段生效（当前片段不中断）")
        }
    }

    override fun onShown() {
        if (probe == null) probe(reason = null) else renderAll(reason = null)
    }

    private companion object {
        /** 三档预设：只动分辨率与帧率，码率保持自动（高档待实测）。 */
        /**
         * 实测收敛后只剩两档：
         * - 原生只有 1280×800，1080p 不可用；帧率上限 25，30fps 不可用；
         * - 真正的"省空间"杠杆是降到 720p + 15fps（码率约减半，回溯窗口翻倍）。
         * 不设"最高画质"档，因为原生 25fps 已经是上限，再加一档只会与均衡重复。
         */
        val PRESETS = listOf(
            Preset("saver", "省空间", 720, 15),
            Preset("balanced", "均衡（默认）", 0, 25),
        )
    }

}
