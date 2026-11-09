package com.galaxy.autorecord.ui

import android.view.View
import android.widget.LinearLayout
import com.galaxy.autorecord.core.RecordSettings
import com.galaxy.autorecord.core.VolumeInfo

/**
 * P6 存储与保存（本轮真实实现）。
 *
 * 纪律（规格 §5 P6、技术口径 §4）：
 * - 持久化只存卷 UUID；保存的卷解析不到就报"已移除"，**不自动回切内置存储**；
 * - 选择介质前做真写探测（1 字节 + fsync + 删除），失败就地报错并保持原值；
 * - 任何改动立即落盘（PrefsSettingsStore.save），行内说明生效时机，不中断当前片段；
 * - 不格式化任何介质，不删除本 App 目录以外的文件。
 */
internal class StorageSettingsPage(private val host: RecordActivity, recordUi: RecordUi) : RecordPage(host, recordUi) {
    override val title: String = "存储与保存"

    private lateinit var locationRow: SettingRow
    private lateinit var spaceRow: SettingRow
    private lateinit var statusRow: SettingRow
    private lateinit var quotaRow: SettingRow
    private lateinit var minFreeRow: SettingRow
    private lateinit var segmentRow: SettingRow
    private lateinit var segmentProgressRow: SettingRow
    private lateinit var rotateRow: SwitchSettingRow
    private lateinit var windowRow: SettingRow

    private var volumes: List<VolumeInfo> = emptyList()
    private var volumesLoaded = false
    private var volumeError: String? = null

    override fun build(): View = ui.pageScroll { column ->
        column.addView(ui.sectionTitle("存储位置"), LinearLayout.LayoutParams(-1, -2))
        column.addView(buildLocationCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })

