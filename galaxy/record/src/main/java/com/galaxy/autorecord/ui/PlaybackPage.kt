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
import com.galaxy.autorecord.core.VehicleTrace
import com.galaxy.autorecord.core.VehicleHistoryTracks
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
        get() = entry?.let { RecordTime.dayTime(it.startedAtMillis) + "\n" +
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(it.startedAtMillis)) } ?: "回放"

    private var entry: SegmentEntry? = host.library.find(segmentId)
    private var events: List<EventMark> = emptyList()
    private var samples: List<VehicleSample> = emptyList()
    private var traces: List<VehicleTrace> = emptyList()
    private var historyIntervalMs = 250
    private data class HistoryData(val samples: List<VehicleSample>, val traces: List<VehicleTrace>, val events: List<EventMark>,
        val error: String?, val firstFrameMs: Long, val intervalMs: Int,
        val lenses:Map<String,com.galaxy.autorecord.preview.LensProfile>,val legacyLens:Boolean)
    private var sidecarError: String? = null
    private var lensesLoaded=false
    private var lenses=emptyMap<String,com.galaxy.autorecord.preview.LensProfile>()
    private var flat=host.settings.flatView
    private var legacyLens=false

    private var playing = false
    private var positionMs = 0L
    private var speed = host.settings.playbackSpeed
    private var singleDirection: String? = null
    private var visibleKinds = EventKind.entries.toMutableSet()
    private var selectedEvent: EventMark? = null
    private var showSelected = false
    private lateinit var selectedTab: TextView
    private lateinit var allTab: TextView

    private lateinit var panel: RecordPlayerPanel
    private lateinit var pictureHost: FrameLayout
    private val surfaces = mutableMapOf<String, Surface>()
    private lateinit var timeline: EventTimelineView
    private lateinit var progress: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var playButton: TextView
    private lateinit var eventCountLabel: TextView
    private lateinit var eventDetail: TextView
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
        host.readPlaybackData({
            val history = VehicleHistory.read(dir)
            val recorded = runCatching { EventReader.read(dir) }
            val manifest = com.galaxy.autorecord.export.ExportPlanner.readManifest(dir)
            val interval = history.mapNotNull { it.intervalMs }.maxOrNull() ?: manifest?.vehicle?.intervalMs ?: 250
            val tracks = VehicleHistoryTracks.build(history, duration(), interval)
            val intervalKeys = tracks.filter { it.pedal || it.binary }.map { it.key.key }.toSet()
            val changes = VehicleHistory.stateEvents(history, interval * 2L + 25).mapNotNull { mark ->
                val values = mark.changes.filterKeys { it !in intervalKeys }
                if (values.isEmpty()) null else mark.copy(changes = values)
            }
            val intervals = tracks.filter { it.pedal || it.binary }.flatMap { it.intervals() }
            val firstFrameMs = manifest?.channels
                ?.filter { it.status == com.galaxy.autorecord.core.SegmentManifest.CHANNEL_COMPLETE }
                ?.minOfOrNull { it.firstPtsUs / 1000 } ?: 0L
            val lensFile=File(dir,"lens.json")
            val savedLens=if(lensFile.exists())org.json.JSONObject(lensFile.readText())else null
            val profiles=manifest?.channels.orEmpty().mapNotNull{ch->
                val profile=if(savedLens!=null)savedLens.optJSONObject(ch.direction)?.let{com.galaxy.autorecord.preview.LensProfile.parse(it.toString(),width=ch.width,height=ch.height)}
                else com.galaxy.autorecord.core.CameraLayoutStore(host).load().cameraIdOf(ch.direction)?.let{id->
                    com.galaxy.autorecord.preview.LensProfile.read(host,ch.direction,id.toString(),ch.width,ch.height)}
                profile?.let{ch.direction to it}
            }.toMap()
            HistoryData(history, tracks, (recorded.getOrDefault(emptyList()).filter { it.kind !in setOf(EventKind.BRAKE,EventKind.ACCEL) } + changes + intervals).sortedBy { it.startMs },
                recorded.exceptionOrNull()?.message, firstFrameMs, interval,profiles,savedLens==null)
        }) { result ->
            if (entry?.dir != dir) return@readPlaybackData
            result.onSuccess { loaded ->
                lenses=loaded.lenses;lensesLoaded=true;legacyLens=loaded.legacyLens
                if(flat&&lenses.isEmpty())flat=false
                rebuildPlayer()
                samples = loaded.samples; traces = loaded.traces; events = loaded.events; sidecarError = loaded.error
                historyIntervalMs = loaded.intervalMs
                if (::timeline.isInitialized) timeline.traces = traces
                if (positionMs == 0L) { positionMs = loaded.firstFrameMs; player?.seekTo(positionMs) }
                updateEvents(); syncPosition()
            }.onFailure { lensesLoaded=true;flat=false;rebuildPlayer();sidecarError = it.message ?: "历史数据读取失败"; updateEvents() }
        }
    }

    override fun build(): View {
        val root = ui.column()
        panel = RecordPlayerPanel(ui)
        panel.fitPictureToAvailableHeight()
        pictureHost = panel.picture
        root.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildControls(), LinearLayout.LayoutParams(-1, ui.dp(60)))
        root.addView(buildTimeline(), LinearLayout.LayoutParams(-1, -2))
        neighborRow = ui.row().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        root.addView(neighborRow, LinearLayout.LayoutParams(-1, ui.dp(44)))
        renderNeighbors()
        renderPicture()
        return root
    }

    override fun rightSlot(container: LinearLayout) {
        val chips = mutableMapOf<String?, TextView>()
        (listOf<String?>(null) + SegmentLayout.DIRECTIONS).forEach { direction ->
            val chip = ui.tab(direction?.let { directionLabel(it).take(1) } ?: "全部", singleDirection == direction) {
                singleDirection = direction
                chips.forEach { (key, view) ->
                    ui.styleTab(view, key == direction)
                }
                renderPicture()
            }.apply { textSize = 16f; setPadding(ui.dp(12), 0, ui.dp(12), 0) }
            chips[direction] = chip
            container.addView(chip, LinearLayout.LayoutParams(-2, ui.dp(44)))
        }
        val viewMode=ui.tab(if(flat)"广角平面"else"鱼眼",flat){}
        viewMode.setOnClickListener{
            val next=!flat
            if(!lensesLoaded){host.toastLine("正在读取录像镜头参数");return@setOnClickListener}
            val directions=singleDirection?.let{listOf(it)}?:SegmentLayout.DIRECTIONS
            if(next&&directions.any{entry?.let{e->hasVideoFile(e,it)}==true&&it !in lenses}){
                host.toastLine("这段录像缺少对应镜头标定，无法展平");return@setOnClickListener
            }
            if(player?.setFlat(next)!=false){flat=next;viewMode.text=if(flat)"广角平面"else"鱼眼";ui.styleTab(viewMode,flat);host.saveFlatView(flat)
                if(flat&&legacyLens)host.toastLine("旧录像使用当前镜头标定，请核对当时的镜头方位")}
        }
        container.addView(viewMode,LinearLayout.LayoutParams(-2,ui.dp(44)))
        container.addView(transport("more", "片段操作") { entry?.let { SegmentActions.show(host, it) } },
            LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
    }

    private fun renderPicture() {
        player?.let { positionMs = it.positionMs }
        player?.release()
        player = null
        surfaces.values.forEach { it.release() }
        surfaces.clear()
        pictureHost.removeAllViews()
        textureViews.clear()
        val directions = singleDirection?.let { listOf(it) } ?: SegmentLayout.DIRECTIONS
        pictureHost.addView(RecordPlayerPanel.grid(ui, directions, true, ::frameCell), FrameLayout.LayoutParams(-1, -1))
        rebuildPlayer()
    }

    /** 重建解码器（布局切换/回显）：保持当前时钟与播放态，不从头重播。 */
    private fun rebuildPlayer() {
        player?.release()
        player = null
        if(!lensesLoaded)return
        val current = entry ?: return
        val directions = (singleDirection?.let { listOf(it) } ?: SegmentLayout.DIRECTIONS)
            .filter { !current.missingDirections.contains(it) && hasVideoFile(current, it) }
        if (directions.isEmpty()) return
        if(flat&&directions.any{it !in lenses})flat=false
        val p = SegmentPlayer(current.dir, directions, duration(),lenses,flat)
        p.speed = speed
        p.onError = { message -> handler.post { host.toastLine(message) } }
        player = p
        textureViews.forEach { (dir, view) ->
            view.surfaceTexture?.let { st ->
                val surface = surfaces.getOrPut(dir) { Surface(st) }
                p.attach(dir, surface)
            }
        }
        p.seekTo(positionMs)
        if (playing) p.play()
    }

    private fun hasVideoFile(entry: SegmentEntry, direction: String): Boolean =
        SegmentLayout.videoFile(entry.dir, direction).let { it.isFile && it.length() > 0 }

    private fun frameCell(direction: String): View {
        val current = entry
        val missing = current == null ||
            current.missingDirections.contains(direction) ||
            !hasVideoFile(current, direction)
        val frame = FrameLayout(ui.context).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        if (!missing && current != null) {
            val texture = TextureView(ui.context)
            textureViews[direction] = texture
            frame.addView(texture, FrameLayout.LayoutParams(-1, -1))
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                private var ownedSurface: Surface? = null
                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                    if (textureViews[direction] !== texture) return
                    surfaces.remove(direction)?.release()
                    val surface = Surface(st)
                    ownedSurface = surface
                    surfaces[direction] = surface
                    fitTexture(texture, direction, w, h)
                    player?.attach(direction, surface)
                }
                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) { fitTexture(texture, direction, w, h) }
                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                    // 旧布局的迟到销毁只能释放它借出的 Surface，不能拆掉同方向的新解码器。
                    if (textureViews[direction] === texture) {
                        player?.detach(direction)
                        surfaces.remove(direction)
                    }
                    ownedSurface?.release()
                    ownedSurface = null
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

    /** 按录像真实宽高等比完整显示，不拉伸鱼眼画面。 */
    private fun fitTexture(texture: TextureView, direction: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val channel = entry?.dir?.let(com.galaxy.autorecord.export.ExportPlanner::readManifest)
            ?.channels?.firstOrNull { it.direction == direction }
        val ratio = if (channel != null && channel.height > 0) channel.width.toFloat() / channel.height else 1.6f
        val viewRatio = width.toFloat() / height
        texture.setTransform(android.graphics.Matrix().apply {
            setScale(if (viewRatio > ratio) ratio / viewRatio else 1f,
                if (viewRatio < ratio) viewRatio / ratio else 1f, width / 2f, height / 2f)
        })
    }

    private fun renderData() {
        if (!::panel.isInitialized) return
        val values = traces.mapNotNull { trace ->
            val point = trace.points.lastOrNull { it.timeMs <= positionMs }?.takeIf { positionMs-it.timeMs <= trace.maxGapMs }
            point?.value?.let { trace.key.key to it }
        }.toMap()
        panel.bind(values, (entry?.startedAtMillis ?: 0L) + positionMs)
    }

    private fun transport(kind: String, label: String, action: () -> Unit): TextView =
        ui.transport(kind, label, action)

    private fun buildControls(): View {
        val row = ui.row().apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)); setBackgroundColor(ui.surface) }
        row.addView(transport("previous", "上一事件") { jumpEvent(-1) }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        playButton = transport("play", "播放") { togglePlay() }
        row.addView(playButton, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        row.addView(transport("next", "下一事件") { jumpEvent(1) }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        progress = SeekBar(ui.context).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) { positionMs = duration() * value / 1000; syncPosition() }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { pause() }
                override fun onStopTrackingTouch(bar: SeekBar?) { player?.seekTo(positionMs); syncPosition() }
            })
        }
        row.addView(progress, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
        timeLabel = ui.text("", RecordRole.DESCRIPTION).apply { textSize = 14f; isSingleLine = true }
        row.addView(timeLabel, LinearLayout.LayoutParams(-2, -2))
        val speedButton = ui.tab("${speed}×", false) {
            android.app.AlertDialog.Builder(host).setTitle("播放速度")
                .setSingleChoiceItems(RecordSettings.SPEED_OPTIONS.map { "${it}×" }.toTypedArray(),
                    RecordSettings.SPEED_OPTIONS.indexOf(speed)) { dialog, index ->
                    speed = RecordSettings.SPEED_OPTIONS[index]
                    player?.speed = speed
                    host.updateSettings { it.copy(playbackSpeed = speed) }
                    (row.getChildAt(row.childCount - 1) as TextView).text = "${speed}×"
                    dialog.dismiss()
                }.show()
        }.apply { textSize = 14f; setPadding(ui.dp(10), 0, ui.dp(10), 0) }
        row.addView(speedButton, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginStart = ui.dp(8) })
        syncPosition()
        return row
    }

    private fun renderNeighbors() {
        if (!::neighborRow.isInitialized) return
        neighborRow.removeAllViews()
        val current = entry ?: return
        neighborRow.addView(ui.text(entry?.let { RecordTime.wallTime(it.startedAtMillis + positionMs) + " · 历史车辆数据" } ?: "历史车辆数据", RecordRole.DESCRIPTION), LinearLayout.LayoutParams(0, -2, 1f))
        val (prev, next) = host.library.neighbors(current, host.settings.segmentSeconds)
        listOf(prev to "上一段", next to "下一段").forEach { (segment, label) ->
            neighborRow.addView(ui.tab(label, false) { segment?.let { host.openPlayback(it.id) } }.apply {
                isEnabled = segment != null; alpha = if (isEnabled) 1f else .4f; textSize = 14f
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
        playButton.contentDescription = if (playing) "暂停" else "播放"
        playButton.setCompoundDrawables(ui.glyphDrawable(if (playing) "pause" else "play", ui.textColor).apply {
            setBounds(0, 0, ui.dp(24), ui.dp(24))
        }, null, null, null)
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
            selectedEvent = target
            updateEvents()
            positionMs = (target?.startMs ?: if (direction > 0) duration() else 0L).coerceIn(0, duration())
        }
        player?.seekTo(positionMs)
        syncPosition()
    }

    private fun filteredEvents(): List<EventMark> {
        val base = events.filter { it.kind in visibleKinds }
        return base.sortedBy { it.startMs }
    }

    // ---- 事件时间轴 ----

    private fun buildTimeline(): View {
        val body = ui.column().apply { setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(8)); setBackgroundColor(ui.surface) }
        val header = ui.row()
        eventCountLabel = ui.text("", RecordRole.DESCRIPTION)
        header.addView(eventCountLabel, LinearLayout.LayoutParams(0, -2, 1f))
        selectedTab = ui.tab("选中事件") {
            selectedEvent = selectedEvent ?: filteredEvents().minByOrNull { kotlin.math.abs(it.startMs - positionMs) }
            showSelected = selectedEvent != null
            updateEvents()
        }.apply { textSize = 12f }
        allTab = ui.tab("全部事件", true) { showSelected = false; updateEvents() }.apply { textSize = 12f }
        header.addView(selectedTab, LinearLayout.LayoutParams(-2, ui.dp(36)))
        header.addView(allTab, LinearLayout.LayoutParams(-2, ui.dp(36)))
        header.addView(transport("event_previous", "上一事件") { jumpEvent(-1) }, LinearLayout.LayoutParams(ui.dp(38), ui.dp(42)))
        header.addView(transport("event_next", "下一事件") { jumpEvent(1) }, LinearLayout.LayoutParams(ui.dp(38), ui.dp(42)))
        header.addView(transport("sliders", "事件筛选") { showEventFilter() }, LinearLayout.LayoutParams(ui.dp(38), ui.dp(42)))
        body.addView(header, LinearLayout.LayoutParams(-1, ui.dp(48)))
        timeline = EventTimelineView(ui.context, ui) { fraction -> seekTo(fraction) }
        timeline.durationMs = duration(); timeline.positionMs = positionMs
        timeline.traces = traces
        timeline.onEventSelected = { event -> selectedEvent = event; updateEvents() }
        body.addView(android.widget.ScrollView(ui.context).apply {
            isFillViewport = false
            addView(timeline, android.widget.FrameLayout.LayoutParams(-1,-2))
        }, LinearLayout.LayoutParams(-1, ui.dp(210)))
        eventDetail = ui.text("正在读取历史车辆数据…", RecordRole.DESCRIPTION).apply { maxLines = 2 }
        body.addView(eventDetail, LinearLayout.LayoutParams(-1, -2))
        updateEvents()
        if (sidecarError != null) body.addView(ui.note(sidecarError!!, true), LinearLayout.LayoutParams(-1, -2))
        return body
    }

    private fun updateEvents() {
        if (!::timeline.isInitialized) return
        val filtered = filteredEvents()
        val selected = selectedEvent?.takeIf { it.kind in visibleKinds && (it in filtered || it.ruleVersion == "raw_trace_v1") }
        if (showSelected && selected == null) showSelected = false
        timeline.events = if (showSelected) listOfNotNull(selected) else filtered
        timeline.visibleKinds = if (showSelected && selected != null) setOf(selected.kind) else visibleKinds
        eventCountLabel.text = if (showSelected) "1 个选中事件" else "${filtered.size} 个事件"
        if (::eventDetail.isInitialized) {
            eventDetail.text = when {
                sidecarError != null -> sidecarError
                selected != null -> selected.timeLabel() + " · " + selected.kind.label + "  " +
                    selected.changes.entries.joinToString("；") { (key, value) -> "${VehicleKey.of(key)?.label ?: key} $value" }
                samples.isEmpty() -> "本段没有可用车辆样本，回看不使用实时车辆值"
                else -> "${samples.size}条样本 · 周期${historyIntervalMs}ms · 油门绿/刹车红，浅轻深重 · 点行首展开曲线"
            }
        }
        selectedTab.isEnabled = filtered.isNotEmpty()
        selectedTab.alpha = if (selectedTab.isEnabled) 1f else .45f
        ui.styleTab(selectedTab, showSelected)
        ui.styleTab(allTab, !showSelected)
    }

    private fun showEventFilter() {
        val kinds = EventKind.entries
        val pending = visibleKinds.toMutableSet()
        android.app.AlertDialog.Builder(host).setTitle("事件筛选")
            .setMultiChoiceItems(kinds.map { it.label }.toTypedArray(),
                BooleanArray(kinds.size) { kinds[it] in pending }) { _, index, checked ->
                if (checked) pending.add(kinds[index]) else pending.remove(kinds[index])
            }.setPositiveButton("完成") { _, _ -> visibleKinds = pending; updateEvents() }
            .setNeutralButton("全部") { _, _ -> visibleKinds = kinds.toMutableSet(); updateEvents() }
            .setNegativeButton("取消", null).show()
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
        surfaces.values.forEach { it.release() }
        surfaces.clear()
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
