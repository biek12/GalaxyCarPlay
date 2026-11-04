package com.galaxy.autorecord.recording

import android.graphics.Bitmap
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.galaxy.autorecord.core.AtomicFiles
import com.galaxy.autorecord.core.BitratePlan
import com.galaxy.autorecord.core.CodecCapabilities
import com.galaxy.autorecord.core.EventsRecord
import com.galaxy.autorecord.core.RecordSettings
import com.galaxy.autorecord.core.RecordSurfaceBinder
import com.galaxy.autorecord.core.RecordVehicleAdapter
import com.galaxy.autorecord.core.RecordingEngineListener
import com.galaxy.autorecord.core.RecordingEnginePort
import com.galaxy.autorecord.core.RetentionPlan
import com.galaxy.autorecord.core.SegmentLayout
import com.galaxy.autorecord.core.SegmentManifest
import com.galaxy.autorecord.core.StorageVolumes
import com.galaxy.autorecord.core.VehicleFeed
import com.galaxy.autorecord.storage.FinishOutcome
import com.galaxy.autorecord.storage.RealSegmentStorePort
import com.galaxy.autorecord.storage.SegmentTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 四路录制引擎（B3）：段事务 + 编码写盘 + 段滚动 + 车辆/事件落盘 + 循环覆盖。
 *
 * 纪律（编码与存储技术口径 §3/§4/§5、页面规格 §14）：
 * - 只认"出帧+写盘+段 rename 完成"为录制成功；编码器创建成功不算。
 * - 收尾顺序不可交换：EOS → drain → muxer.stop → release → fsync → 改名 → 写清单。
 * - PTS 用相机帧时间（SENSOR_TIMESTAMP 域）映射到段相对时间轴，禁止帧号×固定时长。
 * - 影响编码/写盘的设置"下一片段生效"：换段时重建编码器并应用新参数，当前段不中断。
 * - 禁止静默回退：编码器/卷失败即报具名原因；存储卷消失不静默回切内置。
 * - 不创建无界线程：1 条引擎线程 + 每路 1 条 drain 线程，随会话结束退出。
 */
