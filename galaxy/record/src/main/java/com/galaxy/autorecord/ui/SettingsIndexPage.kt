package com.galaxy.autorecord.ui

import android.view.View
import android.widget.LinearLayout

/**
 * P5 设置一级。
 *
 * 七行按规格 §5 P5：每行 80dp、左侧 32dp 图标、标题 20sp + 副标题 16sp muted、
 * 右侧当前值 + 箭头；行文案必须带当前值摘要（例如"H.264 · 硬件 · 25fps"）。
 *
 * 本轮只有"存储与保存""编码与画质"是真实可用的设置页，其余进入占位页并保持可返回。
 */
internal class SettingsIndexPage(private val host: RecordActivity, recordUi: RecordUi) : RecordPage(host, recordUi) {
    override val title: String = "设置"

    private class RowRef(val page: SubPage, val row: SettingRow)

    private val rows = mutableListOf<RowRef>()

    override fun build(): View = ui.pageScroll { column ->
        if (host.isDriving()) {
            column.addView(
                ui.note("行驶中不可修改设置（当前挡位非 P）。停稳后即可编辑；录制、锁定与回放不受影响。"),
                LinearLayout.LayoutParams(-1, -2),
            )
        }
        val card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2))
        SubPage.entries.forEachIndexed { index, page ->
            val row = SettingRow(ui, page.label, page.subtitle, page.glyph)
            row.clickableRow(page.label) { host.openSubPage(page) }
            rows += RowRef(page, row)
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (index < SubPage.entries.lastIndex) {
                card.addView(ui.rowSeparator(), LinearLayout.LayoutParams(-1, ui.dp(1).coerceAtLeast(1)).apply {
                    marginStart = ui.dp(20)
                    marginEnd = ui.dp(20)
                })
            }
        }
        column.addView(
            ui.text("AutoRecord v0.1.0", RecordRole.DESCRIPTION).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), 0) },
            LinearLayout.LayoutParams(-1, -2),
        )
    }

    /** 行内当前值摘要：只写设置里真实存在的字段，不写尚未接入的能力。 */
    private fun renderValues() {
        val settings = host.settings
        rows.forEach { ref ->
            val value = when (ref.page) {
                SubPage.STORAGE -> host.volumeLabel()
                SubPage.CODEC -> listOf(
                    RecordFmt.codecLabel(settings.codecMime).substringBefore("（"),
                    RecordFmt.implLabel(settings.encoderImplementation).removeSuffix("编码"),
                    "${settings.fps}fps",
                ).joinToString(" · ")
                SubPage.RECORDING -> if (settings.autoOnBoot || settings.autoOnLaunch) "自动录制开" else "自动录制关"
                SubPage.VEHICLE -> "${settings.vehicleIntervalMs} ms"
                SubPage.PLAYBACK -> listOf(
                    if (settings.playbackLayout == "grid") "四宫格" else "单路",
                    "${settings.playbackSpeed.toInt()}×",
                    RecordFmt.themeLabel(settings.themeMode),
                ).joinToString(" · ")
                SubPage.DIAGNOSE -> "按需探测"
                SubPage.ABOUT -> "v0.1.0"
            }
            ref.row.setValue(value)
        }
    }

    override fun onShown() {
        renderValues()
        // 行驶中只读：行控件减弱并禁止点击，但当前值仍可读（不是"点不动又不说原因"）
        rows.forEach { ref ->
            ref.row.setRowEnabled(!host.isDriving())
        }
    }
}
