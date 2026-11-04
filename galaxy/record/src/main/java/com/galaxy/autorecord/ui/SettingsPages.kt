package com.galaxy.autorecord.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.galaxy.autorecord.core.RecordSettings
import com.galaxy.autorecord.core.VehicleKey

/**
 * 设置二级页基类：统一"行驶中只读"的收口（v1.1 §2.3-8）。
 *
 * 判定依据是挡位（非 P 即只读），挡位不可用**不锁**——宁可少限制，也不因为数据缺失把用户挡在外面。
 * 只读时不做成"点不动但没说原因"：顶部给一行明确说明，行控件同时减弱并禁止点击。
 */
internal abstract class SettingsSubPage(
    protected val host: RecordActivity,
    ui: RecordUi,
) : RecordPage(host, ui) {

    /** 构建设置内容的通用外壳：行驶中先插一行说明，最后统一禁用交互控件。 */
    protected fun settingsShell(build: (LinearLayout) -> Unit): View = ui.pageScroll { column ->
        if (host.isDriving()) {
            column.addView(
                ui.note("行驶中不可修改设置（当前挡位非 P）。车辆停稳后即可编辑；录制、锁定与回放不受影响。"),
                LinearLayout.LayoutParams(-1, -2),
            )
        }
        build(column)
        if (host.isDriving()) disableInteraction(column)
    }

    private fun disableInteraction(view: View) {
        if (view is SettingRow) {
            view.setRowEnabled(false)
            view.isClickable = false
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) disableInteraction(view.getChildAt(index))
        }
    }

    protected fun groupCard(column: LinearLayout, title: String? = null, topMargin: Int = 0) {
        if (title != null) {
            column.addView(
                ui.sectionTitle(title),
                LinearLayout.LayoutParams(-1, -2).apply { this.topMargin = ui.dp(topMargin) },
            )
        }
        val card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            this.topMargin = ui.dp(if (title != null) 8 else topMargin)
        })
    }

    protected fun addRow(card: LinearLayout, row: SettingRow, index: Int, total: Int) {
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
        if (index < total - 1) {
            card.addView(ui.rowSeparator(), LinearLayout.LayoutParams(-1, ui.dp(1).coerceAtLeast(1)).apply {
                marginStart = ui.dp(20)
                marginEnd = ui.dp(20)
            })
        }
    }

    /** 通用单选行：值来自设置，点开弹窗选择，未通过校验的选项带原因置灰。 */
    protected fun choiceRow(
        title: String,
        description: String,
        currentLabel: String,
        options: List<ChoiceOption>,
        currentId: String,
        onPick: (String) -> Unit,
    ): SettingRow {
        val row = SettingRow(ui, title, description)
        row.setValue(currentLabel)
        row.clickableRow(title) {
            ui.choiceDialog(title, options, currentId) { picked ->
                options.firstOrNull { it.id == picked }?.let { row.setValue(it.label) }
                onPick(picked)
            }
        }
        return row
    }
}

/** P8 录制与后台。 */
internal class RecordingSettingsPage(host: RecordActivity, recordUi: RecordUi) : SettingsSubPage(host, recordUi) {
    override val title: String = "录制与后台"

    private val autoBoot = switchRow("开机自动录制", "开机后自动开始录制；默认关，需你显式开启")
    private val autoLaunch = switchRow("App 启动后自动录制", "打开 AutoRecord 即开始录制；默认关")
    private val protectDeleting = switchRow("录制中防止误删", "录制中的片段不参与删除与覆盖")
    private val background = switchRow("后台继续录制", "回到桌面后继续录制")
    private val screenOff = switchRow("息屏继续录制", "息屏不终止已开启的录制")

    private fun switchRow(title: String, description: String) = SwitchSettingRow(ui, title, description)

