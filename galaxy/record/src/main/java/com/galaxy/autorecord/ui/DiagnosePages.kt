package com.galaxy.autorecord.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import com.galaxy.autorecord.core.CodecCapabilities
import com.galaxy.autorecord.core.RecordLog
import com.galaxy.autorecord.core.RecordSettings
import com.galaxy.autorecord.core.RetentionPlan
import com.galaxy.autorecord.core.SegmentLayout
import com.galaxy.autorecord.core.VehicleKey

/**
 * P11 诊断（只读为主，允许触发的动作必须写明后果）。
 *
 * 为什么把编码器清单放这里而不是设置页：设置页给结论，诊断页给证据（v1.1 RV-06）。
 * 所有实测按钮只在用户点击时运行，不在后台常驻探测。
 */
internal class DiagnosePage(private val host: RecordActivity, recordUi: RecordUi) : RecordPage(host, recordUi) {
    override val title: String = "诊断"

    private lateinit var codecHost: LinearLayout
    private lateinit var storageHost: LinearLayout
    private lateinit var logHost: LinearLayout
    private lateinit var cameraHost: LinearLayout
    private var probing = false

    override fun build(): View = ui.pageScroll { column ->
        column.addView(ui.sectionTitle("连接"), LinearLayout.LayoutParams(-1, -2))
        var card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        addInfoRow(card, "AutoService", if (host.vehicle.connected) "已连接，按需车辆订阅已启用" else host.vehicle.reason, 0, 4)
        addInfoRow(card, "契约版本", host.platformProtocolVersion.takeIf { it > 0 }?.toString() ?: "未连接", 1, 4)
        addInfoRow(
            card, "录制引擎",
            if (host.controller.engine != null) "已接入（B3/B4/B5：录制 + 回放解码 + 导出）"
            else "不可用（模拟器构建未启用录制引擎）",
            2, 4,
        )
        addInfoRow(
            card, "最近段实测",
            com.galaxy.autorecord.recording.EngineDiagnostics.lastSegment?.let { last ->
                "#${last.index} ${last.state} · ${last.encoderName}（${last.implementation}）· " +
                    "${last.width}×${last.height}@${last.fps} · ${last.frameCount} 帧 · " +
                    "成功 ${last.directionsOk.size} 路" +
                    (last.directionsFailed.keys.takeIf { it.isNotEmpty() }?.let { "，失败 ${it.joinToString("、")}" } ?: "")
            } ?: "未实测（尚无录制会话收尾）",
            3, 4,
        )

        column.addView(ui.sectionTitle("摄像头"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        cameraHost = ui.column()
        card.addView(cameraHost, LinearLayout.LayoutParams(-1, -2))
        renderCameras()

        column.addView(ui.sectionTitle("编码"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        codecHost = ui.column()
        card.addView(codecHost, LinearLayout.LayoutParams(-1, -2))
        val probeRow = SettingRow(ui, "重新探测编码器", "只在点击时枚举，不在后台常驻").apply {
            setValue("运行")
            clickableRow("重新探测编码器") { probeEncoders() }
        }
        card.addView(probeRow, LinearLayout.LayoutParams(-1, -2))
        renderCodecHint()

        column.addView(ui.sectionTitle("存储"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        storageHost = ui.column()
        card.addView(storageHost, LinearLayout.LayoutParams(-1, -2))
        val storageProbe = SettingRow(ui, "写入自检", "在目标目录写 1 字节 + fsync + 删除").apply {
            setValue("运行")
            clickableRow("写入自检") { runStorageProbe() }
        }
        card.addView(storageProbe, LinearLayout.LayoutParams(-1, -2))
        val throughputProbe = SettingRow(ui, "写入吞吐实测", "写 8 MB 临时文件计时后删除（决定码率高档上限的依据）").apply {
            setValue("运行")
            clickableRow("写入吞吐实测") { runThroughputProbe() }
        }
        card.addView(throughputProbe, LinearLayout.LayoutParams(-1, -2))
        renderStorage()

        column.addView(ui.sectionTitle("数据与时钟"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        val snapshot = host.vehicle
        addInfoRow(card, "车辆数据源", snapshot.reason, 0, 4)
        addInfoRow(card, "订阅间隔", "${snapshot.intervalMs} ms（最小集 ${VehicleKey.minimal.size} 项）", 1, 4)
        val anchor = com.galaxy.autorecord.recording.EngineDiagnostics.clockAnchor
        addInfoRow(
            card, "会话时钟",
            if (anchor != null) {
                "墙钟 ${anchor.wallMillis} · 单调 ${anchor.monoNanos} ns（段 PTS 与车辆样本同域）"
            } else {
                "未建立（开始一次录制后建立：相机帧时间戳 → 段相对时间轴）"
            },
            2, 4,
        )
        addInfoRow(
            card, "循环覆盖",
            com.galaxy.autorecord.recording.EngineDiagnostics.lastRotationNote ?: "本次会话未触发删除",
            3, 4,
        )

        column.addView(ui.sectionTitle("日志"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        logHost = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)) }
        card.addView(logHost, LinearLayout.LayoutParams(-1, -2))
        val logActions = ui.row().apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(10)) }
        logActions.addView(ui.chip("复制日志", false) { copyLog() }, LinearLayout.LayoutParams(-2, ui.dp(44)))
        logActions.addView(ui.chip("清空", false) {
            RecordLog.clear()
            renderLog()
            host.toastLine("诊断日志已清空")
        }, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginStart = ui.dp(8) })
        card.addView(logActions, LinearLayout.LayoutParams(-1, -2))
        renderLog()

        column.addView(
            ui.note("诊断只读为主：所有探测都在你点击时执行一次，不做后台常驻扫描，也不读取用户录像内容。"),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) },
        )
    }

    private fun addInfoRow(card: LinearLayout, label: String, value: String, index: Int, total: Int) {
        val row = ui.row().apply {
            minimumHeight = ui.dp(64)
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
        }
        row.addView(ui.text(label, RecordRole.LABEL), LinearLayout.LayoutParams(ui.dp(120), -2))
        row.addView(ui.text(value, RecordRole.DESCRIPTION), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
        if (index < total - 1) {
            card.addView(ui.rowSeparator(), LinearLayout.LayoutParams(-1, ui.dp(1).coerceAtLeast(1)).apply {
                marginStart = ui.dp(20)
                marginEnd = ui.dp(20)
            })
        }
    }

    /**
     * 摄像头诊断：来源状态 + **实测能力基线** + **方位标定**（实测建议 §7.1）。
     *
     * 方位为什么要在界面里标定：实测证明 facing 恒 FRONT、sensor_orientation 恒 0、厂商无方位 tag，
     * 所以方位只能靠地面真值确定。把它做成可改的数据，标定后不用改代码。
     */
    private fun renderCameras() {
        cameraHost.removeAllViews()
        val layout = host.cameraLayout
        val statuses = host.cameraSource.statuses()

        cameraHost.addView(
            ui.text("通道来源：${host.cameraSource.label} · CAMERA ${host.cameraPermissionState}", RecordRole.DESCRIPTION).apply {
                setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        cameraHost.addView(
            ui.text("实测能力：${com.galaxy.autorecord.core.CameraProfile.capabilitySummary()}", RecordRole.DESCRIPTION).apply {
                setPadding(ui.dp(20), ui.dp(6), ui.dp(20), 0)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        cameraHost.addView(
            ui.text(
                "别名：cameraId 51 与 2 是同一物理相机（视角一致），已按一路去重；" +
                    "4–50 报 HAL 异常，按缺路证据记录而不是静默跳过。",
                RecordRole.DESCRIPTION,
            ).apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(6)) },
            LinearLayout.LayoutParams(-1, -2),
        )

        cameraHost.addView(
            ui.text("方位标定：${layout.summary()}", RecordRole.LABEL).apply {
                setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        SegmentLayout.DIRECTIONS.forEach { direction ->
            val status = statuses[direction]
            val cameraId = layout.cameraIdOf(direction)
            val row = ui.row().apply {
                minimumHeight = ui.dp(64)
                setPadding(ui.dp(20), ui.dp(6), ui.dp(20), ui.dp(6))
                isClickable = true
                isFocusable = true
                setOnClickListener { pickCameraId(direction, cameraId) }
            }
            row.addView(ui.text(directionLabel(direction), RecordRole.LABEL), LinearLayout.LayoutParams(ui.dp(40), -2))
            row.addView(
                ui.text("cam ${cameraId ?: "—"}", RecordRole.VALUE),
                LinearLayout.LayoutParams(ui.dp(72), -2),
            )
            row.addView(
                ui.text(status?.detail?.ifEmpty { status.state.name } ?: "未知", RecordRole.DESCRIPTION),
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            cameraHost.addView(row, LinearLayout.LayoutParams(-1, -2))
        }

        val actions = ui.row().apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(10)) }
        actions.addView(ui.chip("回到假设 A", false) {
            host.resetCameraLayout()
            renderCameras()
            host.toastLine("已回到实测假设 A（0=左 1=右 2=前 3=后，未标定）")
        }, LinearLayout.LayoutParams(-2, ui.dp(44)))
        actions.addView(ui.chip("确认四路方位", false) {
            RecordDialogs.confirm(host, "确认四路方位", "请先在车外逐路核对画面。确认当前映射：${host.cameraLayout.summary()}？", "确认") {
                host.toastLine(if (host.confirmCameraLayout()) "四路方位已确认" else "标定保存失败")
                renderCameras()
            }
        }, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginStart = ui.dp(8) })
        cameraHost.addView(actions, LinearLayout.LayoutParams(-1, -2))

        cameraHost.addView(
            ui.note(
                "未验证项：" + com.galaxy.autorecord.core.CameraProfile.unverifiedItems().joinToString("；") +
                    "。这些都不能在交付说明里写成已支持。",
            ).apply { setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(10)) },
            LinearLayout.LayoutParams(-1, -2),
        )
    }

    /** 只列已实测 ID；交换前明确展示两个方向并确认，取消不改配置。 */
    private fun pickCameraId(direction: String, current: Int?) {
        val layout = host.cameraLayout
        val options = com.galaxy.autorecord.core.CameraProfile.USABLE_IDS.map { id ->
            val owner = layout.directionOf(id)
            val occupiedByOther = owner != null && owner != direction
            ChoiceOption(
                id = id.toString(),
                label = "cameraId $id",
                reason = when {
                    occupiedByOther -> "将与「${directionLabel(owner!!)}」交换 cameraId，下一步确认"
                    id == 2 -> "实测同时被 51 别名指向"
                    else -> ""
                },
            )
        }
        ui.choiceDialog("「${directionLabel(direction)}」使用哪个 cameraId", options, current?.toString()) { picked ->
            val id = picked.toInt()
            if (id == current) return@choiceDialog
            val other = layout.directionOf(id) ?: return@choiceDialog
            RecordDialogs.confirm(host, "交换摄像头方向", "${directionLabel(direction)}：cam$current → cam$id\n" +
                "${directionLabel(other)}：cam$id → cam$current\n请先核对实际画面，交换后仍需确认四路方位。", "交换") {
                val error = host.updateCameraDirection(direction, id)
                host.toastLine(error ?: "方向已交换，四路方位待核对")
                renderCameras()
            }
        }
    }

    /** 编码器三态：只由 isHardwareAccelerated/isSoftwareOnly 判定，不按名称前缀猜。 */
    private fun probeEncoders() {
        if (probing) return
        probing = true
        RecordLog.add("diagnose", "开始枚举编码器")
        host.io.run({
            listOf(RecordSettings.CODEC_H264, RecordSettings.CODEC_H265).associateWith { mime ->
                CodecCapabilities.encoders(mime)
            }
        }) { result ->
            probing = false
            codecHost.removeAllViews()
            result.getOrElse {
                codecHost.addView(ui.note("枚举失败：${it.message ?: "未知错误"}", error = true), LinearLayout.LayoutParams(-1, -2))
                return@run
            }.forEach { (mime, encoders) ->
                codecHost.addView(
                    ui.text("${RecordFmt.codecLabel(mime)} · ${encoders.size} 个", RecordRole.LABEL).apply {
                        setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(2))
                    },
                    LinearLayout.LayoutParams(-1, -2),
                )
                if (encoders.isEmpty()) {
                    codecHost.addView(
                        ui.text("本机未报告该格式的编码器", RecordRole.DESCRIPTION).apply {
                            setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(6))
                        },
                        LinearLayout.LayoutParams(-1, -2),
                    )
                }
                encoders.forEach { encoder ->
                    val detail = buildString {
                        append(encoder.implementationLabel)
                        if (encoder.alias) append(" · 别名")
                        append(" · 对齐 ${encoder.widthAlignment}/${encoder.heightAlignment}")
                        append(" · ${encoder.sizeRange}")
                    }
                    codecHost.addView(
                        ui.text("· ${encoder.name}", RecordRole.DESCRIPTION).apply {
                            setPadding(ui.dp(20), 0, ui.dp(20), 0)
                        },
                        LinearLayout.LayoutParams(-1, -2),
                    )
                    codecHost.addView(
                        ui.text("   $detail", RecordRole.DESCRIPTION).apply {
                            setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(4))
                        },
                        LinearLayout.LayoutParams(-1, -2),
                    )
                }
            }
            val picked = CodecCapabilities.pick(host.settings.codecMime, host.settings.encoderImplementation)
            codecHost.addView(
                ui.text(
                    if (picked == null) "当前设置将使用的编码器：无（按设置拒绝创建，不回退）"
                    else "当前设置将使用：${picked.name}（${picked.implementationLabel}）",
                    RecordRole.FEEDBACK,
                ).apply { setPadding(ui.dp(20), ui.dp(6), ui.dp(20), ui.dp(10)) },
                LinearLayout.LayoutParams(-1, -2),
            )
            RecordLog.add("diagnose", "编码器枚举完成")
        }
    }

    private fun renderCodecHint() {
        codecHost.addView(
            ui.text(
                "点击「重新探测编码器」枚举本机编码器（含硬/软/未知三态）。" +
                    "四路并发是否成立必须实测，编译通过或创建成功都不算。",
                RecordRole.DESCRIPTION,
            ).apply { setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(10)) },
            LinearLayout.LayoutParams(-1, -2),
        )
    }

