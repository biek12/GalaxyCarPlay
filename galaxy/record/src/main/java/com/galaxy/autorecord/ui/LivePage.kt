package com.galaxy.autorecord.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.galaxy.autorecord.core.CameraState
import com.galaxy.autorecord.core.PreflightOutcome
import com.galaxy.autorecord.core.RecordingState
import com.galaxy.autorecord.core.RetentionPlan
import com.galaxy.autorecord.core.SegmentLayout
import com.galaxy.autorecord.core.VehicleKey
import com.galaxy.autorecord.core.VehicleValueStatus

/**
 * P1 实时画面（内容优先页）。
 *
 * 本轮变化：相机改为**直连采集**（CAMERA 已授予时），四宫格显示真实画面；
 * 权限不足时明确提示授权，而不是笼统写"通道未接入"。
 *
 * 资源纪律（"最少资源办最大的事"）：
 * - 四宫格每路 8fps、1/4 降采样解码；单路放大只开那一路并升到 15fps 全尺寸；
 * - 页面被换下/退到后台立即停流，不占相机、不烧电；
 * - 出帧达不到目标帧率就如实变慢，不硬追（实测单张抓拍 268–478 ms）。
 */
internal class LivePage(private val host: RecordActivity, recordUi: RecordUi) : RecordPage(host, recordUi) {
    override val title: String = "实时画面"

    private lateinit var panel: RecordPlayerPanel
    private lateinit var gridHost: FrameLayout
    private lateinit var segmentChips: LinearLayout
    private lateinit var noticeView: TextView
    private lateinit var primaryAction: Button
    private lateinit var lockAction: Button
    private lateinit var storageBadge: TextView
    private lateinit var windowBadge: TextView
    private lateinit var permissionBar: LinearLayout

    private var flatPreview=host.settings.flatView
    private var singleDirection: String? = null
    private var noticeText: String = ""
    private var vehicleNotice: String? = null

    private val cellImages = mutableMapOf<String, ImageView>()
    private val cellPreviews = mutableMapOf<String, CameraPreviewView>()
    private val cellStates = mutableMapOf<String, TextView>()
    private val cellMissing = mutableMapOf<String, TextView>()
    private val cellTags = mutableMapOf<String, TextView>()


    private fun hasCameraPermission(): Boolean =
        host.cameraAccessReady

    override fun build(): View = ui.pageScroll { column ->
        permissionBar = ui.row().apply { visibility = View.GONE }
        column.addView(permissionBar, LinearLayout.LayoutParams(-1, -2))
        segmentChips = ui.row()
        column.addView(segmentChips, LinearLayout.LayoutParams(-1, -2))
        renderSegmentChips()
        panel = RecordPlayerPanel(ui)
        gridHost = panel.picture
        column.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        renderGrid()
        renderData()
        noticeView = ui.note("").apply { visibility = View.GONE; setTextColor(ui.warning) }
        column.addView(noticeView, LinearLayout.LayoutParams(-1, -2))
        column.addView(buildActions(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(16) })
    }

    // ---- 四宫格 / 单路 ----

    private fun renderSegmentChips() {
        segmentChips.removeAllViews()
        val options = listOf<String?>(null) + SegmentLayout.DIRECTIONS
        options.forEach { direction ->
            val label = direction?.let { directionLabel(it).take(1) } ?: "全部"
            val chip = ui.tab(label, singleDirection == direction) {
                singleDirection = direction
                renderSegmentChips()
                renderGrid()
                restartPreview()
            }
            segmentChips.addView(chip, LinearLayout.LayoutParams(-2, ui.dp(44)).apply {
                marginEnd = ui.dp(8)
            })
        }
    }

