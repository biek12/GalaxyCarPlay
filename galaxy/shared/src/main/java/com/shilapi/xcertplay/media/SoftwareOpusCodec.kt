package com.shilapi.xcertplay.media

import org.concentus.OpusDecoder
import java.io.Closeable

/** 软件后备不依赖车机 MediaCodec；每个流独占状态与有界缓冲，不保留语音。 */
object SoftwareOpusSupport {
    val outputAvailable: Boolean by lazy {
        runCatching { SoftwareOpusDecoder(48_000, 1).close(); true }.getOrDefault(false)
    }
    val inputAvailable: Boolean by lazy {
        runCatching { SoftwareOpusEncoder(24_000).use { it.available } }.getOrDefault(false)
    }
}

internal class SoftwareOpusDecoder(sampleRate: Int, private val channels: Int) : Closeable {
    init { require(sampleRate == 48_000 && channels in 1..2) }
    private var decoder: OpusDecoder? = OpusDecoder(sampleRate, channels)
    // Opus 单包最长 120 ms；不按网络包长度申请 PCM 缓冲。
    private val samples = ShortArray(5_760 * channels)
    val pcm = ByteArray(samples.size * 2)

    fun decode(packet: ByteArray): Int {
        val active = checkNotNull(decoder) { "解码器已关闭" }
        require(packet.isNotEmpty() && packet.size <= 7_650)
        val frames = active.decode(packet, 0, packet.size, samples, 0, 5_760, false)
        check(frames in 1..5_760)
        repeat(frames * channels) { index ->
            val value = samples[index].toInt()
            pcm[index * 2] = value.toByte()
            pcm[index * 2 + 1] = (value shr 8).toByte()
        }
        return frames * channels * 2
    }

    override fun close() {
        decoder = null
        samples.fill(0)
        pcm.fill(0)
    }
}

// 上行复用官方v0.2.21的SoftwareOpusEncoder，保留新版异常隔离、复位与复杂度策略。