        column.addView(ui.sectionTitle("空间与覆盖"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        column.addView(buildSpaceCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })

        column.addView(ui.sectionTitle("分段"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        column.addView(buildSegmentCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })

        column.addView(
            ui.note("不自动格式化介质，也不删除本 App 目录以外的文件；介质掉线只提示并暂停，不换介质。"),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) },
        )
        reloadVolumes()
    }

    private fun buildLocationCard(): View {
        val card = ui.card()
        locationRow = SettingRow(
            ui, "存储位置", "切换只影响新片段；当前片段写完原介质",
        ).apply {
            clickableRow("选择存储位置") { openVolumeDialog() }
        }
        spaceRow = SettingRow(ui, "可用 / 总量", "剩余空间与配额分开计算，不合并成「可用」")
        statusRow = SettingRow(ui, "卷状态", "")
        card.addView(locationRow, LinearLayout.LayoutParams(-1, -2))
        addSeparator(card)
        card.addView(spaceRow, LinearLayout.LayoutParams(-1, -2))
        addSeparator(card)
        card.addView(statusRow, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun buildSpaceCard(): View {
        val card = ui.card()
        quotaRow = SettingRow(
            ui, "录像配额", "自动档取 32 GB 与介质可用容量 50% 的较小值",
        ).apply {
            clickableRow("选择录像配额") { openQuotaDialog() }
        }
        rotateRow = SwitchSettingRow(ui, "循环覆盖", "只删除最旧的未锁定、未在写、未导出的片段")
        rotateRow.setCheckedSilently(host.settings.rotate)
        rotateRow.onToggle = { checked ->
            host.updateSettings { it.copy(rotate = checked) }
            rotateRow.setFeedback("已保存，立即生效：清理任务下一拍按新规则执行")
        }
        minFreeRow = SettingRow(
            ui, "最低剩余空间", "低于该值时暂停录制并提示，不自动换介质",
        ).apply {
            clickableRow("选择最低剩余空间") { openMinFreeDialog() }
        }
        windowRow = SettingRow(
            ui, "可回溯时长", "按当前码率与配额估算；隔夜取证可能已被覆盖",
        )
        windowRow.setRowEnabled(false)
        card.addView(quotaRow, LinearLayout.LayoutParams(-1, -2))
        addSeparator(card)
        card.addView(windowRow, LinearLayout.LayoutParams(-1, -2))
        addSeparator(card)
        card.addView(rotateRow, LinearLayout.LayoutParams(-1, -2))
        addSeparator(card)
        card.addView(minFreeRow, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun buildSegmentCard(): View {
        val card = ui.card()
        segmentRow = SettingRow(ui, "分段时长", "改对下一段生效，当前片段不中断").apply {
            clickableRow("选择分段时长") { openSegmentDialog() }
        }
        segmentProgressRow = SettingRow(ui, "当前片段进度", "录制引擎未接入（阶段B）")
        segmentProgressRow.setValue("—")
        card.addView(segmentRow, LinearLayout.LayoutParams(-1, -2))
        addSeparator(card)
        card.addView(segmentProgressRow, LinearLayout.LayoutParams(-1, -2))
        addSeparator(card)
        val naming = SettingRow(
            ui, "片段命名规则", "segments/<日期>/<开始时间>-<段号>，四路各一个 MP4，另带 sidecar",
        )
        card.addView(naming, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    /** 卡内行间分隔线：左右各留 20dp，只画在行与行之间。 */
    private fun addSeparator(card: LinearLayout) {
        card.addView(ui.rowSeparator(), LinearLayout.LayoutParams(-1, ui.dp(1).coerceAtLeast(1)).apply {
            marginStart = ui.dp(20)
            marginEnd = ui.dp(20)
        })
    }

    // ---- 数据装载 ----

    private fun reloadVolumes() {
        if (!volumesLoaded) locationRow.setFeedback("正在枚举存储卷…")
        host.io.run({ host.storageVolumes.list() }) { result ->
            result.onSuccess {
                volumes = it
                volumesLoaded = true
                volumeError = null
                locationRow.setFeedback("")
            }.onFailure {
                volumesLoaded = true
                volumeError = "枚举存储卷失败：${it.javaClass.simpleName} ${it.message ?: ""}"
                locationRow.setFeedback(volumeError!!, error = true)
            }
            renderStorage()
            renderQuota()
        }
    }

    private fun renderStorage() {
        val uuid = host.settings.storageVolumeUuid
        val volume = volumes.firstOrNull { it.id == uuid }
        if (!volumesLoaded) {
            locationRow.setValue("读取中…")
            spaceRow.setValue("—")
            statusRow.setValue("—")
            return
        }
        when {
            volume == null && uuid != null -> {
                // 保存的卷已不在列表：必须报"已移除"，保持设置，不静默切到内置存储。
                locationRow.setValue("已移除（原卷 ${uuid.take(8)}…）")
                spaceRow.setValue("—")
                statusRow.setValue("已移除")
                statusRow.setFeedback("保存的介质已不在当前列表：请重新选择存储位置（不会自动更换介质）", error = true)
                spaceRow.setFeedback("")
            }
            volume == null -> {
                locationRow.setValue("内置存储（未取得详情）")
                spaceRow.setValue("—")
                statusRow.setValue("—")
                statusRow.setFeedback("")
            }
            else -> {
                locationRow.setValue(volume.label)
                spaceRow.setValue("${RecordFmt.bytes(volume.freeBytes)} / ${RecordFmt.bytes(volume.totalBytes)}")
                statusRow.setValue(volumeStatus(volume))
                statusRow.setFeedback(
                    if (volume.isUsable) "" else "该卷当前不可写：录制会暂停并提示，不会自动换介质",
                    error = !volume.isUsable,
                )
                spaceRow.setFeedback("")
            }
        }
    }

    private fun renderQuota() {
        val settings = host.settings
        val uuid = settings.storageVolumeUuid
        val total = volumes.firstOrNull { it.id == uuid }?.totalBytes ?: 0L
        val effective = settings.effectiveQuotaBytes(total)
        quotaRow.setValue(
            if (settings.quotaBytes > 0L) RecordFmt.bytes(settings.quotaBytes)
            else "自动 → ${RecordFmt.bytes(effective)}",
        )
        quotaRow.setDescription(
            if (settings.quotaBytes > 0L) "手动档；自动档为 32 GB 与介质可用容量 50% 取小"
            else "32 GB 与介质可用容量 50% 取小 = ${RecordFmt.bytes(effective)}",
        )
        windowRow.setValue(host.backtraceLabel(effective))
        val millis = com.galaxy.autorecord.core.RetentionPlan.backtraceMillis(
            effective, settings.requestedHeight, settings.fps, settings.bitrateMode,
        )
        windowRow.setFeedback(
            if (com.galaxy.autorecord.core.RetentionPlan.isShortWindow(millis))
                "回溯窗口不足 2 小时：建议锁定重要片段或导出，或改低画质/提高配额"
            else "估算值：单路由 0.08×W×H×fps 推算，未按本车实测校准",
            error = com.galaxy.autorecord.core.RetentionPlan.isShortWindow(millis),
        )
        minFreeRow.setValue(RecordFmt.minFreeLabel(settings.minFreeBytes))
        segmentRow.setValue(RecordFmt.segmentLabel(settings.segmentSeconds))
        rotateRow.setCheckedSilently(settings.rotate)
    }

    // ---- 交互 ----

    private fun openVolumeDialog() {
        if (!volumesLoaded) {
            locationRow.setFeedback("正在枚举存储卷，请稍候再打开列表")
            return
        }
        ui.volumeDialog(volumes, host.settings.storageVolumeUuid, volumeError, onRefresh = { reloadVolumes() }) { volume ->
            selectVolume(volume)
        }
    }

    /** 真写探测通过才改设置；失败就地报错并保持原值。 */
    private fun selectVolume(volume: VolumeInfo) {
        val dir = volume.appDir
        if (dir == null) {
            locationRow.setFeedback("无法写入 ${volume.label}：App 在本卷没有可用目录，保持原设置", error = true)
            return
        }
        locationRow.setFeedback("正在对 ${volume.label} 做可写探测（1 字节 + fsync）…")
        host.io.run({ host.storageVolumes.probeWritable(dir) }) { result ->
            val writable = result.getOrDefault(false)
            if (writable) {
                host.updateSettings { it.copy(storageVolumeUuid = volume.id) }
                locationRow.setFeedback("已切换为 ${volume.label}，下一段生效（当前片段写完原介质）")
                renderStorage()
                renderQuota()
            } else {
                val why = result.exceptionOrNull()?.let { "（${it.javaClass.simpleName}）" } ?: ""
                locationRow.setFeedback("无法写入 ${volume.label}：真写探测失败$why，保持原设置", error = true)
            }
        }
    }

    private fun openQuotaDialog() {
        val settings = host.settings
        val total = volumes.firstOrNull { it.id == settings.storageVolumeUuid }?.totalBytes ?: 0L
        val effective = settings.effectiveQuotaBytes(total)
        val options = buildList {
            add(
                ChoiceOption(
                    id = "auto",
                    label = "自动",
                    reason = "32 GB 与介质可用容量 50% 取小 = ${RecordFmt.bytes(effective)}",
                ),
            )
            RecordSettings.QUOTA_OPTIONS_GB.forEach { gb ->
                add(ChoiceOption(id = gb.toString(), label = "$gb GB"))
            }
        }
        val current = if (settings.quotaBytes > 0L) (settings.quotaBytes / (1024L * 1024L * 1024L)).toString() else "auto"
        ui.choiceDialog("录像配额", options, current) { id ->
            val bytes = if (id == "auto") 0L else id.toLong() * 1024L * 1024L * 1024L
            host.updateSettings { it.copy(quotaBytes = bytes) }
            renderQuota()
            quotaRow.setFeedback(
                if (bytes > 0L) "已保存，立即生效：清理任务下一拍按新配额执行"
                else "已保存，立即生效：按 ${RecordFmt.bytes(effective)} 管理",
            )
        }
    }

    private fun openMinFreeDialog() {
        val settings = host.settings
        val current = (settings.minFreeBytes / (1024L * 1024L * 1024L)).toString()
        val options = RecordSettings.MIN_FREE_OPTIONS_GB.map { ChoiceOption(id = it.toString(), label = "$it GB") }
        ui.choiceDialog("最低剩余空间", options, current) { id ->
            host.updateSettings { it.copy(minFreeBytes = id.toLong() * 1024L * 1024L * 1024L) }
            renderQuota()
            minFreeRow.setFeedback("已保存，立即生效：低于该值时暂停录制并提示")
        }
    }

    private fun openSegmentDialog() {
        val settings = host.settings
        val options = RecordSettings.SEGMENT_OPTIONS.map {
            ChoiceOption(id = it.toString(), label = RecordFmt.segmentLabel(it))
        }
        ui.choiceDialog("分段时长", options, settings.segmentSeconds.toString()) { id ->
            host.updateSettings { it.copy(segmentSeconds = id.toInt()) }
            renderQuota()
            segmentRow.setFeedback("已保存，下一段生效（当前片段不中断）")
        }
    }

    override fun onShown() {
        reloadVolumes()
    }
}