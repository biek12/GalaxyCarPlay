package com.galaxy.autorecord.recording

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import com.galaxy.autorecord.core.EncoderInfo
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** 会话级编码器；换文件不触碰codec/Reader。输出回调须在返回前复制借用的buffer。 */
internal class ContinuousCodec(val direction: String, val info: EncoderInfo,
    val width: Int, val height: Int, val fps: Int, val bitrate: Long, val gop: Int,
    private val format: (MediaFormat) -> Unit,
    private val sample: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    private val failure: (Exception) -> Unit) {
    private val inputLock = Any()
    @Volatile private var stopping = false
    @Volatile private var broken = false
    private val sync = java.util.concurrent.atomic.AtomicBoolean()
    @Volatile private var nextBoundaryUs=Long.MAX_VALUE
    @Volatile private var periodUs=Long.MAX_VALUE
    fun scheduleKeyFrames(clock:SegmentClock){nextBoundaryUs=clock.firstEndUs;periodUs=clock.periodUs}
    val copyMicros=AtomicLong()
    @Volatile var inputLayout="waiting";private set
    val inputFrames = AtomicLong()
    val backpressure = AtomicLong()
    val inputWaits = AtomicLong()
    val outputFrames = AtomicLong()
    @Volatile var lastOutputUs=0L; private set
    val codec: MediaCodec
    private val owner = EncoderDrainOwner("record-drain-$direction", drain = { drain(it) }, release = {
        stopping = true
        synchronized(inputLock) { runCatching { codec.stop() }; codec.release() }
    })
    init {
        val candidate = MediaCodec.createByCodecName(info.name)
        try {
            val plan = MediaFormat.createVideoFormat(info.mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate.toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, gop)
                // 无B帧：文件路由依赖解码/显示顺序一致，不伪造PTS。
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            candidate.configure(plan, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); candidate.start()
            codec = candidate
        } catch (e: Exception) { candidate.release(); throw e }
    }
    fun start() = owner.start()
    fun requestKeyFrame() { sync.set(true) }
    fun queue(image: Image) = synchronized(inputLock) {
        if (stopping || broken) return@synchronized
        try {
            val pts=image.timestamp/1000
            if(pts>=nextBoundaryUs){
                codec.setParameters(Bundle().apply{putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0)})
                nextBoundaryUs+=((pts-nextBoundaryUs)/periodUs+1)*periodUs
            }
            val waitStarted=android.os.SystemClock.elapsedRealtime()
            var index = codec.dequeueInputBuffer(20_000)
            while(index<0 && !stopping && !broken) {
                inputWaits.incrementAndGet()
                check(android.os.SystemClock.elapsedRealtime()-waitStarted<8000){"encoder_input_timeout"}
                index=codec.dequeueInputBuffer(20_000)
            }
            if (index < 0) { backpressure.incrementAndGet(); return@synchronized }
            val target = codec.getInputImage(index) ?: error("encoder_input_image_missing")
            if(inputFrames.get()==0L)inputLayout="s:"+image.planes.joinToString("/"){"${it.rowStride}:${it.pixelStride}"}+";d:"+target.planes.joinToString("/"){"${it.rowStride}:${it.pixelStride}"}
            val copyAt=android.os.SystemClock.elapsedRealtimeNanos()/1000
            ChannelEncoder.copyPlanes(image, target)
            copyMicros.addAndGet(android.os.SystemClock.elapsedRealtimeNanos()/1000-copyAt)
            codec.queueInputBuffer(index, 0, width * height * 3 / 2, image.timestamp / 1000, 0)
            inputFrames.incrementAndGet()
        } catch (e: Exception) { broken = true; reportFailure("codec_input",e);failure(e) }
    }
    /** 只取消输入等待，不跨线程释放native对象；引擎关闭Reader屏障之前先发此信号。 */
    fun stopInput() { stopping=true }
    fun finish(): Boolean { stopping = true; return owner.finish() }
    private fun drain(owner: EncoderDrainOwner) {
        var eosQueued = false
        val out = MediaCodec.BufferInfo()
        try {
            while (true) {
                check(!owner.expired) { "codec_eos_timeout" }
                if (sync.getAndSet(false)) codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                if (owner.stopping && !eosQueued) synchronized(inputLock) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosQueued = true }
                }
                val index = codec.dequeueOutputBuffer(out, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format(codec.outputFormat)
                if (index >= 0) {
                    try {
                        if (out.size > 0 && out.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            sample(checkNotNull(codec.getOutputBuffer(index)), out)
                            outputFrames.incrementAndGet()
                            lastOutputUs=android.os.SystemClock.elapsedRealtimeNanos()/1000
                        }
                    } finally { codec.releaseOutputBuffer(index, false) }
                    if (out.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                if (broken) break
            }
        } catch (e: Exception) { broken = true;reportFailure("codec_output",e);failure(e) }
    }
    private fun reportFailure(stage:String,e:Exception) {
        com.galaxy.autorecord.core.RecordDiagnostics.event(stage,e,mapOf("direction" to direction,
            "codec" to info.name,"width" to width,"height" to height,"fps" to fps,"bitrate" to bitrate,"gop" to gop))
    }
}
