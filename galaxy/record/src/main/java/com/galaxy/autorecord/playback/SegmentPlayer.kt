package com.galaxy.autorecord.playback

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.galaxy.autorecord.core.SegmentLayout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 片段同步解码播放器（B4）：四路 MP4 → MediaCodec 解码 → Surface 渲染，
 * 四路共用一条会话时间轴（需求 R05-2）。
 *
 * 时钟模型：播放中按墙钟×倍速推进；各路解码线程把 PTS ≤ 当前时钟的帧渲染出去，
 * 未来帧**持有缓冲等待**（不丢帧、不提前渲染）。拖动/跳事件统一 seek：
 * flush + seekTo 上一同步帧，目标前的帧解码后直接释放不渲染（seek 精度≈GOP）。
 *
 * 纪律：
 * - PTS 全部来自 MP4 内的真实样本时间（录制侧已换算为段相对时间轴），不用帧号推算；
 * - 缺路不造帧、不用邻路画面顶替；文件缺失/损坏如实报 [onError]；
 * - 回放只读历史 sidecar（由页面层处理），不发起车辆采集。
 */
class SegmentPlayer(
    private val segmentDir: File,
    directions: List<String>,
    private val durationMs: Long,
) {
    private val channels = ConcurrentHashMap<String, Channel>()
    private val wanted: List<String> = directions.filter { it in SegmentLayout.DIRECTIONS }.distinct()
    private val released = AtomicBoolean(false)

    @Volatile var playing = false
        private set

    @Volatile var speed = 1f
        set(value) {
            basePositionMs = positionMs
            baseWallMs = SystemClock.elapsedRealtime()
            field = value
        }
    var onEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // 时钟锚点：positionMs = basePositionMs + (now − baseWallMs) × speed（播放中）
    @Volatile private var basePositionMs = 0L
    @Volatile private var baseWallMs = SystemClock.elapsedRealtime()

    val positionMs: Long
        get() = if (playing) {
            basePositionMs + ((SystemClock.elapsedRealtime() - baseWallMs) * speed).toLong()
        } else {
            basePositionMs
        }

    /** 某方向的视频文件是否存在（缺路判定由页面用 sidecar，这里只看文件）。 */
    fun hasVideo(direction: String): Boolean =
        SegmentLayout.videoFile(segmentDir, direction).let { it.isFile && it.length() > 0 }

    /** 绑定渲染输出；surface 变化会以当前时钟重建该路解码器。 */
    fun attach(direction: String, surface: Surface) {
        if (released.get() || direction !in wanted) return
        val previous = channels.remove(direction)
        previous?.stop()
        val file = SegmentLayout.videoFile(segmentDir, direction)
        if (!file.isFile || file.length() == 0L) {
            Log.i(TAG, "缺路或文件为空 $direction，不解码")
            return
        }
        val channel = Channel(direction, file, surface)
        channels[direction] = channel
        channel.start()
        // 重建解码器后定位到当前时钟，保持四路同步
        channel.seek(positionMs)
    }

    fun detach(direction: String) {
        channels.remove(direction)?.stop()
    }

    fun play() {
        if (released.get()) return
        if (positionMs >= durationMs) seekTo(0)
        baseWallMs = SystemClock.elapsedRealtime()
        playing = true
    }

    fun pause() {
        basePositionMs = positionMs
        playing = false
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0L, durationMs)
        basePositionMs = target
        baseWallMs = SystemClock.elapsedRealtime()
        channels.values.forEach { it.seek(target) }
    }

    /** 播到末尾检测（页面节拍调用）：全部有帧路到 EOS 且时钟过终点。 */
    fun maybeEnded(): Boolean {
        if (!playing) return false
        if (positionMs < durationMs) return false
        pause()
        basePositionMs = durationMs
        onEnded?.invoke()
        return true
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        playing = false
        channels.values.forEach { it.stop() }
        channels.clear()
    }

    /** 单路解码线程：喂样本 → 按时钟渲染 → seek/收尾。 */
    private inner class Channel(
        val direction: String,
        private val file: File,
        private val surface: Surface,
    ) {
        private val stopFlag = AtomicBoolean(false)
        private val seekFlag = AtomicBoolean(false)
        @Volatile private var seekTargetUs = 0L
        private var thread: Thread? = null

        fun start() {
            thread = Thread({ run() }, "playback-$direction").apply {
                priority = Thread.NORM_PRIORITY - 1
                start()
            }
        }

        fun seek(positionMs: Long) {
            seekTargetUs = positionMs * 1000
            seekFlag.set(true)
        }

        fun stop() {
            stopFlag.set(true)
            seekFlag.set(true) // 唤醒等待
            thread?.let { runCatching { it.join(1500) } }
            thread = null
        }

        private fun run() {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            try {
                extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
                val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: throw IllegalStateException("no_video_track")
                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)
                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(format, surface, null, 0)
                codec.start()
                loop(extractor, codec)
            } catch (error: Exception) {
                Log.w(TAG, "解码失败 $direction：${error.javaClass.simpleName}", error)
                if (!stopFlag.get()) {
                    onError?.invoke("$direction 解码失败（${error.javaClass.simpleName}）")
                }
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor?.release() }
            }
        }

        private fun loop(extractor: MediaExtractor, codec: MediaCodec) {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var heldIndex = -1

            fun releaseHeld(render: Boolean) {
                if (heldIndex >= 0) {
                    runCatching { codec.releaseOutputBuffer(heldIndex, render) }
                    heldIndex = -1
                }
            }

            while (!stopFlag.get()) {
                if (seekFlag.compareAndSet(true, false)) {
                    releaseHeld(false)
                    codec.flush()
                    extractor.seekTo(seekTargetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    inputDone = false
                    outputDone = false
                }

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

                if (heldIndex < 0 && !outputDone) {
                    val outIndex = codec.dequeueOutputBuffer(info, 5_000)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        outIndex >= 0 -> heldIndex = outIndex
                    }
                }

                if (heldIndex >= 0) {
                    val ptsMs = info.presentationTimeUs / 1000
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val target = positionMs
                    when {
                        // 未来帧：持有缓冲等待时钟（不丢帧）
                        !eos && ptsMs > target + TOLERANCE_MS -> Thread.sleep(4)
                        else -> {
                            releaseHeld(render = true)
                            if (eos) outputDone = true
                        }
                    }
                } else if (outputDone && inputDone) {
                    Thread.sleep(20) // 本路已到 EOS：等 seek 或停止
                } else if (!outputDone) {
                    Thread.sleep(2)
                }
            }
            releaseHeld(false)
        }
    }

    companion object {
        private const val TAG = "AutoRecordPlayback"
        private const val TOLERANCE_MS = 30L
    }
}
