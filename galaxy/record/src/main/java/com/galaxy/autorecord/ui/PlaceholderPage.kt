package com.galaxy.autorecord.ui

import android.view.View
import android.widget.LinearLayout

/**
 * 尚未接入的设置二级页占位（P8/P9/P11/P12，以及 P10 的其余分组）。
 *
 * 为什么要有占位而不是隐藏入口：规格 §3 的信息架构要求设置项可见、可返回；
 * 直接隐藏会让用户以为功能不存在。占位页只说明"缺哪一段、阶段计划"，不提供假开关。
 *
 * P10「回放与显示」的"日夜模式"是 S-UI-01，属于全局界面能力且本轮已经实现，
 * 所以在这一页里做成真实可用的选择行（其余分组仍为占位）。
 */
internal class PlaceholderPage(
    private val host: RecordActivity,
    recordUi: RecordUi,
    private val page: SubPage,
) : RecordPage(host, recordUi) {
    override val title: String = page.label

    private var themeRow: SettingRow? = null

    override fun build(): View = ui.pageScroll { column ->
        column.addView(placeholderCard(), LinearLayout.LayoutParams(-1, -2))
        if (page == SubPage.PLAYBACK) {
            column.addView(ui.sectionTitle("界面"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
            column.addView(themeCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        }
        column.addView(
            ui.secondaryButton("返回设置") { host.backToSettings() },
            LinearLayout.LayoutParams(-2, ui.dp(48)).apply { topMargin = ui.dp(16) },
        )
    }

    private fun placeholderCard(): View {
        val card = ui.card()
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16)) }
        card.addView(body, LinearLayout.LayoutParams(-1, -2))
        body.addView(ui.text("该页待接入（阶段C/D）", RecordRole.LABEL), LinearLayout.LayoutParams(-1, -2))
        body.addView(
            ui.text("本页计划包含：${page.subtitle}。", RecordRole.DESCRIPTION),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) },
        )
        body.addView(
            ui.text(
                "占位页不放任何开关或假状态：在对应能力（车辆数据订阅、事件规则、回放、诊断）真正接入之前，" +
                    "本页不会修改设置，也不会显示未经探测的数值。",
                RecordRole.DESCRIPTION,
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) },
        )
        return card
    }

    private fun themeCard(): View {
        val card = ui.card()
        val row = SettingRow(ui, "日夜模式", "跟随系统 / 浅色 / 深色；切换后重建界面，保留当前页与滚动位置")
        row.setValue(RecordFmt.themeLabel(host.settings.themeMode))
        row.clickableRow("选择日夜模式") {
            val options = listOf(
                ChoiceOption(com.galaxy.autorecord.core.RecordSettings.THEME_SYSTEM, "跟随系统"),
                ChoiceOption(com.galaxy.autorecord.core.RecordSettings.THEME_LIGHT, "浅色"),
                ChoiceOption(com.galaxy.autorecord.core.RecordSettings.THEME_DARK, "深色"),
            )
            ui.choiceDialog("日夜模式", options, host.settings.themeMode) { id -> host.setThemeMode(id) }
        }
        themeRow = row
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    override fun onShown() {
        themeRow?.setValue(RecordFmt.themeLabel(host.settings.themeMode))
    }
}