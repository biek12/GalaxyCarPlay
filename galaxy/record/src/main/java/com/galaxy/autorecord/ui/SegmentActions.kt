package com.galaxy.autorecord.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.galaxy.autorecord.core.EventReader
import com.galaxy.autorecord.core.SegmentEntry
import com.galaxy.autorecord.core.VehicleHistory
import java.io.File

/**
 * P4 片段操作面板（库与回放共用，底部面板）。
 *
 * v1.1 改动：导出不再是独立页面（原 P14），而是这里的就地弹层——取证时"锁定 → 看事件 → 导出"
 * 应当连贯完成；另新增"事件摘要"，点一条直接 seek 到那一刻。
 */
internal object SegmentActions {

    fun show(host: RecordActivity, entry: SegmentEntry) {
        val ui = host.ui
        val body = ui.column().apply {
            background = ui.rounded(ui.surface, 12)
            setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(16))
        }
        val dialog = Dialog(host)
        fun dismiss() = dialog.dismiss()

        body.addView(
            ui.text(RecordTime.wallTime(entry.startedAtMillis), RecordRole.SECTION_TITLE).apply {
                setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8))
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        body.addView(infoBlock(ui, host, entry), LinearLayout.LayoutParams(-1, -2))

        body.addView(row(ui, "播放", "打开同步回放") {
            dismiss()
            host.markPlayed(entry)
            host.openPlayback(entry.id)
        }, LinearLayout.LayoutParams(-1, -2))

        val locked = entry.locked
        body.addView(row(ui, if (locked) "解除锁定" else "锁定片段", if (locked) "解锁后会被循环覆盖" else "锁定后不参与自动覆盖") {
            dismiss()
            host.toggleLock(entry, !locked)?.let { host.toastLine(it) } ?: host.toastLine(if (locked) "已解锁" else "已锁定")
        }, LinearLayout.LayoutParams(-1, -2))

        body.addView(row(ui, "导出", "单段或跨段、单路或四宫格、可叠加数据条") {
            dismiss()
            showExport(host, entry)
        }, LinearLayout.LayoutParams(-1, -2))

        body.addView(row(ui, "删除", "同时删除四路视频、车辆数据与索引项", danger = true) {
            dismiss()
            if (entry.locked) {
                host.toastLine("该片段已锁定，请先解锁")
                return@row
            }
            RecordDialogs.confirm(host, "删除片段", "将同时删除四路视频、车辆数据与索引项，不可恢复。", "删除") {
                host.deleteSegments(listOf(entry)) { host.toastLine(it) }
            }
        }, LinearLayout.LayoutParams(-1, -2))

        body.addView(row(ui, "关闭", "") { dismiss() }, LinearLayout.LayoutParams(-1, -2))

        dialog.setContentView(ScrollView(host).apply { addView(body) })
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    /** 只读信息：编码器硬/软如实显示，路径做简写（不暴露完整绝对路径）。 */
    private fun infoBlock(ui: RecordUi, host: RecordActivity, entry: SegmentEntry): View {
        val column = ui.column().apply { setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(8)) }
        val lines = listOf(
            "完整度" to entry.completeness.label,
            "时长" to if (entry.durationMillis > 0) RecordTime.clock(entry.durationMillis) else "—",
            "方向" to if (entry.missingDirections.isEmpty()) "四路齐全" else "缺 ${entry.missingDirections.joinToString("/") { directionLabel(it) }}",
            "分辨率" to entry.resolutionSummary.ifEmpty { "—" },
            "编码器" to entry.encoderSummary.ifEmpty { "—" },
            "码率" to entry.bitrateSummary.ifEmpty { "—" },
            "车辆样本" to if (entry.hasVehicleData) "已记录（回放时加载）" else "无",
            "事件" to "${entry.eventCount} 条",
            "事件规则版本" to (entry.ruleVersion ?: "—"),
            "大小" to RecordFmt.bytes(entry.bytes),
            "介质" to entry.volumeLabel,
            "路径" to shortPath(entry.dir.absolutePath),
        )
        lines.forEach { (label, value) ->
            val row = ui.row().apply { minimumHeight = ui.dp(40) }
            row.addView(ui.text(label, RecordRole.DESCRIPTION), LinearLayout.LayoutParams(ui.dp(112), -2))
            row.addView(ui.text(value, RecordRole.VALUE), LinearLayout.LayoutParams(0, -2, 1f))
            column.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        return column
    }