    override fun build(): View = settingsShell { column ->
        var card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2))
        autoBoot.apply {
            setCheckedSilently(host.settings.autoOnBoot)
            onToggle = { checked ->
                host.updateSettings { it.copy(autoOnBoot = checked) }
                onDataChanged()
            }
        }
        addRow(card, autoBoot, 0, 3)
        autoLaunch.apply {
            setCheckedSilently(host.settings.autoOnLaunch)
            onToggle = { checked ->
                host.updateSettings { it.copy(autoOnLaunch = checked) }
                onDataChanged()
            }
        }
        addRow(card, autoLaunch, 1, 3)
        protectDeleting.apply {
            setCheckedSilently(host.settings.protectDeleting)
            onToggle = { checked ->
                host.updateSettings { it.copy(protectDeleting = checked) }
                onDataChanged()
            }
        }
        addRow(card, protectDeleting, 2, 3)

        column.addView(ui.sectionTitle("后台与息屏"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        background.apply {
            setCheckedSilently(host.settings.background)
            onToggle = { checked ->
                host.updateSettings { it.copy(background = checked) }
                onDataChanged()
            }
        }
        addRow(card, background, 0, 2)
        screenOff.apply {
            setCheckedSilently(host.settings.screenOff)
            onToggle = { checked ->
                host.updateSettings { it.copy(screenOff = checked) }
                onDataChanged()
            }
        }
        addRow(card, screenOff, 1, 2)

        column.addView(ui.sectionTitle("关闭联动"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        val linkCard = ui.card()
        column.addView(linkCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        val serviceRow = SettingRow(ui, "车辆数据状态", "权限准备完成后按需订阅；车辆数据开关由 AutoService 管理").apply {
            setValue(if (host.vehicle.connected) "已订阅" else "等待准备")
        }
        addRow(linkCard, serviceRow, 0, 2)
        val stopAll = SettingRow(ui, "停止全部录制", "一次性动作；不改变上面任何自动录制设置").apply {
            setValue("执行")
            clickableRow("停止全部录制") {
                RecordDialogs.confirm(host, "停止全部录制", "将结束当前片段并保存，确定停止？", "停止") {
                    host.requestStopRecording()
                }
            }
        }
        addRow(linkCard, stopAll, 1, 2)

        column.addView(
            ui.note("以上开关都接实际生命周期并持久化；关闭后旧任务不会复活。AutoService 侧的关闭选择优先于本页设置。"),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) },
        )
    }

    override fun onDataChanged() = Unit
}

/** P9 车辆数据与事件。 */
internal class VehicleSettingsPage(host: RecordActivity, recordUi: RecordUi) : SettingsSubPage(host, recordUi) {
    override val title: String = "车辆数据与事件"

    override fun build(): View = settingsShell { column ->
        column.addView(
            choiceRow(
                title = "订阅间隔",
                description = "250ms 为事件优先档，事件更细但开销更高",
                currentLabel = "${host.settings.vehicleIntervalMs} ms",
                options = RecordSettings.VEHICLE_INTERVAL_OPTIONS.map {
                    ChoiceOption(it.toString(), "$it ms", reason = if (it == 250) "事件优先：更细精度、更高开销" else "")
                },
                currentId = host.settings.vehicleIntervalMs.toString(),
            ) { picked ->
                host.updateSettings { it.copy(vehicleIntervalMs = picked.toInt()) }
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        column.addView(ui.sectionTitle("记录项"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        var card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        val minimal = VehicleKey.minimal
        minimal.forEachIndexed { index, key ->
            val row = SettingRow(ui, key.displayLabel(), "回放必需，固定开启").apply {
                setValue("已记录")
                setRowEnabled(false)
            }
            addRow(card, row, index, minimal.size + 1)
        }
        val extendedRow = SettingRow(ui, "扩展项", "其余已核验目录键按用途单独开关，默认关").apply {
            setValue("${host.settings.extraVehicleGroups.size} 项已开")
            clickableRow("扩展记录项") { showExtendedChoices() }
        }
        addRow(card, extendedRow, minimal.size, minimal.size + 1)

        column.addView(ui.sectionTitle("事件规则"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        val rules = listOf(
            Triple("刹车阈值", "进入 ${host.settings.brakeEnter}% / 退出 ${host.settings.brakeExit}%", 0),
            Triple("油门阈值", "进入 ${host.settings.accelEnter}% / 退出 ${host.settings.accelExit}%", 1),
            Triple("去抖 / 最小事件 / 合并间隔", "${host.settings.debounceMs} / ${host.settings.minEventMs} / ${host.settings.mergeMs} ms", 2),
            Triple("规则版本", host.settings.eventRulesVersion, 3),
        )
        rules.forEach { (title, value, index) ->
            val row = SettingRow(ui, title, if (index == 3) "随阈值修改递增，历史片段按记录版本解释" else "只影响新片段")
            row.setValue(if (index == 3) "已记录" else value)
            if (index < 3) row.clickableRow(title) { showRuleDialog(index) } else row.setRowEnabled(false)
            addRow(card, row, index, rules.size)
        }

        column.addView(ui.sectionTitle("事件标记与保留"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        val protect = SwitchSettingRow(ui, "含事件片段优先保留", "事件段不参与循环覆盖，直到受保护配额上限")
        protect.setCheckedSilently(host.settings.eventProtect)
        protect.onToggle = { checked ->
            host.updateSettings { it.copy(eventProtect = checked) }
            onDataChanged()
        }
        addRow(card, protect, 0, 1)

        column.addView(
            ui.note(
                "边界：本产品只按制动、油门、辅助状态等信号阈值打「事件标记」，用于回放定位与保留保护，" +
                    "不做碰撞检测，也不做事故认定。",
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) },
        )
        column.addView(
            ui.note("历史事件不回算：修改阈值只影响新片段，已有片段仍按写入时的规则版本解释。"),
            LinearLayout.LayoutParams(-1, -2),
        )
    }

    private fun showExtendedChoices() {
        val keys = VehicleKey.extended
        val labels = keys.map { it.displayLabel() }.toTypedArray()
        val flags = keys.map { it.key in host.settings.extraVehicleGroups }.toBooleanArray()
        android.app.AlertDialog.Builder(host)
            .setTitle("扩展记录项")
            .setMultiChoiceItems(labels, flags) { _, which, isChecked ->
                val key = keys[which].key
                val next = host.settings.extraVehicleGroups.toMutableSet()
                if (isChecked) next += key else next -= key
                host.updateSettings { it.copy(extraVehicleGroups = next) }
            }
            .setPositiveButton("完成") { _, _ ->
                onDataChanged()
                host.toastLine("扩展记录项已保存；实际采集在 AutoService 会话就绪后生效")
            }
            .show()
    }

    private fun showRuleDialog(index: Int) {
        val options: List<ChoiceOption>
        when (index) {
            0 -> options = listOf(3, 5, 8, 10).map { ChoiceOption("$it", "进入 $it%（退出 ${it - 2}%）") }
            1 -> options = listOf(10, 15, 20, 30).map { ChoiceOption("$it", "进入 $it%（退出 ${it - 10}%）") }
            else -> options = listOf(100, 200, 300).map { ChoiceOption("$it", "去抖 $it ms") }
        }
        val current = when (index) {
            0 -> host.settings.brakeEnter.toString()
            1 -> host.settings.accelEnter.toString()
            else -> host.settings.debounceMs.toString()
        }
        ui.choiceDialog(
            title = when (index) {
                0 -> "刹车阈值"
                1 -> "油门阈值"
                else -> "去抖时长"
            },
            options = options,
            currentId = current,
        ) { picked ->
            val value = picked.toInt()
            host.updateSettings {
                when (index) {
                    0 -> it.copy(brakeEnter = value, brakeExit = (value - 2).coerceAtLeast(0))
                    1 -> it.copy(accelEnter = value, accelExit = (value - 10).coerceAtLeast(0))
                    else -> it.copy(debounceMs = value)
                }
            }
            onDataChanged()
        }
    }
}

/** P10 回放与显示。 */
internal class PlaybackSettingsPage(host: RecordActivity, recordUi: RecordUi) : SettingsSubPage(host, recordUi) {
    override val title: String = "回放与显示"

    override fun build(): View = settingsShell { column ->
        var card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2))

        val layoutRow = choiceRow(
            title = "默认布局",
            description = "打开回放时的默认画面布局",
            currentLabel = if (host.settings.playbackLayout == RecordSettings.PLAYBACK_GRID) "四宫格" else "单路",
            options = listOf(
                ChoiceOption(RecordSettings.PLAYBACK_GRID, "四宫格"),
                ChoiceOption(RecordSettings.PLAYBACK_SINGLE, "单路"),
            ),
            currentId = host.settings.playbackLayout,
        ) { picked -> host.updateSettings { it.copy(playbackLayout = picked) } }
        addRow(card, layoutRow, 0, 3)

        val speedRow = choiceRow(
            title = "默认倍速",
            description = "回放打开时的初始倍速",
            currentLabel = "${host.settings.playbackSpeed}×",
            options = RecordSettings.SPEED_OPTIONS.map { ChoiceOption("$it", "$it×") },
            currentId = host.settings.playbackSpeed.toString(),
        ) { picked -> host.updateSettings { it.copy(playbackSpeed = picked.toFloat()) } }
        addRow(card, speedRow, 1, 3)

        val endRow = choiceRow(
            title = "播完行为",
            description = "默认停在最后一帧，不自动跳段",
            currentLabel = if (host.settings.playbackOnEnd == RecordSettings.PLAYBACK_STOP) "停在最后一帧" else "自动下一段",
            options = listOf(
                ChoiceOption(RecordSettings.PLAYBACK_STOP, "停在最后一帧"),
                ChoiceOption(RecordSettings.PLAYBACK_NEXT, "自动下一段（同会话相邻段）"),
            ),
            currentId = host.settings.playbackOnEnd,
        ) { picked -> host.updateSettings { it.copy(playbackOnEnd = picked) } }
        addRow(card, endRow, 2, 3)

        column.addView(ui.sectionTitle("事件轨道"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        val trackRow = SwitchSettingRow(ui, "默认展开事件轨道", "关闭后回放页默认折叠时间轴")
        trackRow.setCheckedSilently(host.settings.trackExpanded)
        trackRow.onToggle = { checked ->
            host.updateSettings { it.copy(trackExpanded = checked) }
            onDataChanged()
        }
        addRow(card, trackRow, 0, 2)
        val jumpRow = SettingRow(ui, "前后跳转", "本稿固定为上一/下一事件；无事件时退化为 ±10 秒").apply {
            setValue("事件优先")
            setRowEnabled(false)
        }
        addRow(card, jumpRow, 1, 2)

        column.addView(ui.sectionTitle("界面"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        card = ui.card()
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        val themeRow = choiceRow(
            title = "日夜模式",
            description = "跟随系统时随车机日夜切换",
            currentLabel = RecordFmt.themeLabel(host.settings.themeMode),
            options = listOf(
                ChoiceOption(RecordSettings.THEME_SYSTEM, "跟随系统"),
                ChoiceOption(RecordSettings.THEME_LIGHT, "浅色"),
                ChoiceOption(RecordSettings.THEME_DARK, "深色"),
            ),
            currentId = host.settings.themeMode,
        ) { picked -> host.setThemeMode(picked) }
        addRow(card, themeRow, 0, 2)
        val fontRow = SettingRow(ui, "字体缩放", "跟随系统设置，应用内不单独缩放").apply {
            setValue("跟随系统")
            setRowEnabled(false)
        }
        addRow(card, fontRow, 1, 2)
    }

    override fun onDataChanged() = Unit
}
