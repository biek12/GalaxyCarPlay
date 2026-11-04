package com.shilapi.xcertplay.media

import kotlin.math.pow
import kotlin.math.roundToInt

/** 只作用于本App音乐PCM；按采样帧推进，块大小变化不改变斜坡时长，立体声同帧同增益。 */
internal class MediaDuckingGain(private val sampleRate: Int, private val channels: Int) {
    private data class Target(val db: Float, val millis: Int)
    @Volatile private var requested = Target(0f, 0)
    private var applied: Target? = null
    private var gain = 1f
    private var destination = 1f
    private var step = 0f
    private var remaining = 0L

    fun target(db: Float, durationMs: Int? = null) {
        requested = Target(if (db.isFinite()) db.coerceIn(-96f, 0f) else 0f,
            (durationMs ?: 500).coerceIn(0, 30_000))
    }

    /** 渲染线程唯一调用；控制线程只发布不可变目标。 */
    fun apply(data: ByteArray, offset: Int, length: Int) {
        val target = requested
        if (target != applied) {
            applied = target
            destination = 10.0.pow(target.db / 20.0).toFloat()
            remaining = sampleRate.toLong() * target.millis / 1000
            if (remaining == 0L) gain = destination else step = (destination - gain) / remaining
        }
        if (remaining == 0L && gain == 1f) return
        val frameBytes = channels.coerceAtLeast(1) * 2
        val end = offset + length / frameBytes * frameBytes
        var frame = offset
        while (frame < end) {
            if (remaining > 0) {
                remaining--
                gain = if (remaining == 0L) destination else gain + step
            }
            var index = frame
            while (index < frame + frameBytes) {
                val value = (data[index].toInt() and 255) or (data[index + 1].toInt() shl 8)
                val scaled = (value * gain).roundToInt().coerceIn(-32768, 32767)
                data[index] = scaled.toByte()
                data[index + 1] = (scaled shr 8).toByte()
                index += 2
            }
            frame += frameBytes
        }
    }
}
