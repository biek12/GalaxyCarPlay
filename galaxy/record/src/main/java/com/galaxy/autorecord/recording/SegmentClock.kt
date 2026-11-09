package com.galaxy.autorecord.recording

/** 墙钟只在会话开始映射一次，此后全用源单调时钟；校时不倒退PTS。 */
internal class SegmentClock(val wallMs: Long, val monoUs: Long, seconds: Int) {
    val periodUs = seconds.toLong() * 1_000_000
    val firstEndUs: Long
    init {
        require(seconds > 0)
        val durationMs = seconds * 1000L
        val endWall = if (seconds >= 60 && seconds % 60 == 0)
            ((wallMs + durationMs + 59_999) / 60_000) * 60_000 else wallMs + durationMs
        firstEndUs = monoUs + (endWall - wallMs) * 1000
    }
    fun startUs(index: Int) = if (index == 1) monoUs else firstEndUs + (index - 2) * periodUs
    fun endUs(index: Int) = firstEndUs + (index - 1) * periodUs
    fun wall(index: Int) = wallMs + (startUs(index) - monoUs) / 1000
}
