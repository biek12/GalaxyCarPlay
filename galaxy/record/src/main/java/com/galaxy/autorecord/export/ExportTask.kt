package com.galaxy.autorecord.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.galaxy.autorecord.core.SegmentLayout
import com.galaxy.autorecord.core.SegmentManifest
import com.galaxy.autorecord.core.VehicleHistory
import com.galaxy.autorecord.core.VehicleSample
import java.io.File
import java.io.FileOutputStream

/**
 * 导出执行（B5）：只读源段，输出到用户选定位置（SAF）。
 *
 * 两条通路：
 * - **单路无叠加**：MediaExtractor→MediaMuxer 直搬样本（无损、快），跨段 PTS 按
 *   [ExportPlanner] 平移为连续时间轴；源文件不被修改。
 * - **四宫格 / 叠加数据条**：四路（或单路）解码 → 合成（2×2 或全幅 + 历史数据条）→
 *   重新编码（H.264）→ 封装。合成时间轴同样按 [ExportPlanner] 重排；路间首帧偏移保留。
 *
 * 纪律（页面规格 §14.4）：不改源段/不删源段/不移动源段；取消或失败清理临时状态；
 * 空间不足/目标不可写/编码参数跨段不一致 → 明确报错，不静默降级为单路。
 */
class ExportTask(
    private val context: Context,
    private val request: ExportRequest,
    private val onProgress: (String) -> Unit = {},
) : Runnable {

    data class ExportRequest(
        val segmentDirs: List<File>,
        /** true=四宫格；false=单路（direction 有效）。 */
        val grid: Boolean,
        val direction: String,
        val overlay: Boolean,
        val outputUri: Uri,
    )

    data class ExportResult(
        val success: Boolean,
        val message: String,
        /** export.json 映射表（同时尽力写到输出旁与应用导出记录目录）。 */
        val exportJson: ByteArray? = null,
    )

    @Volatile private var cancelled = false
    private val doneSignal = java.util.concurrent.LinkedBlockingQueue<ExportResult>(1)

    fun cancel() {
        cancelled = true
    }

    override fun run() {
        val result = try {
            execute()
        } catch (error: Exception) {
            Log.w(TAG, "导出失败", error)
            ExportResult(false, if (cancelled) "已取消导出（源段保持不动）"
                else "导出失败：${error.javaClass.simpleName}（源段保持不动）")
        }
        doneSignal.offer(result)
    }

    /** 同步执行入口（测试用）；生产用线程跑 [run] 后取 [await]。 */
    fun execute(): ExportResult {
        val leases = mutableListOf<java.io.Closeable>()
        // 同步调用者也必须收到失败回执；异常不能绕过半成品清理或逃出工作线程。
        val result = try {
            request.segmentDirs.forEach { leases += com.galaxy.autorecord.core.SegmentAccess.hold(it) }
            executeExport()
        } catch (error: Exception) {
            Log.w(TAG, "导出失败", error)
            ExportResult(false, if (cancelled) "已取消导出（源段保持不动）"
                else "导出失败：${error.javaClass.simpleName}（源段保持不动）")
        } finally { leases.asReversed().forEach { it.close() } }
        if (!result.success) {
            runCatching {
                android.provider.DocumentsContract.deleteDocument(context.contentResolver, request.outputUri)
            }
        }
        return result
    }

    private fun executeExport(): ExportResult {
        val dirs = request.segmentDirs
        if (dirs.isEmpty()) return ExportResult(false, "没有可导出的片段")
        if (cancelled) return ExportResult(false, "已取消导出（源段保持不动）")
        if (dirs.any { !it.isDirectory }) return ExportResult(false, "源片段已不可用，请刷新录像库")
        if (!request.grid && request.direction !in SegmentLayout.DIRECTIONS) {
            return ExportResult(false, "无效的导出方向")
        }
        val directions = if (request.grid) SegmentLayout.DIRECTIONS else listOf(request.direction)
        val manifests = dirs.map { ExportPlanner.readManifest(it) }
        if (manifests.any { it == null || it.state == SegmentManifest.STATE_WRITING }) {
            return ExportResult(false, "源片段清单损坏或仍在写入，无法导出")
        }
        val plan = ExportPlanner.plan(manifests, directions)
        if (plan.spans.any { it.durationUs <= 0 }) {
            return ExportResult(false, "所选片段没有可导出的视频样本")
        }
        val json = ExportPlanner.exportJson(plan, directions, request.grid, request.overlay)

        val pfd = openOutput() ?: return ExportResult(false, "目标不可写：无法创建导出文件", json)
        val result = try {
            if (!request.grid && !request.overlay) {
                remux(dirs, plan, directions.first(), pfd.fileDescriptor)
            } else {
                compose(dirs, manifests, plan, directions, pfd.fileDescriptor)
            }
        } finally {
            runCatching { pfd.close() }
        }
        if (result.success) {
            writeExportJson(json)
        }
        return result.copy(exportJson = json)
    }

    fun await(): ExportResult = doneSignal.take()

    // ---- 单路无损重排 ----

    private fun remux(
        dirs: List<File>,
        plan: ExportPlanner.Plan,
        direction: String,
        fd: java.io.FileDescriptor,
    ): ExportResult {
        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var wrote = 0L
            var lastFormat: MediaFormat? = null
            dirs.forEachIndexed { index, dir ->
                if (cancelled) return ExportResult(false, "已取消导出（源段保持不动）")
                val file = SegmentLayout.videoFile(dir, direction)
                if (!file.isFile || file.length() == 0L) {
                    return ExportResult(false, "源段缺 ${direction} 路，无法无损合并（不静默降级）")
                }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val track = (0 until extractor.trackCount).firstOrNull { i ->
                        extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                    } ?: return ExportResult(false, "源段没有视频轨：${dir.name}")
                    extractor.selectTrack(track)
                    val format = extractor.getTrackFormat(track)
                    if (trackIndex < 0) {
                        trackIndex = muxer.addTrack(format)
                        muxer.start()
                    } else if (!sameCodec(format, lastFormat)) {
                        return ExportResult(false, "相邻段编码参数不同（分辨率/编码变化），无法无损合并，请按段分别导出")
                    }
                    var buffer = java.nio.ByteBuffer.allocate(1 shl 20)
                    val info = MediaCodec.BufferInfo()
                    while (!cancelled) {
                        val sampleSize = extractor.sampleSize
                        if (sampleSize < 0) break
                        check(sampleSize <= 32L * 1024 * 1024) { "video_sample_too_large" }
                        if (sampleSize > buffer.capacity()) buffer = java.nio.ByteBuffer.allocate(sampleSize.toInt())
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val pts = plan.toExportPts(index, extractor.sampleTime)
                        info.set(0, size, pts, extractor.sampleFlags)
                        buffer.position(0)
                        buffer.limit(size)
                        muxer.writeSampleData(trackIndex, buffer, info)
                        wrote++
                        extractor.advance()
                    }
                    lastFormat = format
                } finally {
                    extractor.release()
                }
                onProgress("已合并 ${index + 1}/${dirs.size} 段")
            }
            if (cancelled) return ExportResult(false, "已取消导出（源段保持不动）")
            check(wrote > 0) { "empty_export" }
            muxer.stop()
            return ExportResult(true, "导出完成：单路 $direction，${dirs.size} 段合并，$wrote 个样本（PTS 已重排）")
        } finally {
            runCatching { muxer?.release() }
        }
    }

    internal fun sameCodec(a: MediaFormat, b: MediaFormat?): Boolean {
        if (b == null) return true
        return a.getString(MediaFormat.KEY_MIME) == b.getString(MediaFormat.KEY_MIME) &&
            a.getInteger(MediaFormat.KEY_WIDTH) == b.getInteger(MediaFormat.KEY_WIDTH) &&
            a.getInteger(MediaFormat.KEY_HEIGHT) == b.getInteger(MediaFormat.KEY_HEIGHT) &&
            (0..2).all { index ->
                val first = a.getByteBuffer("csd-$index")?.duplicate()?.apply { rewind() }
                val second = b.getByteBuffer("csd-$index")?.duplicate()?.apply { rewind() }
                first == second
            }
    }

    // ---- 合成（四宫格 / 叠加数据条）----

    private fun compose(
        dirs: List<File>,
        manifests: List<SegmentManifest?>,
        plan: ExportPlanner.Plan,
        directions: List<String>,
        fd: java.io.FileDescriptor,
    ): ExportResult {
        val outWidth = 1280
        val outHeight = 800
        val fps = 25
        val frameUs = 1_000_000L / fps

        var muxer: MediaMuxer? = null
        var encoder: MediaCodec? = null
        var composed: Bitmap? = null
        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, (BPP * outWidth * outHeight * fps).toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val pump = EncoderPump(encoder, muxer) { cancelled }

            val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            composed = bitmap
            val canvas = Canvas(bitmap)

            dirs.forEachIndexed { segmentIndex, dir ->
                if (cancelled) return ExportResult(false, "已取消导出（源段保持不动）")
                val manifest = manifests[segmentIndex]
                val span = plan.spans[segmentIndex]
                val samples = runCatching { VehicleHistory.read(dir) }.getOrDefault(emptyList())
                // 逐个创建并立即登记所有权，后一路初始化失败也会释放前面已开的解码器。
                val readers = mutableListOf<FrameReader?>()
                try {
                    directions.forEach { direction ->
                        val file = SegmentLayout.videoFile(dir, direction)
                        val complete = manifest?.channels?.any {
                            it.direction == direction && it.status == SegmentManifest.CHANNEL_COMPLETE
                        } == true
                        if (complete) check(file.isFile && file.length() > 0) { "source_video_missing" }
                        readers += if (complete) FrameReader(file) else null
                    }
                    var tUs = 0L
                    while (tUs <= span.durationUs) {
                        if (cancelled) return ExportResult(false, "已取消导出（源段保持不动）")
                        canvas.drawColor(Color.rgb(16, 18, 22))
                        readers.forEachIndexed { i, reader ->
                            val bmp = reader?.frameAt(tUs)
                            drawCell(canvas, directions[i], bmp, request.grid)
                        }
                        if (request.overlay) {
                            drawDataBar(canvas, samples, tUs / 1000, outWidth, outHeight)
                        }
                        val outPts = plan.toExportPts(segmentIndex, tUs)
                        pump.feed(bitmap, outWidth, outHeight, outPts)
                        tUs += frameUs
                    }
                    onProgress("已合成 ${segmentIndex + 1}/${dirs.size} 段")
                } finally {
                    readers.forEach { it?.close() }
                }
            }

            // 收尾：EOS → drain → muxer.stop（顺序不可交换）
            pump.finish()
            return ExportResult(true, "导出完成：${if (request.grid) "四宫格" else "单路叠加"}，${dirs.size} 段，PTS 已重排")
        } finally {
            composed?.recycle()
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.release() }
        }
    }

    /** 缓冲区输入泵：不丢输入帧，EOS 排出后才结束封装；所有等待均可取消且有界。 */
    private class EncoderPump(
        private val encoder: MediaCodec,
        private val muxer: MediaMuxer,
        private val cancelled: () -> Boolean,
    ) {
        private val info = MediaCodec.BufferInfo()
        private var started = false
        private var trackIndex = -1
        private var samples = 0L
        private var outputEnded = false

        private fun checkWaiting(deadline: Long) {
            check(!cancelled()) { "export_cancelled" }
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "export_codec_timeout" }
        }

        private fun acquireInput(): Int {
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (true) {
                checkWaiting(deadline)
                val index = encoder.dequeueInputBuffer(10_000)
                if (index >= 0) return index
                drain(0)
            }
        }

        fun feed(bitmap: Bitmap, width: Int, height: Int, ptsUs: Long) {
            val index = acquireInput()
            val image = checkNotNull(encoder.getInputImage(index)) { "encoder_yuv_image_missing" }
            rgbToYuv(bitmap, width, height, image)
            encoder.queueInputBuffer(index, 0, width * height * 3 / 2, ptsUs, 0)
            drain(0)
        }

        fun finish() {
            // 本编码器接收 ByteBuffer/Image，Surface 专用 signalEndOfInputStream 会直接抛异常。
            val index = acquireInput()
            encoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (!outputEnded) {
                checkWaiting(deadline)
                drain(10_000)
            }
            check(started && samples > 0) { "empty_export" }
            muxer.stop()
        }

        private fun drain(timeoutUs: Long) {
            while (!outputEnded) {
                when (val index = encoder.dequeueOutputBuffer(info, timeoutUs)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!started) { "encoder_format_changed_twice" }
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        started = true
                    }
                    else -> if (index >= 0) {
                        try {
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!config && info.size > 0) {
                                check(started) { "encoder_format_missing" }
                                val buffer = checkNotNull(encoder.getOutputBuffer(index))
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, buffer, info)
                                samples++
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            encoder.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
        }
    }

    /** 合成单元格：四宫格=前(左上)/后(右上)/左(左下)/右(右下)；单路=全幅。bmp 为 null（缺路）时显式占位，不用邻路顶替。 */
    private fun drawCell(canvas: Canvas, direction: String, bmp: Bitmap?, grid: Boolean) {
        val (left, top, w, h) = if (grid) {
            val col = when (direction) {
                "front", "left" -> 0
                else -> 1
            }
            val row = when (direction) {
                "front", "rear" -> 0
                else -> 1
            }
            val cellW = canvas.width / 2
            val cellH = canvas.height / 2
            floatArrayOf((col * cellW).toFloat(), (row * cellH).toFloat(), cellW.toFloat(), cellH.toFloat())
        } else {
            floatArrayOf(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
        }
        val rect = android.graphics.RectF(left, top, left + w, top + h)
        if (bmp == null) {
            val paint = Paint().apply { color = Color.rgb(32, 34, 40) }
            canvas.drawRect(rect, paint)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(160, 164, 172)
                textSize = (h / 12).coerceAtLeast(18f)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("缺路（$direction）", rect.centerX(), rect.centerY(), text)
        } else {
            val src = android.graphics.Rect(0, 0, bmp.width, bmp.height)
            canvas.drawBitmap(bmp, src, rect, null)
        }
        // 方位标签
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (h / 14).coerceAtLeast(16f)
        }
        val scrim = Paint().apply { color = Color.argb(120, 0, 0, 0) }
        val labelH = label.textSize * 1.8f
        canvas.drawRect(left, top, left + label.textSize * 5, top + labelH, scrim)
        canvas.drawText(direction, left + label.textSize * 0.5f, top + labelH * 0.72f, label)
    }

    /** 历史数据条（可选）：只用录像当时样本，缺失显示"—"，不补零。 */
    private fun drawDataBar(canvas: Canvas, samples: List<VehicleSample>, timeMs: Long, width: Int, height: Int) {
        val sample = VehicleHistory.sampleAt(samples, timeMs, 2000L)
        val barH = (height / 9).toFloat()
        val scrim = Paint().apply { color = Color.argb(170, 10, 12, 16) }
        canvas.drawRect(0f, height - barH, width.toFloat(), height.toFloat(), scrim)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = barH * 0.42f
        }
        val speed = sample?.values?.get("displaySpeed") ?: "—"
        val gear = sample?.values?.get("gear") ?: "—"
        val line = "车速 $speed km/h · 挡位 $gear · ${timeMs / 1000}s（历史样本）"
        canvas.drawText(line, text.textSize, height - barH / 2 + text.textSize / 2, text)
    }

    // ---- 输出与映射表 ----

    private fun openOutput(): ParcelFileDescriptor? =
        runCatching { context.contentResolver.openFileDescriptor(request.outputUri, "rwt") }.getOrNull()

    private fun writeExportJson(json: ByteArray) {
        val name = runCatching {
            context.contentResolver.query(request.outputUri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: "export"
        val base = name.removeSuffix(".mp4").replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(80).ifBlank { "export" }
        // 映射记录是导出结果的一部分；失败不能声称已有留档。
        val dir = File(context.filesDir, "exports")
        check(dir.isDirectory || dir.mkdirs()) { "export_record_directory_failed" }
        check(com.galaxy.autorecord.core.AtomicFiles.writeAtomically(
            File(dir, "$base.export.json"), json, true,
        )) { "export_record_write_failed" }
        // 尽力写到输出同目录（失败不阻塞导出结果，映射表仍有应用内留档）
        runCatching {
            val docId = android.provider.DocumentsContract.getDocumentId(request.outputUri)
            val treeId = android.provider.DocumentsContract.getTreeDocumentId(request.outputUri)
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                request.outputUri,
                if (docId != null && docId.contains(":")) docId.substringBeforeLast(":") + ":" + docId.substringAfterLast(":").substringBeforeLast("/") else treeId,
            )
            val created = android.provider.DocumentsContract.createDocument(
                context.contentResolver, parent, "application/json", "$base.export.json",
            )
            created?.let { uri ->
                context.contentResolver.openFileDescriptor(uri, "w")?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { it.write(json) }
                }
            }
        }.onFailure { Log.i(TAG, "export.json 旁路写入未成功，已保留应用内导出记录") }
    }

    /** 每路最多保留当前与下一张帧；目标时间之前保持缺路占位，绝不提前显示未来帧。 */
    private class FrameReader(file: File) {
        private val extractor = MediaExtractor()
        private var decoder: MediaCodec? = null
        private val codec: MediaCodec get() = checkNotNull(decoder)
        private val info = MediaCodec.BufferInfo()
        private var inputDone = false
        private var outputDone = false
        private val frames = TimedFrames<Bitmap> { it.recycle() }

        init {
            try {
                extractor.setDataSource(file.absolutePath)
                val trackIndex = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)
                // 显式请求可读取的 YUV 输出；没有 Image 时明确失败，不导出伪造的缺路画面。
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (error: Exception) {
                close()
                throw error
            }
        }

        fun frameAt(targetUs: Long): Bitmap? {
            frames.advance(targetUs)
            if (frames.hasFuture || outputDone) return frames.current
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (!outputDone) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "export_decoder_timeout" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(index))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 5_000)
                if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val image = checkNotNull(codec.getOutputImage(index)) { "decoder_yuv_image_missing" }
                            val bitmap = Bitmap.createBitmap(yuvToArgb(image), image.cropRect.width(), image.cropRect.height(), Bitmap.Config.ARGB_8888)
                            frames.offer(info.presentationTimeUs, bitmap, targetUs)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    if (frames.hasFuture) break
                }
            }
            return frames.current
        }

        fun close() {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            decoder = null
            runCatching { extractor.release() }
            frames.close()
        }
    }

    companion object {
        private const val TAG = "AutoRecordExport"
        private const val BPP = 0.08

        /** YUV_420_888 → ARGB（全幅）。 */
        fun yuvToArgb(image: Image): IntArray {
            val crop = image.cropRect
            val w = crop.width()
            val h = crop.height()
            val planes = image.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer
            val yRowStride = planes[0].rowStride
            val yPixelStride = planes[0].pixelStride
            val uRowStride = planes[1].rowStride
            val uPixelStride = planes[1].pixelStride
            val vRowStride = planes[2].rowStride
            val vPixelStride = planes[2].pixelStride
            val out = IntArray(w * h)
            for (row in 0 until h) {
                for (col in 0 until w) {
                    val yIndex = yBuffer.position() + (row + crop.top) * yRowStride + (col + crop.left) * yPixelStride
                    val y = (yBuffer.get(yIndex).toInt() and 0xFF) - 16
                    val uvRow = (row + crop.top) shr 1
                    val uvCol = (col + crop.left) shr 1
                    val u = (uBuffer.get(uBuffer.position() + uvRow * uRowStride + uvCol * uPixelStride).toInt() and 0xFF) - 128
                    val v = (vBuffer.get(vBuffer.position() + uvRow * vRowStride + uvCol * vPixelStride).toInt() and 0xFF) - 128
                    val yy = if (y < 0) 0 else y
                    val r = (298 * yy + 409 * v + 128) shr 8
                    val g = (298 * yy - 100 * u - 208 * v + 128) shr 8
                    val b = (298 * yy + 516 * u + 128) shr 8
                    out[row * w + col] = Color.rgb(
                        r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255),
                    )
                }
            }
            return out
        }

        /** ARGB → YUV_420_888（BT.601，2×2 子采样取左上）。 */
        fun rgbToYuv(bitmap: Bitmap, width: Int, height: Int, image: Image) {
            val argb = IntArray(width * height)
            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
            val planes = image.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer
            val yRowStride = planes[0].rowStride
            val yPixelStride = planes[0].pixelStride
            val uRowStride = planes[1].rowStride
            val uPixelStride = planes[1].pixelStride
            val vRowStride = planes[2].rowStride
            val vPixelStride = planes[2].pixelStride
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val c = argb[row * width + col]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    yBuffer.put(yBuffer.position() + row * yRowStride + col * yPixelStride, y.toByte())
                    if (row % 2 == 0 && col % 2 == 0) {
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        uBuffer.put(uBuffer.position() + (row shr 1) * uRowStride + (col shr 1) * uPixelStride, u.toByte())
                        vBuffer.put(vBuffer.position() + (row shr 1) * vRowStride + (col shr 1) * vPixelStride, v.toByte())
                    }
                }
            }
        }
    }
}
