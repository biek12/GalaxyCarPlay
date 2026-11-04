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
            ExportResult(false, "导出失败：${error.javaClass.simpleName}（源段保持不动）")
        }
        doneSignal.offer(result)
    }

    /** 同步执行入口（测试用）；生产用线程跑 [run] 后取 [await]。 */
    fun execute(): ExportResult {
        val dirs = request.segmentDirs.filter { it.isDirectory }
        if (dirs.isEmpty()) return ExportResult(false, "没有可导出的片段")
        val directions = if (request.grid) SegmentLayout.DIRECTIONS else listOf(request.direction)
        val manifests = dirs.map { ExportPlanner.readManifest(it) }
        val plan = ExportPlanner.plan(manifests, directions)
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
        } else {
            // 取消/失败：清理半截输出（源段不动），保留导出设置由界面维持
            runCatching {
                android.provider.DocumentsContract.deleteDocument(context.contentResolver, request.outputUri)
            }
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
                    val buffer = java.nio.ByteBuffer.allocate(1 shl 20)
                    val info = MediaCodec.BufferInfo()
                    while (!cancelled) {
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
            muxer.stop()
            return ExportResult(true, "导出完成：单路 $direction，${dirs.size} 段合并，$wrote 个样本（PTS 已重排）")
        } finally {
            runCatching { muxer?.release() }
        }
    }

    private var lastFormat: MediaFormat? = null

    private fun sameCodec(a: MediaFormat, b: MediaFormat?): Boolean {
        if (b == null) return true
        return a.getString(MediaFormat.KEY_MIME) == b.getString(MediaFormat.KEY_MIME) &&
            a.getInteger(MediaFormat.KEY_WIDTH) == b.getInteger(MediaFormat.KEY_WIDTH) &&
            a.getInteger(MediaFormat.KEY_HEIGHT) == b.getInteger(MediaFormat.KEY_HEIGHT)
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
            val pump = EncoderPump(encoder, muxer)

            val composed = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(composed)

            dirs.forEachIndexed { segmentIndex, dir ->
                if (cancelled) return ExportResult(false, "已取消导出（源段保持不动）")
                val manifest = manifests[segmentIndex]
                val span = plan.spans[segmentIndex]
                val samples = runCatching { VehicleHistory.read(dir) }.getOrDefault(emptyList())
                val readers = directions.map { d ->
                    val file = SegmentLayout.videoFile(dir, d)
                    if (file.isFile && file.length() > 0 &&
                        manifest?.channels?.any { it.direction == d && it.status == SegmentManifest.CHANNEL_COMPLETE } == true
                    ) {
                        FrameReader(file)
                    } else null
                }
                try {
                    var tUs = 0L
                    while (tUs <= span.durationUs + frameUs / 2) {
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
                        pump.feed(composed, outWidth, outHeight, outPts)
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
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.release() }
        }
    }

    /**
     * 编码输出泵：喂帧 + 排出输出；muxer 在收到 INFO_OUTPUT_FORMAT_CHANGED 后启动，
     * 启动前的输出帧先缓存（通常 0–1 帧），保证不丢帧。
     */
    private class EncoderPump(private val encoder: MediaCodec, private val muxer: MediaMuxer) {
        private val info = MediaCodec.BufferInfo()
        private var started = false
        private var trackIndex = -1
        private val pending = ArrayDeque<Pair<java.nio.ByteBuffer, MediaCodec.BufferInfo>>()

        fun feed(bitmap: Bitmap, width: Int, height: Int, ptsUs: Long) {
            val inIndex = encoder.dequeueInputBuffer(100_000)
            if (inIndex >= 0) {
                val image = encoder.getInputImage(inIndex)
                if (image != null) {
                    rgbToYuv(bitmap, width, height, image)
                    encoder.queueInputBuffer(inIndex, 0, width * height * 3 / 2, ptsUs, 0)
                } else {
                    encoder.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
                }
            }
            drain(0L)
        }

        fun finish() {
            encoder.signalEndOfInputStream()
            var eos = false
            while (!eos) {
                val outIndex = encoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> ensureStarted()
                    outIndex >= 0 -> {
                        val eosFlag = writeAndRelease(outIndex)
                        if (eosFlag) eos = true
                    }
                }
            }
            muxer.stop()
        }

        private fun drain(timeoutUs: Long) {
            while (true) {
                val outIndex = encoder.dequeueOutputBuffer(info, timeoutUs)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> ensureStarted()
                    outIndex >= 0 -> writeAndRelease(outIndex)
                }
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) return
            }
        }

        private fun ensureStarted() {
            if (started) return
            trackIndex = muxer.addTrack(encoder.outputFormat)
            muxer.start()
            started = true
            while (pending.isNotEmpty()) {
                val (buffer, pendingInfo) = pending.removeFirst()
                muxer.writeSampleData(trackIndex, buffer, pendingInfo)
            }
        }

        private fun writeAndRelease(outIndex: Int): Boolean {
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val buffer = encoder.getOutputBuffer(outIndex)
            try {
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val copy = java.nio.ByteBuffer.allocate(info.size)
                    copy.put(buffer)
                    copy.flip()
                    val copiedInfo = MediaCodec.BufferInfo().apply {
                        set(0, info.size, info.presentationTimeUs, info.flags)
                    }
                    if (started) {
                        muxer.writeSampleData(trackIndex, copy, copiedInfo)
                    } else {
                        pending.addLast(copy to copiedInfo)
                    }
                }
            } finally {
                encoder.releaseOutputBuffer(outIndex, false)
            }
            return eos
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
        runCatching { context.contentResolver.openFileDescriptor(request.outputUri, "w") }.getOrNull()

    private fun writeExportJson(json: ByteArray) {
        val name = runCatching {
            context.contentResolver.query(request.outputUri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: "export"
        val base = name.removeSuffix(".mp4")
        // 应用内导出记录（审计留档，必写）
        runCatching {
            val dir = File(context.filesDir, "exports").apply { mkdirs() }
            File(dir, "$base.export.json").writeBytes(json)
        }
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

    /** 单帧读取器：解码到内存帧，按目标时间返回"最近的已解码帧"（不补帧、不拉伸时间）。 */
    private class FrameReader(file: File) {
        private val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
        private val trackIndex = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        private val format = extractor.getTrackFormat(trackIndex)
        private val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
            configure(format, null, null, 0)
            start()
        }
        private val info = MediaCodec.BufferInfo()
        private var inputDone = false
        private var current: Bitmap? = null
        private var currentPtsUs = Long.MIN_VALUE
        private var nextPtsUs = Long.MAX_VALUE
        private var nextFrame: Bitmap? = null

        init {
            extractor.selectTrack(trackIndex)
        }

        /** 返回 pts ≤ targetUs 的最近帧；未解出更晚帧前保持推进。 */
        fun frameAt(targetUs: Long): Bitmap? {
            advanceUntil(targetUs)
            return current
        }

        private fun advanceUntil(targetUs: Long) {
            // 已有下一帧且仍晚于目标：current 即答案
            if (nextPtsUs != Long.MAX_VALUE && nextPtsUs > targetUs && current != null) return
            var guard = 0
            while (guard++ < 400) {
                // 喂输入
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        if (buffer != null) {
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                // 取输出
                val outIndex = codec.dequeueOutputBuffer(info, 5_000)
                if (outIndex >= 0) {
                    val bitmap = decodeBitmap(outIndex)
                    val pts = info.presentationTimeUs
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (bitmap != null) {
                        current?.recycle()
                        current = bitmap
                        currentPtsUs = pts
                    }
                    if (eos && bitmap == null) {
                        nextPtsUs = Long.MAX_VALUE
                        return
                    }
                    if (pts > targetUs) {
                        nextPtsUs = pts
                        return // current 已是 ≤ target 的最近帧
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    return // 等不来更多帧：保持当前
                }
            }
        }

        private fun decodeBitmap(outIndex: Int): Bitmap? {
            val image = codec.getOutputImage(outIndex) ?: run {
                codec.releaseOutputBuffer(outIndex, false)
                return null
            }
            val w = image.width
            val h = image.height
            val argb = yuvToArgb(image)
            codec.releaseOutputBuffer(outIndex, false)
            return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        }

        fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
            current?.recycle()
            nextFrame?.recycle()
        }
    }

    companion object {
        private const val TAG = "AutoRecordExport"
        private const val BPP = 0.08

        /** YUV_420_888 → ARGB（全幅）。 */
        fun yuvToArgb(image: Image): IntArray {
            val w = image.width
            val h = image.height
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
                    val yIndex = row * yRowStride + col * yPixelStride
                    val y = (yBuffer.get(yIndex).toInt() and 0xFF) - 16
                    val uvIndex = (row shr 1) * uRowStride + (col shr 1) * uPixelStride
                    val u = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                    val v = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128
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
                    yBuffer.put(row * yRowStride + col * yPixelStride, y.toByte())
                    if (row % 2 == 0 && col % 2 == 0) {
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        uBuffer.put((row shr 1) * uRowStride + (col shr 1) * uPixelStride, u.toByte())
                        vBuffer.put((row shr 1) * vRowStride + (col shr 1) * vPixelStride, v.toByte())
                    }
                }
            }
        }
    }
}
