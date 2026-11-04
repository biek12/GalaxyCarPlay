package com.galaxy.autorecord.ui

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.galaxy.autorecord.core.EventKind
import com.galaxy.autorecord.core.EventMark
import com.galaxy.autorecord.core.EventReader
import com.galaxy.autorecord.core.RecordSettings
import com.galaxy.autorecord.core.SegmentCompleteness
import com.galaxy.autorecord.core.SegmentEntry
import com.galaxy.autorecord.core.SegmentLayout
import com.galaxy.autorecord.core.VehicleHistory
import com.galaxy.autorecord.core.VehicleKey
import com.galaxy.autorecord.core.VehicleSample
import com.galaxy.autorecord.playback.SegmentPlayer
import java.io.File

/**
 * P3 同步回放（沉浸二级页，v1.1 §5 P3）。
 *
 * 真实能力（B4 已接入）：
 * - 四路 MP4 同步解码渲染（[SegmentPlayer]），四路 + 历史数据 + 事件游标**共用一条会话时间轴**；
 * - 事件轨道、事件摘要、上一/下一事件跳转来自段 sidecar 的 `events.json`；
 * - 数据条来自 `vehicles.jsonl` 的历史样本，缺失/过期分别显示，不补零、不沿用实时值；
 * - 跨片段：同会话内相邻段可"上一段/下一段"，段界不拼接时间轴（RV-09）。
 *
 * 纪律：缺路显式占位、不用邻路画面顶替；seek 精度≈GOP；回放只读历史，不发起车辆采集。
 */
