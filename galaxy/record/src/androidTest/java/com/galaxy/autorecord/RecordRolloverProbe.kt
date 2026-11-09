package com.galaxy.autorecord

import android.app.Instrumentation
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.os.Bundle
import com.galaxy.autorecord.core.*
import com.galaxy.autorecord.recording.RecordingEngine
import com.galaxy.autorecord.storage.FinishOutcome
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 仅测试APK：隔离模拟器单路Camera2→YUV→编码→十次实际换段、停止与重启取消，不连接车辆服务。 */
internal object RecordRolloverProbe {
    fun run(instrumentation: Instrumentation, codecName: String, report: Bundle) {
        val context = instrumentation.targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull() ?: error("test_camera_missing")
        val formats = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        check(formats.getOutputSizes(ImageFormat.YUV_420_888).any { it.width == 1280 && it.height == 720 })
        check(formats.getOutputSizes(ImageFormat.PRIVATE).any { it.width == 1280 && it.height == 720 })
        val camera = SurfaceCameraSource(context, object : RecordCameraAdapter {
            override val label = "隔离模拟器合成场景"
            override fun routes() = mapOf("front" to RecordCameraRoute(id, 1280, 720))
        }, {})
        val root = File(context.cacheDir, "rollover-probe-${System.nanoTime()}").apply { mkdirs() }
        val volumes = object : StorageVolumes {
            override fun list() = listOf(VolumeInfo(null, "隔离测试存储", false, true, root, root.usableSpace, root.totalSpace, true))
            override fun resolve(uuid: String?) = list().single().takeIf { uuid == null }
            override fun probeWritable(dir: File) = dir.isDirectory || dir.mkdirs()
        }
        val settings = RecordSettings(segmentSeconds=4, requestedHeight=720, fps=15,
            encoderImplementation=RecordSettings.IMPL_SOFTWARE, minFreeBytes=1024 * 1024)
        val codec = CodecCapabilities.encoders(RecordSettings.CODEC_H264).first { it.name == codecName }
        val engine = RecordingEngine(volumes, OfflineVehicleFeed("测试值：未连接车辆"), camera,
            { settings }, "isolated-rollover", encoderPicker={ codec })
        val completed = Collections.synchronizedList(mutableListOf<FinishOutcome>())
        val ten = CountDownLatch(10)
        val stopped = CountDownLatch(1)
        val nextActive = CountDownLatch(1)
        val segmentIndex = java.util.concurrent.atomic.AtomicInteger()
        val failure = AtomicReference<String?>()
        try {
            camera.start(listOf("front"))
            engine.start(listOf("front"), settings, object : RecordingEngineListener {
                override fun onSegmentStarted(index: Int, segmentDir: File) { segmentIndex.set(index) }
                override fun onRecordingActive() { if (segmentIndex.get() >= 11) nextActive.countDown() }
                override fun onProgress(bytesWritten: Long, segmentElapsedMs: Long, segmentIndex: Int) {}
                override fun onSegmentFinished(index: Int, outcome: FinishOutcome) {
                    // 单路测试按产品四路清单应为 partial；只认实选 front 成功，不能伪造 complete。
                    check(outcome.completedDirections == listOf("front")) { "selected_channel_missing:${outcome.state}:${outcome.error}" }
                    check(outcome.state == "partial") { "single_channel_must_remain_partial" }
                    completed.add(outcome); ten.countDown()
                }
                override fun onStopped(error: String?) { failure.set(error); stopped.countDown() }
            })
            check(ten.await(120, TimeUnit.SECONDS)) { "ten_rollovers_missing:${failure.get()}" }
            check(nextActive.await(15, TimeUnit.SECONDS)) { "post_rollover_frame_missing" }
            engine.requestStop()
            check(stopped.await(15, TimeUnit.SECONDS)) { "engine_stop_timeout" }
            check(failure.get() == null) { "engine_failed:${failure.get()}" }
            val outcomes = synchronized(completed) { completed.toList() }
            check(outcomes.size >= 10)
            outcomes.forEach { outcome ->
                val video = SegmentLayout.videoFile(outcome.segmentDir, "front")
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(video.path)
                    check(extractor.trackCount == 1)
                    extractor.selectTrack(0)
                    var samples = 0
                    var previous = -1L
                    while (extractor.sampleTime >= 0) {
                        check(extractor.sampleTime >= previous) { "non_monotonic_pts" }
                        previous = extractor.sampleTime; samples++; extractor.advance()
                    }
                    check(samples > 0) { "empty_committed_segment" }
                } finally { extractor.release() }
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(video.path)
                    val bitmap = retriever.getFrameAtTime(0) ?: error("committed_video_decode_failed")
                    check(bitmap.width == 1280 && bitmap.height == 720); bitmap.recycle()
                } finally { retriever.release() }
            }
            check(root.walkTopDown().none { it.name.endsWith(".writing") }) { "unexplained_writing_after_stop" }
            // 同一引擎再次开始后立即退出：覆盖建段尚未出帧的合法取消与 release 幂等。
            val restarted = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            engine.start(listOf("front"), settings, object : RecordingEngineListener {
                override fun onSegmentStarted(index: Int, segmentDir: File) { restarted.countDown() }
                override fun onRecordingActive() {}
                override fun onProgress(bytesWritten: Long, segmentElapsedMs: Long, segmentIndex: Int) {}
                override fun onSegmentFinished(index: Int, outcome: FinishOutcome) {}
                override fun onStopped(error: String?) { cancelled.countDown() }
            })
            check(restarted.await(10, TimeUnit.SECONDS))
            engine.release(); engine.release()
            check(cancelled.await(15, TimeUnit.SECONDS)) { "release_cancel_timeout" }
            report.putString("rollover", "PASS: ${outcomes.size} Camera2/YUV/MP4 segments; ordered PTS; decoded 1280x720; stop without writing residue; restart/release cancellation")
            report.putString("artifacts", root.path)
        } finally {
            engine.requestStop()
            stopped.await(15, TimeUnit.SECONDS)
            engine.release()
            camera.release()
            // 保留本次合成样本、清单及合法取消的 partial，供脚本只读回收核验。
        }
    }
}
