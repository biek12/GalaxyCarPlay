package com.galaxy.autorecord.recording

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.galaxy.autorecord.core.EncoderInfo
import com.galaxy.autorecord.storage.ChannelWriteResult
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单路编码写盘器：YUV 取帧（Camera2 YUV_420_888 → MediaCodec 输入缓冲）→ MediaMuxer →
 * `<dir>.mp4.writing`。
 *
 * 通路选择（实测修正）：本车 LEGACY HAL 对"相机→编码器输入 Surface"不出帧（20261104 实测：
 * 双 Surface 会话建立、预览 25fps，但编码器 60s 零出帧），改走技术口径 §2 路线 B——
 * YUV_420_888 ImageReader 取帧、按 rowStride/pixelStride 拷入编码器输入（COLOR_FormatYUV420Flexible）。
 * CPU 拷贝代价如实承担，不伪造"不经 CPU"。
 *
 * 时间戳纪律：PTS 取自 `Image.timestamp`（SENSOR_TIMESTAMP 域，与 elapsedRealtimeNanos 同域），
 * **不是**"收到帧的时刻"、更不是"帧序号×固定时长"。写盘前平移为段相对时间轴（0 = 段锚点），
 * 段锚点前的在途帧如实丢弃并计数。
 *
 * 收尾顺序（技术口径 §3，不可交换）：signalEndOfInputStream → drain 到 EOS 并写完剩余帧 →
 * muxer.stop()（生成 moov）→ release。用户主动停止同样走完，不得跳过 stop 丢掉 moov。
 */