    private fun renderStorage() {
        storageHost.removeAllViews()
        storageHost.addView(
            ui.text("读取存储卷…", RecordRole.DESCRIPTION).apply { setPadding(ui.dp(20), ui.dp(10), ui.dp(20), 0) },
            LinearLayout.LayoutParams(-1, -2),
        )
        host.loadVolumes { result ->
            storageHost.removeAllViews()
            val volumes = result.getOrNull()
            if (volumes == null) {
                storageHost.addView(
                    ui.note("卷枚举失败：${result.exceptionOrNull()?.message ?: "未知错误"}", error = true),
                    LinearLayout.LayoutParams(-1, -2),
                )
                return@loadVolumes
            }
            val quota = host.settings.effectiveQuotaBytes(volumes.firstOrNull()?.totalBytes ?: 0L)
            volumes.forEach { volume ->
                val state = when {
                    !volume.mounted -> "已移除"
                    volume.appDir == null -> "不可写（无 App 目录）"
                    !volume.writable -> "只读"
                    else -> "可写"
                }
                storageHost.addView(
                    ui.text(
                        "${volume.label} · ${state} · 剩 ${RecordFmt.bytes(volume.freeBytes)} / " +
                            "共 ${RecordFmt.bytes(volume.totalBytes)}",
                        RecordRole.DESCRIPTION,
                    ).apply { setPadding(ui.dp(20), ui.dp(6), ui.dp(20), 0) },
                    LinearLayout.LayoutParams(-1, -2),
                )
            }
            storageHost.addView(
                ui.text(
                    "配额 ${RecordFmt.bytes(quota)} · ${host.backtraceLabel(quota)} · " +
                        "四路合计 ${RetentionPlan.totalBpsLabel(host.settings.requestedHeight, host.settings.fps, host.settings.bitrateMode)}",
                    RecordRole.FEEDBACK,
                ).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(10)) },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
    }