class RecordingEngine(
    private val volumes: StorageVolumes,
    private val feed: VehicleFeed,
    private val surfaceBinder: RecordSurfaceBinder,
    private val settingsProvider: () -> RecordSettings,
    private val appVersionName: String,
) : RecordingEnginePort {

    private val thread = HandlerThread("record-engine").apply { start() }
    private val handler = Handler(thread.looper)
    /** YUV 取帧线程（每路一条，共 4 条有界线程；避免共享回调排队丢帧）。 */
    private val frameThreads = List(4) { index -> HandlerThread("record-frames-$index").apply { start() } }
    private val frameHandlers = frameThreads.map { Handler(it.looper) }

    @Volatile private var running = false
    @Volatile private var released = false

    private var session: Session? = null

    private class Session(
        val directions: List<String>,
        val settings: RecordSettings,
        val listener: RecordingEngineListener,
        val rootDir: File,
        val volumeUuid: String?,
        val volumeLabel: String,
        val freeBytes: Long,
        val totalBytes: Long,
    ) {
        var segmentIndex = 0
        var tx: SegmentTransaction? = null
        var segmentDir: File? = null
        val encoders = LinkedHashMap<String, ChannelEncoder>()
        val readers = LinkedHashMap<String, ImageReader>()
        var scriber: VehicleScriber? = null
        var detector: EventDetector? = null
        var segmentStartMonoNs = 0L
        var segmentStartWallMs = 0L
        var recordingActive = false
        var stopping = false
        var channelFailures = linkedMapOf<String, String>()
    }

    override fun start(directions: List<String>, settings: RecordSettings, listener: RecordingEngineListener) {
        if (released) return
        handler.post { startOnHandler(directions, settings, listener) }
    }

    override fun requestStop() {
        handler.post { stopSegmentOnHandler(final = true, error = null) }
    }

    override fun release() {
        if (released) return
        released = true
        handler.post {
            if (running) stopSegmentOnHandler(final = true, error = null)
        }
        thread.quitSafely()
        frameThreads.forEach { it.quitSafely() }
    }

    // ---- 引擎线程 ----

    private fun startOnHandler(directions: List<String>, settings: RecordSettings, listener: RecordingEngineListener) {
        if (running) {
            listener.onStopped("录制引擎已在运行")
            return
        }
        val dirs = directions.filter { it in SegmentLayout.DIRECTIONS }.distinct()
        if (dirs.isEmpty()) {
            listener.onStopped("没有可录制的方向")
            return
        }
        // 软编 + 3/4 路必须拒绝（技术口径 §2）
        if (settings.encoderImplementation == RecordSettings.IMPL_SOFTWARE && dirs.size >= 3) {
            listener.onStopped("四路软件编码未在本车验证（软编仅支持 1–2 路），已拒绝开始")
            return
        }
        val volume = volumes.resolve(settings.storageVolumeUuid)
        if (volume == null || !volume.mounted) {
            listener.onStopped("存储卷不可用（未挂载或已移除），未自动切换介质")
            return
        }
        val appDir = volume.appDir
        if (appDir == null || !volumes.probeWritable(appDir)) {
            listener.onStopped("存储卷「${volume.label}」不可写")
            return
        }

        val quota = settings.effectiveQuotaBytes(volume.totalBytes)
        val incoming = RetentionPlan.segmentBytes(settings.segmentSeconds, settings.requestedHeight, settings.fps, settings.bitrateMode)
        val room = RotationJanitor.makeRoom(
            segmentsRoot = SegmentLayout.segmentsRoot(appDir),
            indexDir = SegmentLayout.indexDir(appDir),
            quotaBytes = quota,
            freeBytes = volume.freeBytes,
            minFreeBytes = settings.minFreeBytes,
            incomingBytes = incoming,
            rotate = settings.rotate,
            eventProtect = settings.eventProtect,
        )
        if (room.blockedReason != null) {
            listener.onStopped(room.blockedReason)
            return
        }

        val s = Session(
            directions = dirs,
            settings = settings,
            listener = listener,
            rootDir = appDir,
            volumeUuid = settings.storageVolumeUuid,
            volumeLabel = volume.label,
            freeBytes = volume.freeBytes,
            totalBytes = volume.totalBytes,
        )
        session = s
        running = true
        startSegmentOnHandler()
    }

    private fun startSegmentOnHandler() {
        val s = session ?: return
        if (s.stopping) return
        val settings = s.settings
        s.segmentIndex++
        s.recordingActive = false
        s.channelFailures = linkedMapOf()
        s.encoders.clear()

        val wall = System.currentTimeMillis()
        val mono = SystemClock.elapsedRealtimeNanos()
        s.segmentStartWallMs = wall
        s.segmentStartMonoNs = mono
        EngineDiagnostics.clockAnchor = EngineDiagnostics.ClockAnchor(wall, mono)

        val tx = try {
            SegmentTransaction(
                rootDir = s.rootDir,
                volumeUuid = s.volumeUuid,
                startedAtWallMillis = wall,
                startedAtMonotonicNanos = mono,
                segmentIndex = s.segmentIndex,
                selectedDirections = s.directions,
                appVersionName = appVersionName,
                store = RealSegmentStorePort(),
            ).also { it.begin() }
        } catch (error: Exception) {
            Log.w(TAG, "建段失败 #${s.segmentIndex}", error)
            failStop("建段失败：${error.javaClass.simpleName}")
            return
        }
        s.tx = tx
        s.segmentDir = tx.segmentDirectory()

        val detector = EventDetector(EventDetector.Rules.from(settings))
        s.detector = detector
        s.scriber = VehicleScriber(
            segmentDir = tx.segmentDirectory(),
            segmentStartMonoNanos = mono,
            intervalMs = settings.vehicleIntervalMs,
            feed = feed,
            keys = RecordVehicleAdapter.selected(settings),
            detector = detector,
        )

        val encoderInfo = CodecCapabilities.pick(settings.codecMime, settings.encoderImplementation)
        if (encoderInfo == null) {
            failStop("找不到符合设置的编码器（${settings.codecMime}/${settings.encoderImplementation}），未回退其他实现")
            return
        }
        val size = CodecCapabilities.alignAndCheck(
            encoderInfo,
            RetentionPlan.resolution(settings.requestedHeight).first,
            RetentionPlan.resolution(settings.requestedHeight).second,
            settings.fps,
        )
        if (size == null) {
            failStop("编码尺寸不受所选编码器支持，未上采样、未改分辨率")
            return
        }
        val bitrate = BitratePlan.forMode(settings.bitrateMode, size[0], size[1], settings.fps)
        val ptsOffsetUs = mono / 1000

        for ((channelIndex, direction) in s.directions.withIndex()) {
            val writing = SegmentLayout.writingFile(SegmentLayout.videoFile(tx.segmentDirectory(), direction))
            try {
                val encoder = ChannelEncoder.create(
                    direction = direction,
                    writing = writing,
                    encoder = encoderInfo,
                    width = size[0],
                    height = size[1],
                    fps = settings.fps,
                    bitrateBps = bitrate,
                    gopSeconds = settings.gopSeconds,
                    ptsOffsetUs = ptsOffsetUs,
                    onFirstSample = { handler.post { firstFrameOnHandler() } },
                )
                s.encoders[direction] = encoder
                // YUV 取帧：ImageReader 作为相机输出，回调按帧时间戳送编码器（路线 B，本车实测可用）
                val reader = ImageReader.newInstance(size[0], size[1], android.graphics.ImageFormat.YUV_420_888, 3)
                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val ts = image.timestamp
                    encoder.queueYuv(image, ts)
                }, frameHandlers[channelIndex % frameHandlers.size])
                s.readers[direction] = reader
            } catch (error: EncoderCreateException) {
                s.channelFailures[direction] = error.message ?: "编码器创建失败"
                Log.w(TAG, "通道创建失败 $direction：${error.message}")
            }
        }
        if (s.encoders.isEmpty()) {
            failStop("全部方向的编码器创建失败（${s.channelFailures.values.joinToString("；")}）")
            return
        }

        // 先启动 drain，再把 YUV 取帧 Surface 交给相机（相机会话重配后开始出帧）
        s.encoders.forEach { (direction, encoder) ->
            encoder.startDrain()
            val reader = s.readers[direction]
            surfaceBinder.bindRecordSurface(direction, reader?.surface)
        }

        handler.postDelayed(sampler, settings.vehicleIntervalMs.toLong().coerceIn(100L, 2000L))
        handler.postDelayed(ticker, TICK_MS)
        handler.postDelayed(rollover, settings.segmentSeconds * 1000L)
        s.listener.onSegmentStarted(s.segmentIndex, tx.segmentDirectory())
        Log.i(TAG, "段开始 #${s.segmentIndex} ${tx.segmentDirectory().name} 路=${s.encoders.keys}")
    }

    /** 车辆采样：按 vehicleIntervalMs 节拍写 vehicles.jsonl（250ms 默认），与视频同段时间轴。 */
    private val sampler = object : Runnable {
        override fun run() {
            val s = session ?: return
            if (!running || s.stopping) return
            runCatching { s.scriber?.sample(SystemClock.elapsedRealtimeNanos()) }
            handler.postDelayed(this, s.settings.vehicleIntervalMs.toLong().coerceIn(100L, 2000L))
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val s = session ?: return
            if (!running || s.stopping) return
            val now = SystemClock.elapsedRealtimeNanos()
            val bytes = s.encoders.values.sumOf { writingLength(it) }
            s.listener.onProgress(bytes, (now - s.segmentStartMonoNs) / 1_000_000L, s.segmentIndex)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val rollover = Runnable {
        val s = session ?: return@Runnable
        if (!running || s.stopping) return@Runnable
        // 换段：当前段收尾（设置在下一段生效），随后滚动腾空间并开新段
        stopSegmentOnHandler(final = false, error = null)
    }

    private fun firstFrameOnHandler() {
        val s = session ?: return
        if (!s.recordingActive && running && !s.stopping) {
            s.recordingActive = true
            s.listener.onRecordingActive()
            Log.i(TAG, "首批编码帧已写盘 #${s.segmentIndex}（出帧+写盘才算录制中）")
        }
    }

    /**
     * 收尾当前段（final=true 时结束整个会话）。
     * 顺序：解绑相机录制 Surface → 各路 EOS/drain/muxer.stop/release → 车辆/事件收尾 →
     * 事务提交（fsync+改名）→ 最终清单。全程在引擎线程串行执行。
     */
    private fun stopSegmentOnHandler(final: Boolean, error: String?) {
        val s = session ?: return
        if (s.stopping && !final) return
        s.stopping = !final
        handler.removeCallbacks(sampler)
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(rollover)

        // 1) 相机停止向取帧 Surface 写帧（会话重配），稍候再收编码器，避免写入已释放 Surface
        s.encoders.keys.toList().forEach { direction ->
            runCatching { surfaceBinder.bindRecordSurface(direction, null, s.readers[direction]?.surface) }
        }
        runCatching { Thread.sleep(CAMERA_DETACH_MS) }
        s.readers.values.forEach { reader ->
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
        }
        s.readers.clear()

        // 2) 各路编码收尾：EOS → drain → muxer.stop → release（顺序不可交换）
        val tx = s.tx
        for ((direction, encoder) in s.encoders.toList()) {
            val result = try {
                encoder.finish()
            } catch (e: Exception) {
                Log.w(TAG, "通道收尾异常 $direction", e)
                null
            }
            if (tx != null) {
                if (result != null) {
                    val outcome = tx.commitChannel(result.copy(transactionId = tx.transactionId))
                    if (outcome.failed) {
                        s.channelFailures[direction] = outcome.reason ?: "提交失败"
                        Log.w(TAG, "通道提交失败 $direction：${outcome.reason}")
                    }
                } else {
                    s.channelFailures[direction] = s.channelFailures[direction] ?: "无有效编码帧（未伪造 complete）"
                }
            }
        }
        s.encoders.clear()

        // 3) 车辆样本/事件收尾 + sidecar 落盘
        var eventsRecord: EventsRecord? = null
        val vehicleRecord = s.scriber?.close()
        s.scriber = null
        val detector = s.detector
        s.detector = null
        if (detector != null && s.segmentDir != null) {
            val events = detector.finish(maxLastSampleMs(s))
            if (events.isNotEmpty()) {
                val ok = writeEvents(s.segmentDir!!, events.map { mark ->
                    JSONObject().apply {
                        put("type", mark.kind.key)
                        put("startMs", mark.startMs)
                        put("endMs", mark.endMs)
                        mark.peak?.let { put("peak", it) }
                        put("ruleVersion", mark.ruleVersion)
                    }
                }, detector.rules.ruleVersion)
                if (ok) {
                    eventsRecord = EventsRecord(
                        ruleVersion = detector.rules.ruleVersion,
                        count = events.size,
                        file = SegmentLayout.FILE_EVENTS,
                    )
                }
            }
        }

        // 各路提交结果（在事务终止前取，供诊断实测）
        val committed = tx?.let { t ->
            s.directions.mapNotNull { d -> t.channelOutcome(d)?.channel }
        } ?: emptyList()

        // 4) 事务收尾：全部视频提交后原子发布最终清单
        val outcome: FinishOutcome? = tx?.let {
            runCatching {
                it.attachSidecar(vehicleRecord, eventsRecord)
                it.finish()
            }.getOrElse { e ->
                Log.w(TAG, "段清单收尾异常", e)
                null
            }
        }
        s.tx = null

        if (outcome != null) {
            // 5) 缩略图（从已编码文件取，不额外开采集）
            runCatching { captureThumbnail(outcome) }
            recordDiagnostics(s, outcome, committed)
            s.listener.onSegmentFinished(s.segmentIndex, outcome)
            Log.i(TAG, "段收尾 #${s.segmentIndex} ${outcome.state} complete=${outcome.isComplete} err=${outcome.error}")
        }

        if (final || error != null) {
            running = false
            session = null
            s.listener.onStopped(error)
            return
        }

        // 6) 滚动腾空间后开下一段（设置从这一段起生效）
        val settings = s.settings
        val quota = settings.effectiveQuotaBytes(s.totalBytes)
        val incoming = RetentionPlan.segmentBytes(settings.segmentSeconds, settings.requestedHeight, settings.fps, settings.bitrateMode)
        val room = RotationJanitor.makeRoom(
            segmentsRoot = SegmentLayout.segmentsRoot(s.rootDir),
            indexDir = SegmentLayout.indexDir(s.rootDir),
            quotaBytes = quota,
            freeBytes = s.freeBytes,
            minFreeBytes = settings.minFreeBytes,
            incomingBytes = incoming,
            rotate = settings.rotate,
            eventProtect = settings.eventProtect,
        )
        if (room.deleted.isNotEmpty()) {
            EngineDiagnostics.lastRotationNote = "循环覆盖：删除 ${room.deleted.size} 段，释放 ${room.freedBytes / (1024 * 1024)} MB"
            Log.i(TAG, "循环覆盖删除 ${room.deleted.size} 段，释放 ${room.freedBytes} 字节")
        }
        if (room.blockedReason != null) {
            running = false
            session = null
            s.listener.onStopped(room.blockedReason)
            return
        }
        s.stopping = false
        startSegmentOnHandler()
    }

    private fun failStop(message: String) {
        val s = session
        running = false
        session = null
        EngineDiagnostics.lastError = message
        handler.removeCallbacks(sampler)
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(rollover)
        s?.readers?.values?.forEach { runCatching { it.close() } }
        s?.readers?.clear()
        s?.encoders?.values?.forEach { runCatching { it.finish() } }
        s?.encoders?.clear()
        s?.tx?.let { runCatching { it.abort(message) } }
        runCatching { s?.scriber?.close() }
        s?.listener?.onStopped(message)
    }

    /** 诊断实测（P11）：只记真实收尾数据，不写死文案。 */
    private fun recordDiagnostics(s: Session, outcome: FinishOutcome, committed: List<com.galaxy.autorecord.core.ChannelRecord>) {
        val first = committed.firstOrNull()
        EngineDiagnostics.lastSegment = EngineDiagnostics.LastSegment(
            index = s.segmentIndex,
            state = outcome.state,
            encoderName = first?.encoderName ?: "—",
            implementation = first?.implementation ?: "—",
            width = first?.width ?: 0,
            height = first?.height ?: 0,
            fps = first?.fps ?: 0,
            bitrateBps = first?.bitrateBps ?: 0L,
            gopSeconds = first?.gopSeconds ?: 0,
            frameCount = committed.sumOf { it.frameCount },
            durationMs = committed.maxOfOrNull { it.lastPtsUs }?.div(1000) ?: 0L,
            directionsOk = outcome.completedDirections,
            directionsFailed = s.channelFailures.toMap(),
            finishedAtWallMs = System.currentTimeMillis(),
        )
    }

    private fun writingLength(encoder: ChannelEncoder): Long {
        // drain 线程在写 `.writing`；只读长度做进度展示，不干扰写入
        val dir = session?.segmentDir ?: return 0L
        val writing = SegmentLayout.writingFile(SegmentLayout.videoFile(dir, encoder.direction))
        return if (writing.exists()) writing.length() else {
            val done = SegmentLayout.videoFile(dir, encoder.direction)
            if (done.exists()) done.length() else 0L
        }
    }

    private fun maxLastSampleMs(s: Session): Long {
        val elapsed = (SystemClock.elapsedRealtimeNanos() - s.segmentStartMonoNs) / 1_000_000L
        return elapsed.coerceAtLeast(0L)
    }

    private fun writeEvents(segmentDir: File, events: List<JSONObject>, ruleVersion: String): Boolean {
        val payload = JSONObject().apply {
            put("ruleVersion", ruleVersion)
            put("events", JSONArray(events))
        }.toString().toByteArray()
        return AtomicFiles.writeAtomically(File(segmentDir, SegmentLayout.FILE_EVENTS), payload)
    }

    private fun captureThumbnail(outcome: FinishOutcome) {
        val dir = outcome.segmentDir
        val source = listOf("front", "rear", "left", "right")
            .map { SegmentLayout.videoFile(dir, it) }
            .firstOrNull { it.isFile && it.length() > 0 } ?: return
        val target = File(dir, SegmentLayout.FILE_THUMB)
        if (target.isFile && target.length() > 0) return
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.frameAtTime ?: return
            val scale = 480.0 / frame.width
            val scaled = if (scale < 1.0) {
                android.graphics.Bitmap.createScaledBitmap(frame, 480, (frame.height * scale).toInt().coerceAtLeast(1), true)
            } else frame
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
            if (scaled !== frame) scaled.recycle()
            frame.recycle()
            AtomicFiles.writeAtomically(target, out.toByteArray())
        } catch (error: Exception) {
            Log.w(TAG, "缩略图生成失败 ${dir.name}：${error.javaClass.simpleName}")
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private const val TAG = "AutoRecordEngine"
        private const val TICK_MS = 1000L
        private const val CAMERA_DETACH_MS = 300L
    }
}