class ChannelEncoder private constructor(
    val direction: String,
    private val writing: File,
    private val mime: String,
    private val encoderName: String,
    private val implementation: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateBps: Long,
    private val gopSeconds: Int,
    private val ptsOffsetUs: Long,
    private val onFirstSample: (() -> Unit)?,
) {
    private val codec: MediaCodec
    private val muxer = MediaMuxer(writing.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    private var muxerStarted = false
    private var trackIndex = -1
    private var frameCount = 0L
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L
    private var droppedPreAnchor = 0L
    private var droppedBackpressure = 0L
    private var eosSeen = false
    private var streamError: String? = null
    /** muxer.stop() 是否成功（决定文件是否可提交；失败保留 .writing 证据，不伪造 complete）。 */
    private var muxerStopped = false
    private val stopRequested = AtomicBoolean(false)
    private val doneSignal = LinkedBlockingQueue<Boolean>(1)
    private var drainThread: Thread? = null
    /** 输入侧互斥：queueYuv 与 EOS 入队都占输入缓冲，MediaCodec 不允许并发调用。 */
    private val inputLock = Any()

    init {
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps.toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, gopSeconds)
        }
        codec = MediaCodec.createByCodecName(encoderName)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /**
     * 送入一帧 YUV（相机 ImageReader 回调线程调用）。
     * 按 stride 拷贝三平面到编码器输入；PTS 用帧自带时间戳（绝对域，drain 侧统一平移段相对）。
     */
    fun queueYuv(image: Image, frameTimestampNs: Long) {
        if (stopRequested.get() || eosSeen) {
            image.close()
            return
        }
        val relUs = frameTimestampNs / 1000 - ptsOffsetUs
        if (relUs < 0) {
            // 段锚点前的在途帧：如实丢弃，不写入、不伪造时间
            droppedPreAnchor++
            image.close()
            return
        }
        synchronized(inputLock) {
            if (stopRequested.get() || eosSeen) {
                image.close()
                return
            }
            val inIndex = try {
                codec.dequeueInputBuffer(DRAIN_TIMEOUT_US)
            } catch (error: Exception) {
                // 输入侧单帧异常＝丢帧；收尾竞态（stopRequested）静默丢弃，不毒化整路收尾
                droppedBackpressure++
                if (!stopRequested.get()) Log.w(TAG, "编码输入异常 $direction：${error.javaClass.simpleName}")
                image.close()
                return
            }
            if (inIndex < 0) {
                droppedBackpressure++ // 编码跟不上：丢帧并计数，不排队堆积
                image.close()
                return
            }
            try {
                val target = codec.getInputImage(inIndex)
                if (target == null) {
                    codec.queueInputBuffer(inIndex, 0, 0, frameTimestampNs / 1000, 0)
                } else {
                    copyPlanes(image, target)
                    codec.queueInputBuffer(inIndex, 0, width * height * 3 / 2, frameTimestampNs / 1000, 0)
                }
            } catch (error: Exception) {
                // 输入侧异常只丢当前帧（如实计数），绝不把整路已写盘内容判失败；
                // 段收尾竞态（编码器已被 drain 线程释放）属预期噪声，静默丢弃。
                droppedBackpressure++
                if (!stopRequested.get()) {
                    Log.w(TAG, "编码送帧异常 $direction：${error.javaClass.simpleName}（丢 1 帧）")
                }
                runCatching { codec.queueInputBuffer(inIndex, 0, 0, 0L, 0) }
            } finally {
                image.close()
            }
        }
    }

    /** 启动 drain 线程（每编码器一条，随 finish() 退出；不创建无界线程）。 */
    fun startDrain() {
        drainThread = Thread({ drainLoop() }, "record-drain-$direction").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    /**
     * 收尾一路：EOS → drain → muxer.stop → release。阻塞至 drain 结束。
     * 返回真实 [ChannelWriteResult]；无有效帧/封装未启动/异常时返回 null，由上层按具名原因记失败。
     *
     * EOS 口径：本编码器走 ByteBuffer 输入（YUV 取帧），**必须用带 EOS 标志的输入缓冲收尾**；
     * `signalEndOfInputStream()` 只适用于 Surface 输入模式，用错会让 drain 永远等不到 EOS。
     */
    fun finish(): ChannelWriteResult? {
        stopRequested.set(true)
        synchronized(inputLock) {
            try {
                val inIndex = codec.dequeueInputBuffer(500_000)
                if (inIndex >= 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else if (streamError == null) {
                    streamError = "EOS 入队超时（编码器无空闲输入缓冲）"
                }
            } catch (error: Exception) {
                if (streamError == null) streamError = "EOS 入队异常：${error.javaClass.simpleName}"
            }
        }
        // drain 线程收到 EOS 后走完 stop/release 并投递 doneSignal
        val finished = try {
            doneSignal.poll(FINISH_TIMEOUT_MS, TimeUnit.MILLISECONDS) == true
        } catch (e: InterruptedException) {
            false
        }
        if (!finished) {
            Log.w(TAG, "收尾超时 $direction，强制释放")
            forceRelease()
        }
        drainThread?.let { runCatching { it.join(2000) } }
        drainThread = null

        if (!muxerStopped || frameCount <= 0L) {
            Log.w(
                TAG,
                "通道未完成收尾 $direction（frames=$frameCount muxer=$muxerStarted stopped=$muxerStopped " +
                    "droppedPreAnchor=$droppedPreAnchor dropped=$droppedBackpressure err=$streamError）",
            )
            return null
        }
        streamError?.let {
            // 输出侧曾有告警但 muxer 已正常 stop、文件有真实帧：如实告警后仍提交（内容是真实证据）
            Log.w(TAG, "通道收尾告警 $direction：$it（已写 $frameCount 帧，仍提交）")
        }
        if (droppedPreAnchor > 0 || droppedBackpressure > 0) {
            Log.i(TAG, "通道丢帧 $direction 段前=$droppedPreAnchor 背压/异常=$droppedBackpressure（如实计数，不补帧）")
        }
        return ChannelWriteResult(
            transactionId = "", // 由 RecordingEngine 回填真实事务身份
            direction = direction,
            codecMime = mime,
            encoderName = encoderName,
            implementation = implementation,
            width = width,
            height = height,
            fps = fps,
            bitrateBps = bitrateBps,
            gopSeconds = gopSeconds,
            frameCount = frameCount,
            firstPtsUs = firstPtsUs,
            lastPtsUs = lastPtsUs,
        )
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (!eosSeen) {
                val outIndex = codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                            Log.i(TAG, "封装开始 $direction track=$trackIndex")
                        }
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)
                        try {
                            if (buffer != null && info.size > 0 && muxerStarted) {
                                val relUs = info.presentationTimeUs - ptsOffsetUs
                                if (relUs < 0) {
                                    droppedPreAnchor++
                                } else {
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    val shifted = MediaCodec.BufferInfo().apply {
                                        set(0, info.size, relUs, info.flags)
                                    }
                                    muxer.writeSampleData(trackIndex, buffer, shifted)
                                    if (firstPtsUs < 0) {
                                        firstPtsUs = relUs
                                        onFirstSample?.invoke()
                                    }
                                    lastPtsUs = relUs
                                    frameCount++
                                }
                            }
                        } finally {
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            eosSeen = true
                        }
                    }
                }
            }
        } catch (error: Exception) {
            streamError = "编码/封装异常：${error.javaClass.simpleName}"
            Log.w(TAG, "drain 异常 $direction", error)
        } finally {
            runCatching {
                if (muxerStarted) {
                    muxer.stop()
                    muxerStopped = true
                }
            }.onFailure { if (streamError == null) streamError = "muxer.stop 失败：${it.javaClass.simpleName}" }
            runCatching { muxer.release() }
            // 释放编码器与输入侧同锁：避免 queueYuv 迟到回调撞上已释放的 codec（IllegalStateException 竞态）
            synchronized(inputLock) {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            doneSignal.offer(true)
        }
    }

    private fun forceRelease() {
        synchronized(inputLock) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        runCatching { muxer.release() }
        if (streamError == null) streamError = "收尾超时"
    }

    companion object {
        private const val TAG = "AutoRecordEngine"
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val FINISH_TIMEOUT_MS = 8_000L

        /**
         * 创建单路编码器。失败抛 [EncoderCreateException]（带方向与原因），
         * 调用方必须报错，禁止静默换编码器/降级。
         */
        fun create(
            direction: String,
            writing: File,
            encoder: EncoderInfo,
            width: Int,
            height: Int,
            fps: Int,
            bitrateBps: Long,
            gopSeconds: Int,
            ptsOffsetUs: Long,
            onFirstSample: (() -> Unit)? = null,
        ): ChannelEncoder {
            require(width > 0 && height > 0) { "非法编码尺寸 ${width}x$height" }
            require(fps > 0 && bitrateBps > 0 && gopSeconds > 0) { "非法编码参数 fps=$fps bitrate=$bitrateBps gop=$gopSeconds" }
            val implementation = when {
                encoder.hardware && !encoder.software -> "hardware"
                encoder.software -> "software"
                else -> "unknown"
            }
            return try {
                ChannelEncoder(
                    direction = direction,
                    writing = writing,
                    mime = encoder.mime,
                    encoderName = encoder.name,
                    implementation = implementation,
                    width = width,
                    height = height,
                    fps = fps,
                    bitrateBps = bitrateBps,
                    gopSeconds = gopSeconds,
                    ptsOffsetUs = ptsOffsetUs,
                    onFirstSample = onFirstSample,
                )
            } catch (error: Exception) {
                throw EncoderCreateException(direction, encoder.name, error.javaClass.simpleName)
            }
        }

        /** YUV_420_888 → 编码器输入平面（按 rowStride/pixelStride 逐行拷贝）。 */
        internal fun copyPlanes(src: Image, dst: Image) {
            for (planeIndex in 0 until minOf(src.planes.size, dst.planes.size)) {
                val srcPlane = src.planes[planeIndex]
                val dstPlane = dst.planes[planeIndex]
                val srcBuffer = srcPlane.buffer
                val dstBuffer = dstPlane.buffer
                val rowStride = srcPlane.rowStride
                val pixelStride = srcPlane.pixelStride
                val dstRowStride = dstPlane.rowStride
                val dstPixelStride = dstPlane.pixelStride
                val width = if (planeIndex == 0) src.width else src.width / 2
                val height = if (planeIndex == 0) src.height else src.height / 2
                val rowBytes = (width - 1) * pixelStride + 1
                if (pixelStride == 1 && dstPixelStride == 1 && rowStride == dstRowStride && rowStride == rowBytes) {
                    srcBuffer.position(0)
                    srcBuffer.limit(rowStride * height)
                    dstBuffer.position(0)
                    dstBuffer.put(srcBuffer)
                } else {
                    for (row in 0 until height) {
                        val srcPos = row * rowStride
                        val dstPos = row * dstRowStride
                        srcBuffer.position(srcPos)
                        dstBuffer.position(dstPos)
                        if (pixelStride == 1 && dstPixelStride == 1) {
                            srcBuffer.limit(srcPos + rowBytes)
                            dstBuffer.put(srcBuffer)
                        } else {
                            for (col in 0 until width) {
                                dstBuffer.put(dstPos + col * dstPixelStride, srcBuffer.get(srcPos + col * pixelStride))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 编码器创建失败：必须报出方向与阶段，不静默回退到别的编码器。 */
class EncoderCreateException(val direction: String, encoderName: String, causeName: String) :
    IllegalStateException("$direction 编码器创建失败：$encoderName（$causeName）")