    private fun renderGrid() {
        gridHost.removeAllViews()
        cellImages.clear()
        cellPreviews.clear()
        cellStates.clear()
        cellMissing.clear()
        cellTags.clear()
        val direction = singleDirection
        if (direction == null) {
            gridHost.addView(quadGrid(), FrameLayout.LayoutParams(-1, -2))
        } else {
            gridHost.addView(cell(direction, big = true), FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun quadGrid(): View = RecordPlayerPanel.grid(ui, SegmentLayout.DIRECTIONS, false) { cell(it, big = false) }

    /**
     * 单格：16:9 + 真实画面（有帧时）+ 左上方向/cameraId 标签 + 居中状态（无帧时）。
     * 有画面时不再盖状态文字；缺路仍标在右下角，避免把"少一路"藏起来。
     */
    private fun cell(direction: String, big: Boolean): View {
        val status = host.cameraSource.statuses()[direction]
        val state = status?.state ?: CameraState.STOPPED
        val cameraId = host.cameraLayout.cameraIdOf(direction)
        val frame = CameraAspectFrame(ui.context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            clipToOutline = true
            isClickable = !big
            if (!big) setOnClickListener {
                singleDirection = direction
                renderSegmentChips()
                renderGrid()
                restartPreview()
            }
        }

        if (host.usesSurfacePreview) {
            val preview = CameraPreviewView(host, direction)
            frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
            cellPreviews[direction] = preview
        } else {
            val image = ImageView(ui.context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(ui.control)
            }
            frame.addView(image, FrameLayout.LayoutParams(-1, -1))
            cellImages[direction] = image
        }

        val stateView = ui.text("", RecordRole.DESCRIPTION).apply { gravity = Gravity.CENTER }
        frame.addView(stateView, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            marginStart = ui.dp(8)
            marginEnd = ui.dp(8)
        })
        cellStates[direction] = stateView

        val tagText = directionLabel(direction)
        frame.addView(
            ui.text(tagText, RecordRole.OVERLAY).apply {
                cellTags[direction] = this
                setTextColor(ui.overlayText)
                setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3))
                background = ui.rounded(ui.overlayScrim, 3)
            },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                marginStart = ui.dp(8); topMargin = ui.dp(8)
            },
        )