    /**
     * 导出弹层（B5 已接入执行）。
     *
     * 选项：范围（本段/含相邻段）、画面（四宫格/单路）、附加（叠加历史数据条）。
     * 执行走系统文件选择器（SAF）落到用户选定位置；导出只读源段——不改、不删、不移动；
     * 取消/失败清理输出文件并保留导出设置。跨段合并默认一个 MP4 + `export.json` 映射表。
     */
    private fun showExport(host: RecordActivity, entry: SegmentEntry) {
        val ui = host.ui
        val column = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8)) }
        val (prev, next) = host.library.neighbors(entry, host.settings.segmentSeconds)
        val crossSegment = entry.isEventBearing && (prev != null || next != null)

        column.addView(ui.text("导出范围", RecordRole.SECTION_TITLE), LinearLayout.LayoutParams(-1, -2))
        val thisOnly = checkRow(ui, "仅本段（${RecordTime.dayTime(entry.startedAtMillis)}）", checked = !crossSegment)
        val withNeighbors = checkRow(ui, "含相邻段（事件跨段时合并为一个文件）", checked = crossSegment)
        column.addView(thisOnly, LinearLayout.LayoutParams(-1, -2))
        column.addView(withNeighbors, LinearLayout.LayoutParams(-1, -2))

        column.addView(ui.text("画面", RecordRole.SECTION_TITLE), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = ui.dp(12)
        })
        val grid = checkRow(ui, "四宫格（四路拼接）", checked = true)
        val single = checkRow(ui, "单路（前）", checked = false)
        column.addView(grid, LinearLayout.LayoutParams(-1, -2))
        column.addView(single, LinearLayout.LayoutParams(-1, -2))

        column.addView(ui.text("附加", RecordRole.SECTION_TITLE), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = ui.dp(12)
        })
        val overlay = checkRow(ui, "叠加历史数据条（使用录像当时样本）", checked = false)
        column.addView(overlay, LinearLayout.LayoutParams(-1, -2))

        column.addView(
            ui.note(
                "导出只读：不改、不删、不移动原始片段。跨段合并会把 PTS 重排为连续时间轴，" +
                    "并写 export.json 映射表（源段 → 导出时间区间）；取消或失败会清理输出文件。",
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) },
        )

        android.app.AlertDialog.Builder(host)
            .setTitle("导出")
            .setView(ScrollView(host).apply { addView(column) })
            .setNegativeButton("取消", null)
            .setPositiveButton("开始导出") { _, _ ->
                val gridBox = grid as CheckBox
                val singleBox = single as CheckBox
                val useNeighbors = (withNeighbors as CheckBox).isChecked
                val useOverlay = (overlay as CheckBox).isChecked
                if (!gridBox.isChecked && !singleBox.isChecked) {
                    host.toastLine("请选择画面：四宫格或单路")
                    return@setPositiveButton
                }
                val segments = buildList {
                    add(entry)
                    if (useNeighbors) {
                        prev?.let { add(0, it) }
                        next?.let { add(it) }
                    }
                }.sortedBy { it.startedAtMillis }
                val mergeTag = if (segments.size > 1) "-合并" else ""
                host.requestExport(
                    segments = segments.map { it.dir },
                    grid = gridBox.isChecked,
                    direction = "front",
                    overlay = useOverlay,
                    suggestedName = "AutoRecord-${entry.id}$mergeTag.mp4",
                )
            }
            .show()
    }

    private fun checkRow(ui: RecordUi, label: String, checked: Boolean): View {
        val box = CheckBox(ui.context).apply {
            text = label
            isChecked = checked
            textSize = 15f
            setTextColor(ui.textColor)
        }
        return box
    }

    private fun row(ui: RecordUi, title: String, description: String, danger: Boolean = false, onClick: () -> Unit): View {
        val container = ui.column().apply {
            minimumHeight = ui.dp(76)
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ui.rippleColor), null, null,
            )
        }
        val titleView: TextView = ui.text(title, RecordRole.LABEL)
        if (danger) titleView.setTextColor(ui.danger)
        container.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        if (description.isNotEmpty()) {
            container.addView(ui.text(description, RecordRole.DESCRIPTION), LinearLayout.LayoutParams(-1, -2))
        }
        return container
    }
}

/** P13 首次使用引导：一次、可跳过、不阻断；只说明数据来源与边界，不承诺未验证能力。 */
internal object OnboardingDialog {
    fun show(host: RecordActivity, onDone: () -> Unit) {
        val ui = host.ui
        val column = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8)) }
        column.addView(
            ui.text(
                "1. 数据来源\n授权完成后可查看实时画面；车辆数据由 AutoService 按需提供，缺失或过期显示「—」，本应用不联网、不上传。" +
                    "通道未接入时界面会直接写「未接入」，不会显示占位画面。",
                RecordRole.DESCRIPTION,
            ),
            LinearLayout.LayoutParams(-1, -2),
        )
        column.addView(
            ui.text(
                "2. 本地存储规划\n录制内容只存本地。按当前画质，32 GB 配额" +
                    "可回溯 ${host.backtraceLabel(32L * 1_000_000_000L)}（估算，未按实测码率校准）；" +
                    "循环覆盖只删最旧且未锁定、未含事件标记的片段。",
                RecordRole.DESCRIPTION,
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) },
        )
        column.addView(
            ui.text(
                "3. 当前使用范围\n支持四路录制（分片 + 循环覆盖）、同步回放（含车辆数据与事件轨道）与导出（单路/四宫格，可跨段合并）。" +
                    "开启后台录制后切页/退后台不中断；未开启则回到前台才继续。停车监控不在本期范围。",
                RecordRole.DESCRIPTION,
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) },
        )
        column.addView(
            ui.note("录像可能拍到他人与车牌，请勿公开传播他人影像；导出由你主动发起，本应用不做云端备份。"),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) },
        )
        android.app.AlertDialog.Builder(host)
            .setTitle("关于 AutoRecord")
            .setView(ScrollView(host).apply { addView(column) })
            .setNegativeButton("跳过") { _, _ -> onDone() }
            .setPositiveButton("知道了") { _, _ -> onDone() }
            .show()
    }
}