    private fun runStorageProbe() {
        host.io.run({
            val volumes = host.storageVolumes.list()
            volumes.map { volume ->
                val dir = volume.appDir
                volume.label to (dir != null && volume.mounted && host.storageVolumes.probeWritable(dir))
            }
        }) { result ->
            val outcome = result.getOrNull()
            if (outcome == null) {
                host.toastLine("写入自检失败：${result.exceptionOrNull()?.message ?: "未知错误"}")
                return@run
            }
            val text = outcome.joinToString("；") { (label, ok) -> "$label ${if (ok) "可写" else "不可写"}" }
            RecordLog.add("diagnose", "写入自检：$text")
            host.toastLine("写入自检：$text")
            renderLog()
        }
    }

    /** 写入吞吐实测（技术口径 §7-8）：真写 8 MB + fsync 计时，删临时文件；只在点击时跑。 */
    private fun runThroughputProbe() {
        host.io.run({
            val volumes = host.storageVolumes.list()
            volumes.map { volume ->
                val dir = volume.appDir
                if (dir == null || !volume.mounted) {
                    return@map volume.label to "不可用"
                }
                val tmp = java.io.File(dir, "AutoRecord/.throughput-probe")
                try {
                    val bytes = 8 * 1024 * 1024
                    val buffer = ByteArray(64 * 1024) { 0x55 }
                    val start = android.os.SystemClock.elapsedRealtime()
                    java.io.FileOutputStream(tmp).use { out ->
                        var written = 0
                        while (written < bytes) {
                            out.write(buffer)
                            written += buffer.size
                        }
                        out.flush()
                        out.fd.sync()
                    }
                    val elapsedMs = (android.os.SystemClock.elapsedRealtime() - start).coerceAtLeast(1)
                    val mbps = bytes / 1024.0 / 1024.0 * 1000.0 / elapsedMs
                    volume.label to String.format(java.util.Locale.US, "%.1f MB/s（8 MB / %d ms）", mbps, elapsedMs)
                } finally {
                    tmp.delete()
                }
            }
        }) { result ->
            val outcome = result.getOrNull()
            if (outcome == null) {
                host.toastLine("吞吐实测失败：${result.exceptionOrNull()?.message ?: "未知错误"}")
                return@run
            }
            val text = outcome.joinToString("；") { (label, speed) -> "$label $speed" }
            RecordLog.add("diagnose", "写入吞吐实测：$text")
            host.toastLine("写入吞吐实测：$text")
            renderLog()
        }
    }

