package com.galaxy.autorecord.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import com.galaxy.autorecord.core.*
import com.galaxy.autorecord.storage.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 单一写所有者：tx/muxer只在这里操作。磁盘收尾期间编码继续；队列有包数/字节双上限。 */
internal class ContinuousSegmentWriter(
    private val root: File, private val settings: RecordSettings, private val clock: SegmentClock,
    private val codecs: Map<String, ContinuousCodec>, private val feed: VehicleFeed,
    private val version: String, private val listener: RecordingEngineListener,
    private val fail: (Exception) -> Unit,
    private val lensProfiles: Map<String, com.galaxy.autorecord.preview.LensProfile> = emptyMap(),
) {
    private val queuedBytes = AtomicLong()
    // 包数按通道计预算，避免四路共享原先单路的窗口；总数据仍受 24MiB 硬上限约束。
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256 * codecs.size.coerceAtLeast(1)),
        { task -> Thread(task, "record-writer") }, ThreadPoolExecutor.AbortPolicy())
    private lateinit var finalizerOwner:Thread
    private val finalizingIds=ConcurrentHashMap.newKeySet<String>()
    private val finalizationError=AtomicReference<Exception?>()
    private val finalizationProgress=Object()
    private val finalizer=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(2),
        {task->Thread(task,"record-finalize").also{finalizerOwner=it}},ThreadPoolExecutor.AbortPolicy()).apply{prestartCoreThread()}
    val queuedPacketCount get()=executor.queue.size
    val queuedPacketBytes get()=queuedBytes.get()
    val finalizingCount get()=finalizingIds.size
    @Volatile var finalizeStage="idle";private set
    @Volatile var writeStage="initialize";private set
    @Volatile var writeDirection="none";private set
    @Volatile var maxSampleWriteUs=0L;private set
    private val formats = mutableMapOf<String, MediaFormat>()
    private val positions = codecs.keys.associateWith { 1 }.toMutableMap()
    private val segments = linkedMapOf<Int, Segment>()
    private val priorPts = mutableMapOf<String, Long>()
    @Volatile private var stopped = false
    private var quotaAllowance = 0L
    // 启动全量核对一次；之后由本会话的实际收尾与删除回执维护，不在出包线程反复扫历史。
    private val catalog = ConcurrentHashMap<String,SegmentEntry>()
    private var catalogReady=false
    private var lastFreeProbeNs = 0L
    private var lastVehicleProbeNs = 0L
    @Volatile var activeIndex = 1; private set
    @Volatile var activeDir: File? = null; private set
    @Volatile var bytesWritten = 0L; private set
    @Volatile var rotations = 0; private set
    @Volatile var highWaterBytes = 0L; private set
    private val maxBytes = 24L * 1024 * 1024
    private inner class Segment(val index: Int) {
        val tx = SegmentTransaction(root, settings.storageVolumeUuid, clock.wall(index), clock.startUs(index) * 1000,
            index, codecs.keys, version, RealSegmentStorePort()).also { transaction ->
                transaction.begin()
                if(lensProfiles.isNotEmpty())check(AtomicFiles.writeAtomically(File(transaction.segmentDirectory(),"lens.json"),
                    JSONObject().apply{lensProfiles.forEach{(direction,profile)->put(direction,profile.toJson())}}.toString().toByteArray())){"lens_profile_write_failed"}
            }
        val tracks = mutableMapOf<String, Track>()
        val detector = EventDetector(EventDetector.Rules.from(settings))
        val scriber = VehicleScriber(tx.segmentDirectory(), clock.startUs(index)*1000, settings.vehicleIntervalMs,
            feed, RecordVehicleAdapter.selected(settings), detector)
        var sampleAt = 0L
        var active = false
        var finished = false
        // writing清单保护构造阶段；文件打开成功后才取得租约，构造失败不能遗留占用。
        val use = SegmentAccess.hold(tx.segmentDirectory())
    }
    private class Track(val muxer: MediaMuxer, val track: Int) {
        var frames = 0L; var first = -1L; var last = -1L
    }
    fun markSentinel(atNanos:Long,sequence:Long)=enqueue {
        val timeUs=atNanos/1000
        val index=if(timeUs<clock.firstEndUs)1 else 2+((timeUs-clock.firstEndUs)/clock.periodUs).toInt()
        val dir=SegmentLayout.segmentDir(root,clock.wall(index),index)
        val relative=(timeUs-clock.startUs(index))/1000
        val ids=(maxOf(1,index-1)..index+1).map{SegmentLayout.segmentDir(root,clock.wall(it),it).name}
        check(SegmentMarks(SegmentLayout.indexDir(root)).setLocked(ids,true)!=null){"sentinel_protection_write_failed"}
        SegmentAccess.hold(dir).use{
            check(File(dir,SegmentLayout.FILE_MANIFEST).exists()){"sentinel_evidence_segment_missing"}
            // 独立append侧录不与finalizer争写events.json；跨段迟到事件仍按源时钟落回正确目录。
            val event=JSONObject().put("type",EventKind.SENTINEL.key).put("startMs",relative.coerceAtLeast(0))
                .put("endMs",relative.coerceAtLeast(0)).put("sequence",sequence).put("ruleVersion","VSTD:$sequence")
                .put("atMonotonicNanos",atNanos).put("beforeCapture",relative<0)
            java.io.FileOutputStream(File(dir,"sentinel-events.jsonl"),true).use{out->
                out.write((event.toString()+"\n").toByteArray());out.fd.sync()
            }
        }
    }
    fun initialize() {
        // 冷启动历史目录核对不与编码出包并行；完成后才启动codec，不以超时遗留writer线程。
        call(timeoutSeconds=120) { prepare(1); prepare(2); activeDir = segments.getValue(1).tx.segmentDirectory()
            listener.onSegmentStarted(1, activeDir!!) }
    }
    fun format(direction: String, value: MediaFormat) = enqueue { formats[direction] = value }
    fun sample(direction: String, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val size = info.size
        val used = queuedBytes.addAndGet(size.toLong())
        if (used > maxBytes) { queuedBytes.addAndGet(-size.toLong()); error("record_writer_bytes_exhausted") }
        highWaterBytes = maxOf(highWaterBytes, used)
        val copy = ByteArray(size)
        buffer.duplicate().apply { position(info.offset); limit(info.offset+size) }.get(copy)
        val pts = info.presentationTimeUs; val flags = info.flags
        try { executor.execute {
            try { if (!stopped) write(direction, copy, pts, flags) }
            catch (e: Exception) { stopped = true; fail(e) }
            finally { queuedBytes.addAndGet(-size.toLong()) }
        } } catch (e: RejectedExecutionException) { queuedBytes.addAndGet(-size.toLong()); throw IllegalStateException("record_writer_queue_exhausted", e) }
    }
    private fun enqueue(action: () -> Unit) {
        executor.execute { try { if (!stopped) action() } catch (e: Exception) { stopped=true; fail(e) } }
    }
    private fun call(timeoutSeconds:Long=30,action: () -> Unit) {
        val task = FutureTask<Unit> { action() }
        try { executor.execute(task) }
        catch (e: RejectedExecutionException) {
            // 停止时生产者已关闭；队列满也必须为收尾控制任务保留入队机会。
            check(!executor.isShutdown && executor.queue.offer(task, 25, TimeUnit.SECONDS)) { "record_writer_control_timeout" }
        }
        task.get(timeoutSeconds, TimeUnit.SECONDS)
    }
    fun finish(reason: String?) {
        try { call {
            try {
                stopped=true
                drainVehicle()
                segments.values.toList().forEach{handoff(it,reason,wait=true)}
                segments.clear()
            }finally{finalizer.shutdown()}
        } } finally {executor.shutdown()}
        check(finalizer.awaitTermination(30,TimeUnit.SECONDS)){"segment_finalize_timeout"}
        finalizationError.get()?.let{throw it}
    }
    /** 不再接包的旧段移交；Native muxer、tx、scriber移交后只由finalizer操作。 */
    private fun handoff(segment:Segment,reason:String?,wait:Boolean=false) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25)
        synchronized(finalizationProgress){
            while(finalizingIds.size>=2){
                check(wait&&System.nanoTime()<deadline){"segment_finalize_window_exhausted"}
                finalizationProgress.wait(100)
            }
            finalizingIds.add(segment.tx.segmentDirectory().name)
        }
        segment.tx.transferToFinalizer(finalizerOwner)
        finalizer.execute {
            val at=SystemClock.elapsedRealtime()
            try {finishSegment(segment,reason)}
            catch(e:Exception){finalizationError.compareAndSet(null,e);stopped=true;fail(e)}
            finally {
                segment.use.close()
                RecordDiagnostics.event("segment_finalize_ms=${SystemClock.elapsedRealtime()-at}_index=${segment.index}")
                finalizeStage="idle"
                synchronized(finalizationProgress){finalizingIds.remove(segment.tx.segmentDirectory().name);finalizationProgress.notifyAll()}
            }
        }
    }
    private fun prepare(index: Int): Segment {
        segments[index]?.let { return it }
        // 某路持续不跨关键帧边界时不得无界持有其他路的muxer/侧录文件。
        check(segments.size<8){"active_segment_window_exhausted"}
        val seconds = (clock.endUs(index)-clock.startUs(index))/1_000_000
        val estimate = codecs.values.sumOf { it.bitrate } * (seconds+settings.gopSeconds+2) / 8
        // 首次建立实际空间账本；之后逐包扣减，正常换段不重新遍历整个历史目录。
        // 空间预算不足才扫描/覆盖；每秒仍探测实际剩余空间，外部占用不会绕过下限。
        if (quotaAllowance < estimate) {
            writeStage="storage-scan:$index"
            val room = RotationJanitor.makeRoom(SegmentLayout.segmentsRoot(root), SegmentLayout.indexDir(root),
                settings.effectiveQuotaBytes(root.totalSpace), root.usableSpace, settings.minFreeBytes,
                estimate + 8L*1024*1024, settings.rotate, settings.eventProtect,inUse=finalizingIds.toSet(),catalog=if(catalogReady)catalog.values.toList()else null,
                usedBytesOverride=if(catalogReady)(settings.effectiveQuotaBytes(root.totalSpace)-quotaAllowance-8L*1024*1024).coerceAtLeast(0)else null)
            check(room.blockedReason == null) { room.blockedReason.orEmpty() }
            if (room.deleted.isNotEmpty()) {
                rotations += room.deleted.size
                EngineDiagnostics.lastRotationNote = "循环覆盖：累计删除 $rotations 段"
            }
            room.deleted.forEach{catalog.remove(it)}
            if(!catalogReady){room.catalog.forEach{catalog[it.id]=it};catalogReady=true}
            refreshAllowance(room.usedBytes,room.freedBytes)
        }
        writeStage="prepare:$index"
        return Segment(index).also { segments[index] = it }
    }
    /** 分段/删除后重建账本，包写入只扣减字节；不再每几MiB全目录扫描。8MiB预留封装/侧录开销。 */
    private fun refreshAllowance(used:Long,freed:Long) {
        if(catalogReady && bytesWritten>0){
            quotaAllowance=minOf(quotaAllowance+freed,root.usableSpace-settings.minFreeBytes-8L*1024*1024)
            return
        }
        quotaAllowance=minOf(settings.effectiveQuotaBytes(root.totalSpace)-used,root.usableSpace-settings.minFreeBytes)-8L*1024*1024
    }
    private fun write(direction: String, bytes: ByteArray, pts: Long, flags: Int) {
        val vehicleNow = SystemClock.elapsedRealtimeNanos()
        if (vehicleNow - lastVehicleProbeNs >= 50_000_000L) {
            drainVehicle(); lastVehicleProbeNs = vehicleNow
        }
        val previous = priorPts[direction]
        check(previous == null || pts > previous) { "non_monotonic_encoded_pts_$direction" }
        priorPts[direction] = pts
        var index = positions.getValue(direction)
        val key = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (pts >= clock.endUs(index) && key) {
            val target=2+((pts-clock.firstEndUs)/clock.periodUs).toInt()
            if(target>index+1)RecordDiagnostics.event("keyframe_late",IllegalStateException("keyframe_exceeded_segment"),
                mapOf("direction" to direction,"segmentIndex" to target))
            index=target
            positions[direction] = index
        }
        val segment = prepare(index)
        var track = segment.tracks[direction]
        if (track == null) {
            if (!key) return // 会话首次须从真实关键帧开始，不能把不可解码P帧当有效录像。
            writeStage="muxer-open:$index";writeDirection=direction
            val muxer = MediaMuxer(SegmentLayout.writingFile(SegmentLayout.videoFile(segment.tx.segmentDirectory(), direction)).path,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                writeStage="muxer-track:$index"
                val nativeTrack = muxer.addTrack(checkNotNull(formats[direction]))
                writeStage="muxer-start:$index";muxer.start()
                track = Track(muxer, nativeTrack); segment.tracks[direction] = track
            } catch (e: Exception) { muxer.release(); throw e }
        }
        val relative = pts-clock.startUs(index)
        check(relative >= 0) { "packet_before_segment_anchor" }
        val nowNs=SystemClock.elapsedRealtimeNanos()
        if(nowNs-lastFreeProbeNs>1_000_000_000L){
            quotaAllowance=minOf(quotaAllowance,root.usableSpace-settings.minFreeBytes-8L*1024*1024)
            lastFreeProbeNs=nowNs
        }
        if(quotaAllowance<bytes.size) {
            val room=RotationJanitor.makeRoom(SegmentLayout.segmentsRoot(root),SegmentLayout.indexDir(root),
                settings.effectiveQuotaBytes(root.totalSpace),root.usableSpace,settings.minFreeBytes,
                maxOf(16L*1024*1024,bytes.size.toLong()*2),settings.rotate,settings.eventProtect,inUse=finalizingIds.toSet(),catalog=if(catalogReady)catalog.values.toList()else null,
                usedBytesOverride=if(catalogReady)(settings.effectiveQuotaBytes(root.totalSpace)-quotaAllowance-8L*1024*1024).coerceAtLeast(0)else null)
            check(room.blockedReason==null){room.blockedReason.orEmpty()}
            rotations+=room.deleted.size
            room.deleted.forEach{catalog.remove(it)}
            if(!catalogReady){room.catalog.forEach{catalog[it.id]=it};catalogReady=true}
            refreshAllowance(room.usedBytes,room.freedBytes)
            check(quotaAllowance>=bytes.size){"quota_reserve_exhausted"}
        }
        quotaAllowance-=bytes.size
        val info = MediaCodec.BufferInfo().apply { set(0, bytes.size, relative, flags) }
        writeStage="sample";writeDirection=direction
        val sampleAt=SystemClock.elapsedRealtimeNanos()/1000
        track.muxer.writeSampleData(track.track, ByteBuffer.wrap(bytes), info)
        maxSampleWriteUs=maxOf(maxSampleWriteUs,SystemClock.elapsedRealtimeNanos()/1000-sampleAt)
        if (track.first < 0) track.first = relative
        track.last = relative; track.frames++; bytesWritten += bytes.size
        if (index > activeIndex) {
            activeIndex=index; activeDir=segment.tx.segmentDirectory()
            listener.onSegmentStarted(index, activeDir!!)
        }
        if (!segment.active) { segment.active=true; listener.onRecordingActive() }
        // 在同一写线程采样，侧录不跨线程写；按最慢方向归属段锚点，保持原始单调时间。
        val now = SystemClock.elapsedRealtimeNanos()
        if (updateSource != true && now/1000 >= clock.startUs(index) && now-segment.sampleAt >= settings.vehicleIntervalMs*1_000_000L) {
            segment.scriber.sample(now); segment.sampleAt=now
        }
        val safe = positions.values.minOrNull() ?: index
        segments.values.filter { it.index < safe }.toList().forEach { old ->
            handoff(old,null);segments.remove(old.index)
        }
        // 至少一个段的提前准备，普通换段不做codec/相机会话重建。
        if (positions.values.distinct().size == 1 && !segments.containsKey(safe+1)) prepare(safe+1)
    }
    private var updateSource: Boolean? = null
    private fun drainVehicle() {
        val updates = feed.drainRecordedUpdates()
        updateSource = updates != null
        updates?.forEach { update ->
            if (update.atNanos < clock.monoUs * 1000) return@forEach
            val index = if (update.atNanos / 1000 < clock.firstEndUs) 1
                else 2 + ((update.atNanos / 1000 - clock.firstEndUs) / clock.periodUs).toInt()
            prepare(index).scriber.sample(update.atNanos, update.snapshot)
        }
    }
    private fun finishSegment(segment: Segment, reason: String?) {
        if(segment.finished)return
        segment.finished=true
        finalizeStage="muxer:${segment.index}"
        val tx = segment.tx
        var failed = reason
        val rows = JSONArray()
        segment.tracks.forEach { (direction, track) ->
            val codec = codecs.getValue(direction)
            var stoppedOk = false
            try { track.muxer.stop(); stoppedOk=true }
            catch (e: Exception) { failed = failed ?: "muxer_stop_${e.javaClass.simpleName}" }
            finally {
                runCatching { track.muxer.release() }.onFailure { failed=failed?:"muxer_release_${it.javaClass.simpleName}" }
            }
            if (stoppedOk && track.frames > 0) {
                val result = ChannelWriteResult(tx.transactionId, direction, codec.info.mime, codec.info.name,
                    (if(codec.info.hardware && !codec.info.software) "hardware" else if(codec.info.software) "software" else "unknown"), codec.width, codec.height, codec.fps, codec.bitrate, codec.gop,
                    track.frames, track.first, track.last)
                finalizeStage="fsync:${segment.index}:$direction"
                val outcome = tx.commitChannel(result)
                if (outcome.failed) failed = failed ?: outcome.reason
            }
            rows.put(JSONObject().put("direction", direction).put("frames", track.frames)
                .put("firstSourcePtsUs", clock.startUs(segment.index)+track.first)
                .put("lastSourcePtsUs", clock.startUs(segment.index)+track.last))
        }
        finalizeStage="sidecar:${segment.index}"
        val vehicle = segment.scriber.close()
        val marks = segment.detector.finish((clock.endUs(segment.index)-clock.startUs(segment.index))/1000)
        val events = if (marks.isEmpty()) null else {
            val payload = JSONObject().put("ruleVersion", segment.detector.rules.ruleVersion).put("events", JSONArray().apply {
                marks.forEach { put(JSONObject().put("type",it.kind.key).put("startMs",it.startMs).put("endMs",it.endMs)
                    .put("peak",it.peak).put("ruleVersion",it.ruleVersion)) }
            })
            check(AtomicFiles.writeAtomically(File(tx.segmentDirectory(), SegmentLayout.FILE_EVENTS), payload.toString().toByteArray()))
            EventsRecord(segment.detector.rules.ruleVersion, marks.size, SegmentLayout.FILE_EVENTS)
        }
        tx.attachSidecar(vehicle, events)
        val result = if (failed == null) tx.finish() else tx.abort(failed!!)
        SegmentScanner.readSegment(tx.segmentDirectory())?.let{catalog[it.id]=it}
        AtomicFiles.writeAtomically(File(tx.segmentDirectory(), "continuity.json"), JSONObject()
            .put("plannedStartUs",clock.startUs(segment.index)).put("plannedEndUs",clock.endUs(segment.index))
            .put("plannedStartWallMs",clock.wall(segment.index)).put("channels",rows).toString().toByteArray())
        // 提前准备但没有写入的段如实failed，不发虚假的有效段完成通知。
        if (segment.tracks.isNotEmpty()) {
            val first=tx.channelOutcome(codecs.keys.first())?.channel
            EngineDiagnostics.lastSegment=EngineDiagnostics.LastSegment(segment.index,result.state,
                first?.encoderName?:"—",first?.implementation?:"—",first?.width?:0,first?.height?:0,
                first?.fps?:0,first?.bitrateBps?:0,first?.gopSeconds?:0,
                segment.tracks.values.sumOf{it.frames},segment.tracks.values.maxOfOrNull{it.last/1000}?:0,
                result.completedDirections,result.failedDirections.associateWith{failed?:"通道未提交"},System.currentTimeMillis())
            // 录像库由 RecordThumbnail 按需生成真实四向缩略图；录制中不启动重复的单路解码。
            listener.onSegmentFinished(segment.index, result)
        }
        if (failed != null) error("segment_commit_failed:$failed")
    }
}