        if (!big) {
            val missing = ui.text("缺路", RecordRole.OVERLAY).apply {
                setTextColor(ui.overlayText)
                setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3))
                background = ui.rounded(ui.danger, 3)
            }
            cellMissing[direction] = missing
            frame.addView(
                missing,
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                    marginEnd = ui.dp(8); bottomMargin = ui.dp(8)
                },
            )
        }

        paintCell(direction, state, status?.detail ?: "")
        return frame
    }

    /** 有帧就显示画面并隐藏状态字；没帧就把真实原因写清楚（不黑屏无解释）。 */
    private fun paintCell(direction: String, state: CameraState, detail: String) {
        cellMissing[direction]?.apply {
            visibility = if (state in setOf(CameraState.ACTIVE, CameraState.WAITING_FIRST_FRAME, CameraState.STOPPED, CameraState.DISABLED)) View.GONE else View.VISIBLE
        }
        cellTags[direction]?.text = directionLabel(direction)
        val stateView = cellStates[direction] ?: return
        if (cellPreviews.containsKey(direction)) {
            stateView.visibility = if (state == CameraState.ACTIVE) View.GONE else View.VISIBLE
            stateView.text = detail.ifEmpty { "等待连续预览…" }
            return
        }
        val image = cellImages[direction] ?: return
        val bitmap = host.frameOf(direction)
        if (bitmap != null && state == CameraState.ACTIVE) {
            image.setImageBitmap(bitmap)
            image.visibility = View.VISIBLE
            stateView.visibility = View.GONE
            return
        }
        image.setImageDrawable(null)
        image.visibility = View.GONE
        stateView.visibility = View.VISIBLE
        stateView.text = when (state) {
            CameraState.ACTIVE -> "等待画面…"
            CameraState.WAITING_FIRST_FRAME -> {
                val id = host.cameraLayout.cameraIdOf(direction)
                if (id == null) "未配置 cameraId" else "打开中（cam$id）…"
            }
            CameraState.WAITING_VENDOR_RELEASE -> "等待原厂释放（${directionLabel(direction)}）"
            CameraState.DISABLED -> "功能已关闭（${directionLabel(direction)}）"
            CameraState.NO_PERMISSION -> detail.ifEmpty { "权限不足（${directionLabel(direction)}）" }
            CameraState.DISCONNECTED -> detail.ifEmpty { "连接断开（${directionLabel(direction)}）" }
            CameraState.STOPPED -> detail.ifEmpty { "未开始" }
        }
    }

    // ---- 预览生命周期 ----

    private fun desiredDirections(): List<String> =
        singleDirection?.let { listOf(it) } ?: SegmentLayout.DIRECTIONS

    private fun restartPreview() {
        if (!hasCameraPermission()) {
            renderPermissionBar()
            return
        }
        host.setPreviewFullSize(singleDirection)
        // 单路时释放其余三路，四宫格时四路全开：按需占资源
        host.startPreview(desiredDirections(), fullSizeDirection = singleDirection)
    }

    private fun renderPermissionBar() {
        if (!::permissionBar.isInitialized) return
        permissionBar.removeAllViews()
        permissionBar.visibility = View.VISIBLE
        permissionBar.addView(
            ui.text(host.cameraPermissionState, RecordRole.FEEDBACK).apply { setTextColor(ui.warning) },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        permissionBar.addView(
            ui.chip("授权", false) { host.requestCameraPermission(force = true) },
            LinearLayout.LayoutParams(-2, ui.dp(44)),
        )
    }

    override fun onShown() {
        renderActions()
        renderData()
        refreshStorage()
        if (noticeText.isNotEmpty()) showNotice(noticeText)
        if (!hasCameraPermission()) {
            renderPermissionBar()
            host.requestCameraPermission()   // 只在首次自动申请，被拒后等用户点"授权"
        } else {
            permissionBar.visibility = View.GONE
            restartPreview()
        }
    }

    override fun onHidden() {
        host.stopPreview()
    }

    override fun onCameraFrame(direction: String) {
        val status = host.cameraSource.statuses()[direction]
        paintCell(direction, status?.state ?: CameraState.ACTIVE, status?.detail ?: "")
    }

    override fun onSnapshotChanged() {
        renderActions()
    }

    override fun onDataChanged() {
        renderData()
        SegmentLayout.DIRECTIONS.forEach { direction ->
            val status = host.cameraSource.statuses()[direction]
            paintCell(direction, status?.state ?: CameraState.STOPPED, status?.detail ?: "")
        }
        if (::permissionBar.isInitialized) {
            if (hasCameraPermission()) permissionBar.visibility = View.GONE else renderPermissionBar()
        }
    }

    // ---- 数据条 ----

    private fun renderData() {
        if (!::panel.isInitialized) return
        val values = host.vehicle.values.filterValues { it.status == VehicleValueStatus.LIVE }
            .mapKeys { it.key.key }.mapValues { it.value.value }
        panel.bind(values, System.currentTimeMillis())
        if (::noticeView.isInitialized) {
            if (!host.vehicle.connected) {
                vehicleNotice = host.vehicle.reason
                showNotice(host.vehicle.reason)
            } else if (vehicleNotice != null) {
                if (noticeText == vehicleNotice) showNotice("")
                vehicleNotice = null
            }
        }
    }

    // ---- 操作区 ----

    private fun buildActions(): View {
        val column = ui.column()
        val viewMode=ui.chip(if(flatPreview)"广角平面 · 切鱼眼"else"鱼眼 · 切广角平面",flatPreview){}
        viewMode.setOnClickListener{
            if(host.setFlatPreview(!flatPreview,singleDirection?.let{listOf(it)}?:SegmentLayout.DIRECTIONS)){
                flatPreview=!flatPreview;viewMode.text=if(flatPreview)"广角平面 · 切鱼眼"else"鱼眼 · 切广角平面";ui.styleTab(viewMode,flatPreview)
            }
        }
        column.addView(viewMode,LinearLayout.LayoutParams(-2,ui.dp(44)))
        val row = ui.row()
        primaryAction = ui.primaryButton("开始录制") { onPrimaryAction() }
        lockAction = ui.secondaryButton("锁定最近片段") { showNotice(host.lockRecent()) }
        row.addView(primaryAction, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
        row.addView(lockAction, LinearLayout.LayoutParams(-2, ui.dp(48)).apply { marginStart = ui.dp(12) })
        column.addView(row, LinearLayout.LayoutParams(-1, -2))
        return column
    }

    private fun onPrimaryAction() {
        when (host.controller.snapshot.state) {
            RecordingState.IDLE, RecordingState.ERROR -> host.requestStartRecording()
            RecordingState.RECORDING -> RecordDialogs.confirm(
                host, "停止录制", "将结束当前片段并保存，确定停止？", "停止",
            ) { host.requestStopRecording() }
            RecordingState.STARTING, RecordingState.STOPPING -> showNotice("正在处理上一次请求，请稍候")
        }
    }

    private fun renderActions() {
        if (!::primaryAction.isInitialized) return
        val state = host.controller.snapshot.state
        primaryAction.text = when (state) {
            RecordingState.IDLE -> "开始录制"
            RecordingState.STARTING -> "预检中…"
            RecordingState.RECORDING -> "停止录制"
            RecordingState.STOPPING -> "收尾中…"
            RecordingState.ERROR -> "重试开始录制"
        }
        primaryAction.isEnabled = state != RecordingState.STARTING && state != RecordingState.STOPPING
        lockAction.isEnabled = true
    }

    // ---- 预检结果 ----

    fun onPreflight(outcome: PreflightOutcome) {
        when (outcome) {
            is PreflightOutcome.Passed -> showNotice("预检通过：${outcome.directions.size} 路可用，开始录制")
            is PreflightOutcome.Failed -> showPreflightFailure(outcome)
            PreflightOutcome.Cancelled -> Unit
        }
    }

    private fun showPreflightFailure(outcome: PreflightOutcome.Failed) {
        val live = host.cameraSource.statuses().filterValues { it.isLive }.keys.toList()
        val engineBlocked = outcome.failures.any { it.stage == "engine" }
        val lines = outcome.failures.joinToString("\n") { failure ->
            val where = failure.direction?.let { directionLabel(it) + "：" } ?: ""
            "· $where${failure.detail}"
        }
        val message = buildString {
            append(lines)
            append("\n\n")
            if (engineBlocked) {
                append("说明：录制引擎尚未接入（B3），本轮不会创建编码器、不会写盘，因此无法开始录制。")
            } else {
                append("可选择「仅录可用路」后开始，缺路会在回放里按区间标注。")
            }
        }
        val dialog = android.app.AlertDialog.Builder(host)
            .setTitle("预检未通过")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .create()
        if (!engineBlocked && live.isNotEmpty()) {
            dialog.setButton(android.app.AlertDialog.BUTTON_POSITIVE, "仅录 ${live.size} 路") { _, _ ->
                host.controller.startWithDirections(live)
                showNotice("已按你的选择进入正式阶段（引擎未接入时仍不会写盘）")
            }
        }
        dialog.show()
        showNotice(outcome.failures.joinToString("；") { it.detail })
    }

    // ---- 提示与徽标 ----

    override fun showTransient(message: String) = showNotice(message)

    private fun showNotice(text: String) {
        noticeText = text
        if (!::noticeView.isInitialized) return
        noticeView.text = text
        noticeView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        noticeView.setTextColor(ui.warning)
    }

    override fun rightSlot(container: LinearLayout) {
        windowBadge = ui.badge("可回溯 —", ui.muted)
        storageBadge = ui.badge("存储：读取中", ui.muted)
        container.addView(windowBadge, LinearLayout.LayoutParams(-2, -2))
        container.addView(storageBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
        refreshStorage()
    }

    override fun onTick() {
        if (host.vehicle.connected) renderData()
    }

    /** 存储徽标 + 可回溯时长：配额与画质变化后立即重算（v1.1 RV-02）。 */
    private fun refreshStorage() {
        host.loadVolumes { result ->
            if (!::storageBadge.isInitialized) return@loadVolumes
            val volumes = result.getOrNull()
            if (volumes == null) {
                storageBadge.text = "存储：探测失败"
                storageBadge.setTextColor(ui.warning)
                windowBadge.text = "可回溯 —"
                return@loadVolumes
            }
            val uuid = host.settings.storageVolumeUuid
            val volume = if (uuid == null) volumes.firstOrNull { !it.removable } ?: volumes.firstOrNull()
            else volumes.firstOrNull { it.id == uuid }
            if (volume == null) {
                storageBadge.text = "存储：已移除"
                storageBadge.setTextColor(ui.warning)
                windowBadge.text = "可回溯 —"
                return@loadVolumes
            }
            storageBadge.text = "${volume.label} · 剩 ${RecordFmt.bytes(volume.freeBytes)}"
            storageBadge.setTextColor(if (volume.isUsable) ui.muted else ui.warning)

            val quota = host.settings.effectiveQuotaBytes(volume.totalBytes)
            val millis = RetentionPlan.backtraceMillis(
                quota, host.settings.requestedHeight, host.settings.fps, host.settings.bitrateMode,
            )
            windowBadge.text = host.backtraceLabel(quota)
            windowBadge.setTextColor(if (RetentionPlan.isShortWindow(millis)) ui.warning else ui.muted)
        }
    }
}