    private fun renderLog() {
        logHost.removeAllViews()
        val lines = RecordLog.snapshot()
        if (lines.isEmpty()) {
            logHost.addView(ui.text("暂无日志（诊断日志只在内存，最多 200 行）", RecordRole.DESCRIPTION), LinearLayout.LayoutParams(-1, -2))
            return
        }
        lines.takeLast(20).forEach { line ->
            logHost.addView(ui.text(line, RecordRole.DESCRIPTION), LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun copyLog() {
        val text = RecordLog.snapshot().joinToString("\n").ifEmpty { "（无日志）" }
        val clipboard = host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AutoRecord 诊断日志", text))
        host.toastLine("诊断日志已复制（不含录像内容）")
    }

    override fun onShown() {
        renderCameras()
        renderStorage()
        renderLog()
    }
}

/** P12 关于。 */
internal class AboutPage(private val host: RecordActivity, recordUi: RecordUi) : RecordPage(host, recordUi) {
    override val title: String = "关于"

    override fun build(): View = ui.pageScroll { column ->
        val card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2))
        val rows = listOf(
            "应用" to "AutoRecord 四向行车记录仪",
            "版本" to "0.1.0（开发版）",
            "包名" to "com.galaxy.autorecord（候选，待确认）",
            "模块" to "record · contracts · client",
            "契约版本" to "未绑定 AutoService，暂不显示",
            "数据来源" to "摄像头与车辆数据均由车机 AutoService 提供",
        )
        rows.forEachIndexed { index, (label, value) ->
            val row = ui.row().apply {
                minimumHeight = ui.dp(64)
                setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
            }
            row.addView(ui.text(label, RecordRole.LABEL), LinearLayout.LayoutParams(ui.dp(104), -2))
            row.addView(ui.text(value, RecordRole.DESCRIPTION), LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (index < rows.lastIndex) {
                card.addView(ui.rowSeparator(), LinearLayout.LayoutParams(-1, ui.dp(1).coerceAtLeast(1)).apply {
                    marginStart = ui.dp(20); marginEnd = ui.dp(20)
                })
            }
        }

        column.addView(ui.sectionTitle("隐私边界"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        column.addView(
            ui.text(
                "· 录像只存在本地介质，本应用不联网、不上传、不做云端备份；\n" +
                    "· 导出由你主动发起，导出内容与去向由你决定；\n" +
                    "· 诊断日志只在内存、最多 200 行，不含原始车辆载荷与录像内容；\n" +
                    "· 录像可能拍到他人与车牌，公开传播前请自行确认合规。",
                RecordRole.DESCRIPTION,
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) },
        )

        column.addView(ui.sectionTitle("开源与参考"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        column.addView(
            ui.text(
                "界面沿用项目 FlymeAuto 样式；普通图标采用 Lucide 0.468.0（ISC）；车辆图标含 MDI 7.4.47（Apache-2.0）与 ISO7000-2956 现有矢量（CC0），" +
                    "原始来源清单与许可随应用保存在 assets/licenses。踏板素材：Press Brake Pedal — Mateusz Skrzyniecki；Pedal — LAFS，来自 Noun Project（CC BY 3.0，https://creativecommons.org/licenses/by/3.0/）；仅显示时染色缩放。方向盘、巡航、后雾灯、自动驻车和P图形来自Volvo Cars公开图标目录。" +
                    "EVCam 仅作摄像头接入链的研究参考（GPL-3.0），未迁入任何代码。",
                RecordRole.DESCRIPTION,
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) },
        )
    }
}