internal class PlaybackPage(
    private val host: RecordActivity,
    recordUi: RecordUi,
    private val segmentId: String,
) : RecordPage(host, recordUi) {

    override val title: String
        get() = entry?.let { RecordTime.wallTime(it.startedAtMillis) } ?: "回放"

    private var entry: SegmentEntry? = host.library.find(segmentId)
    private var events: List<EventMark> = emptyList()
    private var samples: List<VehicleSample> = emptyList()
    private var sidecarError: String? = null

    private var playing = false
    private var positionMs = 0L
    private var speed = host.settings.playbackSpeed
    private var singleDirection: String? = null
    private var visibleKinds: LinkedHashSet<EventKind> = LinkedHashSet()
    private var onlySelectedEvent = false

    private lateinit var pictureHost: FrameLayout
    private lateinit var dataHost: LinearLayout
    private lateinit var timeline: EventTimelineView
    private lateinit var progress: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var playButton: TextView
    private lateinit var eventCountLabel: TextView
    private lateinit var eventListHost: LinearLayout
    private lateinit var neighborRow: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var player: SegmentPlayer? = null
    private val textureViews = mutableMapOf<String, TextureView>()

    private val ticker = object : Runnable {
        override fun run() {
            if (!playing) return
            val p = player
            if (p != null) {
                positionMs = p.positionMs
                if (p.maybeEnded()) {
                    playing = false
                    onEnded()
                }
            } else {
                positionMs += (TICK_MS * speed).toLong()
                if (positionMs >= duration()) {
                    positionMs = duration()
                    pause()
                    onEnded()
                }
            }
            syncPosition()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        singleDirection = if (host.settings.playbackLayout == RecordSettings.PLAYBACK_SINGLE) {
            SegmentLayout.DIRECTIONS.first()
        } else null
        loadSidecar()
    }

    private fun duration(): Long = entry?.durationMillis?.takeIf { it > 0 } ?: DEFAULT_DURATION_MS

    private fun loadSidecar() {
        val dir = entry?.dir ?: return
        samples = VehicleHistory.read(dir)
        events = runCatching { EventReader.read(dir) }.getOrElse {
            sidecarError = it.message ?: "事件数据读取失败"
            emptyList()
        }
    }

    override fun build(): View {
        val root = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(16)) }

        pictureHost = FrameLayout(ui.context)
        root.addView(pictureHost, LinearLayout.LayoutParams(-1, 0, 1f))
        renderPicture()

        root.addView(buildDataCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        root.addView(buildControls(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        root.addView(buildTimeline(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        return root
    }

    // ---- 画面区 ----

    private fun renderPicture() {
        pictureHost.removeAllViews()
        textureViews.clear()
        val direction = singleDirection
        if (direction == null) {
            val grid = ui.column()
            SegmentLayout.DIRECTIONS.chunked(2).forEachIndexed { index, pair ->
                val row = ui.row()
                pair.forEachIndexed { position, dir ->
                    if (position > 0) row.addView(View(ui.context), LinearLayout.LayoutParams(ui.dp(10), 1))
                    row.addView(frameCell(dir), LinearLayout.LayoutParams(0, -2, 1f))
                }
                grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = ui.dp(10) })
            }
            pictureHost.addView(grid, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        } else {
            pictureHost.addView(frameCell(direction), FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        }
        val switcher = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
        val options = listOf<String?>(null) + SegmentLayout.DIRECTIONS
        options.forEach { option ->
            val label = option?.let { directionLabel(it) } ?: "全部"
            switcher.addView(ui.chip(label, singleDirection == option) {
                singleDirection = option
                renderPicture()
            }, LinearLayout.LayoutParams(-2, ui.dp(40)).apply { marginEnd = ui.dp(8) })
        }
        pictureHost.addView(switcher, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        rebuildPlayer()
    }

    /** 重建解码器（布局切换/回显）：保持当前时钟与播放态，不从头重播。 */
    private fun rebuildPlayer() {
        player?.release()
        player = null
        val current = entry ?: return
        val directions = (singleDirection?.let { listOf(it) } ?: SegmentLayout.DIRECTIONS)
            .filter { !current.missingDirections.contains(it) && hasVideoFile(current, it) }
        if (directions.isEmpty()) return
        val p = SegmentPlayer(current.dir, directions, duration())
        p.speed = speed
        p.onError = { message -> handler.post { host.toastLine(message) } }
        player = p
        textureViews.forEach { (dir, view) ->
            view.surfaceTexture?.let { st -> p.attach(dir, Surface(st)) }
        }
        if (playing) p.play() else p.seekTo(positionMs)
    }

    private fun hasVideoFile(entry: SegmentEntry, direction: String): Boolean =
        SegmentLayout.videoFile(entry.dir, direction).let { it.isFile && it.length() > 0 }

    private fun frameCell(direction: String): View {
        val current = entry
        val missing = current == null ||
            current.missingDirections.contains(direction) ||
            !hasVideoFile(current, direction)
        val frame = Aspect16x9(ui.context).apply {
            background = ui.rounded(ui.control, 4).apply { setStroke(ui.dp(1).coerceAtLeast(1), ui.border) }
            clipToOutline = true
        }
        if (!missing && current != null) {
            val texture = TextureView(ui.context)
            textureViews[direction] = texture
            frame.addView(texture, FrameLayout.LayoutParams(-1, -1))
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                    player?.attach(direction, Surface(st))
                }
                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                    player?.detach(direction)
                    return true
                }
                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
            }
        }
        frame.addView(
            ui.text(directionLabel(direction), RecordRole.OVERLAY).apply {
                setTextColor(ui.overlayText)
                setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3))
                background = ui.rounded(ui.overlayScrim, 3)
            },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                marginStart = ui.dp(8); topMargin = ui.dp(8)
            },
        )
        if (missing) {
            frame.addView(
                ui.text(
                    when {
                        current == null -> "片段不存在"
                        else -> "缺路（本段没有该方向）"
                    },
                    RecordRole.DESCRIPTION,
                ).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
                    marginStart = ui.dp(8); marginEnd = ui.dp(8)
                },
            )
        }
        return frame
    }

    // ---- 数据条 ----

    private fun buildDataCard(): View {
        val card = ui.card()
        dataHost = ui.column().apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(10)) }
        card.addView(dataHost, LinearLayout.LayoutParams(-1, -2))
        renderData()
        return card
    }

    /** 历史数据条：只读录像当时的样本；缺失显示"缺失"，超时显示"过期"（规格 R05-4）。 */
    private fun renderData() {
        dataHost.removeAllViews()
        if (sidecarError != null) {
            dataHost.addView(ui.note(sidecarError!!, error = true), LinearLayout.LayoutParams(-1, -2))
        }
        val row = WrapRow(ui.context, 0, ui.dp(6))
        val sample = VehicleHistory.sampleAt(samples, positionMs, host.settings.vehicleIntervalMs * 4L)
        if (samples.isEmpty()) {
            dataHost.addView(
                ui.text("本段没有车辆数据（录像时未订阅或数据未落盘）", RecordRole.DESCRIPTION),
                LinearLayout.LayoutParams(-1, -2),
            )
            return
        }
        VehicleKey.minimal.forEach { key ->
            val raw = sample?.values?.get(key.key)
            val value = if (sample == null) "过期" else raw ?: "缺失"
            row.addView(dataItem(key.displayLabel(), value), LinearLayout.LayoutParams(-2, -2))
        }
        dataHost.addView(row, LinearLayout.LayoutParams(-1, -2))
        dataHost.addView(
            ui.text(
                "样本 ${samples.size} 条 · 间隔 ${host.settings.vehicleIntervalMs} ms · 只读历史，不叠加实时值",
                RecordRole.DESCRIPTION,
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) },
        )
    }

    private fun dataItem(label: String, value: String): View {
        val item = ui.column().apply { setPadding(0, ui.dp(2), ui.dp(16), ui.dp(2)) }
        item.addView(ui.text(label, RecordRole.DESCRIPTION), LinearLayout.LayoutParams(-2, -2))
        item.addView(ui.text(value, RecordRole.VALUE), LinearLayout.LayoutParams(-2, -2))
        return item
    }

    // ---- 播放控制 ----

    private fun buildControls(): View {
        val card = ui.card()
        val body = ui.column().apply { setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12)) }
        card.addView(body, LinearLayout.LayoutParams(-1, -2))

        progress = SeekBar(ui.context).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    positionMs = duration() * value / 1000
                    syncPosition()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { pause() }
                override fun onStopTrackingTouch(bar: SeekBar?) { syncPosition() }
            })
        }
        body.addView(progress, LinearLayout.LayoutParams(-1, -2))

        val row = ui.row()
        row.addView(ui.chip("上一事件", false) { jumpEvent(-1) }, LinearLayout.LayoutParams(-2, ui.dp(44)))
        playButton = ui.chip("播放", false) { togglePlay() }
        row.addView(playButton, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginStart = ui.dp(8) })
        row.addView(ui.chip("下一事件", false) { jumpEvent(1) }, LinearLayout.LayoutParams(-2, ui.dp(44)).apply {
            marginStart = ui.dp(8)
        })
        row.addView(ui.column(), LinearLayout.LayoutParams(0, 1, 1f))
        timeLabel = ui.text("00:00 / 00:00", RecordRole.VALUE)
        row.addView(timeLabel, LinearLayout.LayoutParams(-2, -2))
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })

        val speedRow = ui.row()
        speedRow.addView(ui.text("倍速", RecordRole.DESCRIPTION), LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = ui.dp(8)
        })
        RecordSettings.SPEED_OPTIONS.forEach { option ->
            speedRow.addView(ui.chip("${option}×", speed == option) {
                speed = option
                player?.let { it.speed = option }
                host.updateSettings { it.copy(playbackSpeed = option) }
                renderSpeedRow(speedRow)
            }, LinearLayout.LayoutParams(-2, ui.dp(40)).apply { marginEnd = ui.dp(8) })
        }
        body.addView(speedRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })

        neighborRow = ui.row()
        body.addView(neighborRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        renderNeighbors()
        syncPosition()
        return card
    }

    private fun renderSpeedRow(row: LinearLayout) {
        row.removeAllViews()
        row.addView(ui.text("倍速", RecordRole.DESCRIPTION), LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = ui.dp(8)
        })
        RecordSettings.SPEED_OPTIONS.forEach { option ->
            row.addView(ui.chip("${option}×", speed == option) {
                speed = option
                player?.let { it.speed = option }
                host.updateSettings { it.copy(playbackSpeed = option) }
                renderSpeedRow(row)
            }, LinearLayout.LayoutParams(-2, ui.dp(40)).apply { marginEnd = ui.dp(8) })
        }
    }

    /** 跨片段：同会话相邻段可切换；段界不拼接时间轴（RV-09）。 */
    private fun renderNeighbors() {
        neighborRow.removeAllViews()
        val current = entry ?: return
        val (prev, next) = host.library.neighbors(current, host.settings.segmentSeconds)
        if (prev == null && next == null) {
            neighborRow.addView(
                ui.text("本段前后没有同会话片段", RecordRole.DESCRIPTION),
                LinearLayout.LayoutParams(-1, -2),
            )
            return
        }
        prev?.let {
            neighborRow.addView(ui.chip("上一段 ${RecordTime.dayTime(it.startedAtMillis)}", false) {
                host.openPlayback(it.id)
            }, LinearLayout.LayoutParams(-2, ui.dp(40)).apply { marginEnd = ui.dp(8) })
        }
        next?.let {
            neighborRow.addView(ui.chip("下一段 ${RecordTime.dayTime(it.startedAtMillis)}", false) {
                host.openPlayback(it.id)
            }, LinearLayout.LayoutParams(-2, ui.dp(40)))
        }
    }

    private fun togglePlay() {
        if (playing) pause() else play()
    }

    private fun play() {
        if (positionMs >= duration()) positionMs = 0
        playing = true
        playbackHint()
        player?.let { it.seekTo(positionMs); it.play() }
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
        syncPosition()
    }

    private fun pause() {
        playing = false
        player?.pause()
        handler.removeCallbacks(ticker)
        syncPosition()
    }

    private fun onEnded() {
        // 播完停在最后一帧；不自动跳下一段（连续播放由 P10 的"播完行为"决定）
        if (host.settings.playbackOnEnd == RecordSettings.PLAYBACK_NEXT) {
            val next = entry?.let { host.library.neighbors(it, host.settings.segmentSeconds)?.second }
            if (next != null) host.openPlayback(next.id)
        }
    }

    private fun syncPosition() {
        if (!::progress.isInitialized) return
        val total = duration()
        progress.progress = if (total <= 0) 0 else (positionMs * 1000 / total).toInt().coerceIn(0, 1000)
        timeLabel.text = "${RecordTime.clock(positionMs)} / ${RecordTime.clock(total)}"
        playButton.text = if (playing) "暂停" else "播放"
        if (::timeline.isInitialized) timeline.positionMs = positionMs
        renderData()
    }

    private fun seekTo(fraction: Float) {
        positionMs = (duration() * fraction).toLong().coerceIn(0, duration())
        player?.seekTo(positionMs)
        syncPosition()
    }

    private fun jumpEvent(direction: Int) {
        val ordered = filteredEvents()
        if (ordered.isEmpty()) {
            // 无事件时退化为 ±10 秒（规格 §5 P3-2 的本稿定案）
            positionMs = (positionMs + direction * 10_000L).coerceIn(0, duration())
        } else {
            val target = if (direction > 0) ordered.firstOrNull { it.startMs > positionMs }
            else ordered.lastOrNull { it.startMs < positionMs }
            positionMs = (target?.startMs ?: if (direction > 0) duration() else 0L).coerceIn(0, duration())
        }
        player?.seekTo(positionMs)
        syncPosition()
    }

    private fun filteredEvents(): List<EventMark> {
        val base = if (visibleKinds.isEmpty()) events else events.filter { it.kind in visibleKinds }
        return base.sortedBy { it.startMs }
    }

    // ---- 事件时间轴 ----

    private fun buildTimeline(): View {
        val card = ui.card()
        val body = ui.column().apply { setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12)) }
        card.addView(body, LinearLayout.LayoutParams(-1, -2))

        val header = ui.row()
        header.addView(ui.text("驾驶事件", RecordRole.LABEL), LinearLayout.LayoutParams(0, -2, 1f))
        eventCountLabel = ui.text("事件 ${events.size}", RecordRole.DESCRIPTION)
        header.addView(eventCountLabel, LinearLayout.LayoutParams(-2, -2))
        body.addView(header, LinearLayout.LayoutParams(-1, -2))

        val filterRow = WrapRow(ui.context, ui.dp(8), ui.dp(6)).apply { setPadding(0, ui.dp(6), 0, ui.dp(4)) }
        EventKind.entries.forEach { kind ->
            filterRow.addView(ui.chip(kind.label, kind in visibleKinds || visibleKinds.isEmpty()) {
                if (visibleKinds.isEmpty()) {
                    visibleKinds.add(kind)
                } else if (kind in visibleKinds) {
                    visibleKinds.remove(kind)
                } else {
                    visibleKinds.add(kind)
                }
                timeline.visibleKinds = visibleKinds.toSet()
                renderEventChips(filterRow)
            }, LinearLayout.LayoutParams(-2, ui.dp(40)))
        }
        body.addView(filterRow, LinearLayout.LayoutParams(-1, -2))

        timeline = EventTimelineView(ui.context, ui) { fraction -> seekTo(fraction) }
        timeline.events = events
        timeline.durationMs = duration()
        timeline.positionMs = positionMs
        body.addView(timeline, LinearLayout.LayoutParams(-1, -2))

        eventListHost = ui.column()
        body.addView(eventListHost, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        val toggle = ui.chip("事件摘要", false) { onlySelectedEvent = !onlySelectedEvent; renderEvents() }
        body.addView(toggle, LinearLayout.LayoutParams(-2, ui.dp(40)).apply { topMargin = ui.dp(4) })
        renderEvents()
        if (sidecarError != null) {
            body.addView(ui.note(sidecarError!!, error = true), LinearLayout.LayoutParams(-1, -2))
        }
        return card
    }

    private fun renderEventChips(row: WrapRow) {
        row.removeAllViews()
        EventKind.entries.forEach { kind ->
            row.addView(ui.chip(kind.label, kind in visibleKinds || visibleKinds.isEmpty()) {
                if (visibleKinds.isEmpty()) {
                    visibleKinds.add(kind)
                } else if (kind in visibleKinds) {
                    visibleKinds.remove(kind)
                } else {
                    visibleKinds.add(kind)
                }
                timeline.visibleKinds = visibleKinds.toSet()
                renderEventChips(row)
                renderEvents()
            }, LinearLayout.LayoutParams(-2, ui.dp(40)))
        }
    }

    /** 事件摘要：点一条就 seek 到那一刻——这是取证时跳过"拖进度条找画面"的最短路径。 */
    private fun renderEvents() {
        if (!::eventListHost.isInitialized) return
        eventListHost.removeAllViews()
        eventListHost.visibility = if (onlySelectedEvent) View.VISIBLE else View.GONE
        if (events.isEmpty()) {
            eventListHost.addView(ui.text("本段无事件", RecordRole.DESCRIPTION), LinearLayout.LayoutParams(-1, -2))
            return
        }
        EventReader.summarize(events, 10).forEach { event ->
            val row = ui.row().apply {
                minimumHeight = ui.dp(56)
                isClickable = true
                setOnClickListener { positionMs = event.startMs; syncPosition() }
            }
            row.addView(ui.text(event.timeLabel(), RecordRole.VALUE), LinearLayout.LayoutParams(ui.dp(56), -2))
            row.addView(ui.text(event.kind.label, RecordRole.LABEL), LinearLayout.LayoutParams(0, -2, 1f))
            val detail = if (event.isDiscrete) "瞬时" else "${event.durationMs} ms" +
                (event.peak?.let { " · 峰值 ${it.toInt()}%" } ?: "")
            row.addView(ui.text(detail, RecordRole.DESCRIPTION), LinearLayout.LayoutParams(-2, -2))
            eventListHost.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        eventListHost.addView(
            ui.text("事件由制动/油门/辅助状态阈值判定，仅用于定位与保留保护，不做碰撞或事故认定。", RecordRole.DESCRIPTION),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) },
        )
    }

    private fun playbackHint() {
        if (entry?.completeness != SegmentCompleteness.COMPLETE) {
            host.toastLine("该片段不是完整片段（${entry?.completeness?.label}），回放按可用内容进行")
        }
    }

    override fun onShown() {
        // 片段可能在库刷新后才有/被替换，重新取一次并加载 sidecar
        val refreshed = host.library.find(segmentId)
        if (refreshed != null && refreshed.id != entry?.id) {
            entry = refreshed
            loadSidecar()
        }
        renderPicture() // 重建解码器并恢复当前时钟
        syncPosition()
    }

    override fun onHidden() {
        pause()
        player?.release()
        player = null
        textureViews.clear()
    }

    override fun onDataChanged() {
        entry = host.library.find(segmentId) ?: entry
        renderNeighbors()
        syncPosition()
    }

    override fun onTick() {
        // 播放用独立 200ms 节拍推进；这里不需要额外动作。
    }

    override fun onBack(): Boolean {
        if (playing) {
            pause()
            return true
        }
        return false
    }

    private companion object {
        const val TICK_MS = 200L
        const val DEFAULT_DURATION_MS = 60_000L
    }
}
