package com.galaxy.autorecord.recording

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.galaxy.autorecord.core.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 会话级Camera2/YUV/codec + 有界编码包队列 + 单所有者分段写盘。普通换段不重建采集链。 */
class RecordingEngine(
    private val volumes: StorageVolumes,
    private val feed: VehicleFeed,
    private val surfaceBinder: RecordSurfaceBinder,
    private val settingsProvider: () -> RecordSettings,
    private val appVersionName: String,
    private val encoderPicker: (RecordSettings) -> EncoderInfo? = { CodecCapabilities.pick(it.codecMime,it.encoderImplementation) },
) : RecordingEnginePort {
    fun markSentinel(atNanos:Long,sequence:Long){handler.post{session?.takeIf{!it.stopping}?.writerOrNull()?.markSentinel(atNanos,sequence)}}
    private val thread = HandlerThread("record-engine").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var released = false
    private var session: Session? = null
    private class Session(val settings: RecordSettings, val listener: RecordingEngineListener, val clock: SegmentClock) {
        val readers = linkedMapOf<String,RecordingFrameReader>()
        val codecs = linkedMapOf<String,ContinuousCodec>()
        val threads = mutableListOf<HandlerThread>()
        lateinit var writer: ContinuousSegmentWriter
        fun writerOrNull() = if (::writer.isInitialized) writer else null
        var stopping = false
        var nextSyncUs = clock.firstEndUs
        var diagnosticsAt=0L
        var captureArmedUs=0L
        var error: String? = null
    }
    override fun start(directions: List<String>, settings: RecordSettings, listener: RecordingEngineListener) {
        if (released) return
        handler.post {
            if (session != null) { listener.onStopped("录制引擎已在运行"); return@post }
            try { startSession(directions,settings,listener) }
            catch (e: Exception) {
                RecordDiagnostics.event("engine_start",e)
                if (session == null) listener.onStopped("录制启动失败：${e.javaClass.simpleName}")
                else stop("录制启动失败：${e.javaClass.simpleName}")
            }
        }
    }
    private fun startSession(directions: List<String>, settings: RecordSettings, listener: RecordingEngineListener) {
        val dirs = directions.filter { it in SegmentLayout.DIRECTIONS }.distinct()
        require(dirs.isNotEmpty()) { "no_record_directions" }
        check(!(settings.encoderImplementation == RecordSettings.IMPL_SOFTWARE && dirs.size >= 3)) { "four_software_codecs_not_verified" }
        val volume = checkNotNull(volumes.resolve(settings.storageVolumeUuid)) { "storage_unavailable" }
        check(volume.mounted); val root = checkNotNull(volume.appDir); check(volumes.probeWritable(root))
        val info = checkNotNull(encoderPicker(settings)) { "selected_encoder_unavailable" }
        val resolution = RetentionPlan.resolution(settings.requestedHeight)
        val size = checkNotNull(CodecCapabilities.alignAndCheck(info,resolution.first,resolution.second,settings.fps)) { "selected_encoder_size_unsupported" }
        val clock = SegmentClock(System.currentTimeMillis(),SystemClock.elapsedRealtimeNanos()/1000,settings.segmentSeconds)
        val s = Session(settings,listener,clock); session=s
        EngineDiagnostics.reset(); EngineDiagnostics.clockAnchor=EngineDiagnostics.ClockAnchor(clock.wallMs,clock.monoUs*1000)
        fun failure(e: Exception) { handler.post { if (session === s && !s.stopping) {
            RecordDiagnostics.event("record_pipeline",e); stop("录制链路失败：${e.javaClass.simpleName}")
        } } }
        dirs.forEach { direction ->
            val codec = ContinuousCodec(direction,info,size[0],size[1],settings.fps,
                BitratePlan.forMode(settings.bitrateMode,size[0],size[1],settings.fps),settings.gopSeconds,
                { format -> s.writer.format(direction,format) },
                { data, sample -> s.writer.sample(direction,data,sample) }, ::failure)
            codec.scheduleKeyFrames(clock)
            s.codecs[direction]=codec
        }
        // writer先建事务并提前准备下一段，再允许编码输出；所有事务操作均在writer线程。
        val lensProfiles=dirs.mapNotNull{direction->(surfaceBinder as? CameraChannelSource)?.lensProfile(direction)?.let{direction to it}}.toMap()
        s.writer=ContinuousSegmentWriter(root,settings,clock,s.codecs,feed,appVersionName,listener,::failure,lensProfiles)
        s.writer.initialize()
        dirs.forEach { direction ->
            val frameThread=HandlerThread("record-frames-$direction").apply { start() }; s.threads.add(frameThread)
            val codec=s.codecs.getValue(direction)
            val reader=RecordingFrameReader(size[0],size[1],Handler(frameThread.looper),{codec.queue(it)},::failure)
            s.readers[direction]=reader
            codec.start(); surfaceBinder.bindRecordSurface(direction,reader.surface)
        }
        s.captureArmedUs=SystemClock.elapsedRealtimeNanos()/1000
        RecordDiagnostics.event("continuous_session_start")
        handler.post(ticker)
    }
    private val ticker = object : Runnable {
        override fun run() {
            val s=session ?: return
            if(s.stopping)return
            try {
                val now=SystemClock.elapsedRealtimeNanos()/1000
                val stalled=OutputWatchdog.stalled(now,s.captureArmedUs,s.codecs.mapValues{it.value.lastOutputUs})
                if(stalled.isNotEmpty()) {
                    val failure=IllegalStateException("encoded_output_timeout")
                    RecordDiagnostics.event("channel_output_timeout",failure,mapOf("direction" to stalled.joinToString(","),"timeoutMs" to 8000))
                    stop("${stalled.joinToString("/")} 编码输出超时（8秒无有效输出）")
                    return
                }
                if(now-s.diagnosticsAt>=1_000_000){
                    s.diagnosticsAt=now
                    EngineDiagnostics.pipeline="queue=${s.writer.queuedPacketCount} queueBytes=${s.writer.queuedPacketBytes} finalizing=${s.writer.finalizingCount} finalizeStage=${s.writer.finalizeStage} writeStage=${s.writer.writeStage} writeDirection=${s.writer.writeDirection} sampleMaxUs=${s.writer.maxSampleWriteUs} "+
                        s.codecs.entries.joinToString(" "){(direction,codec)->"$direction[input=${codec.inputFrames.get()},output=${codec.outputFrames.get()},waits=${codec.inputWaits.get()},discarded=${codec.backpressure.get()},copyUs=${codec.copyMicros.get()},layout=${codec.inputLayout}]"}
                }
                s.listener.onProgress(s.writer.bytesWritten,((now-s.clock.startUs(s.writer.activeIndex))/1000).coerceAtLeast(0),s.writer.activeIndex)
                handler.postDelayed(this,100)
            } catch(e:Exception){RecordDiagnostics.event("engine_progress",e);stop("录制进度失败：${e.javaClass.simpleName}")}
        }
    }
    override fun requestStop() { if(!released) handler.post { stop(null) } }
    override fun release() {
        if(released)return
        released=true
        handler.post { try { stop(null) } finally { thread.quitSafely() } }
    }
    private fun stop(reason: String?) {
        val s=session ?: return
        if(s.stopping)return
        s.stopping=true; handler.removeCallbacks(ticker)
        var failure=reason
        try {
            // 唤醒可能正在等输入缓冲的取帧线程，再等图像归还；不能等锁时反向死锁。
            s.codecs.values.forEach { it.stopInput() }
            s.readers.values.forEach { it.stopConsuming() }
            if(s.readers.isNotEmpty()) {
                val readers=s.readers.toMap(); val done=CountDownLatch(1); val result=AtomicReference<Throwable?>()
                try { surfaceBinder.detachRecordSurfaces(readers.mapValues { it.value.surface }) { detached ->
                    try { detached.getOrThrow(); readers.values.forEach { it.close() } }
                    catch(e:Exception){result.set(e)} finally {done.countDown()}
                } } catch(e:Exception){result.set(e);done.countDown()}
                if(!done.await(5,TimeUnit.SECONDS))failure=failure?:"相机解绑超时，等待迟到回执释放Reader"
                result.get()?.let {failure=failure?:"相机解绑失败：${it.javaClass.simpleName}"}
            }
            s.codecs.values.forEach { codec ->
                if(!codec.finish())failure=failure?:"${codec.direction} 编码收尾超时"
            }
            s.writerOrNull()?.finish(failure)
        } catch(e:Exception){RecordDiagnostics.event("engine_stop",e);failure=failure?:"录制收尾失败：${e.javaClass.simpleName}"}
        finally {
            s.threads.forEach {it.quitSafely()}
            session=null
            EngineDiagnostics.lastError=failure
            EngineDiagnostics.pipeline="stopped reason=${failure?:"user"} "+EngineDiagnostics.pipeline
            RecordDiagnostics.event("continuous_session_stop")
            s.listener.onStopped(failure)
        }
    }
}
