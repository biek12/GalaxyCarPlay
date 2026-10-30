package com.galaxy.auto.contract

/** 单owner线程的分数周期调度；工作耗时计入周期，迟到后跳过已过期节拍，不排旧帧。 */
class FramePacer(rate: Int = 30) {
    var rate: Int = rate; private set
    var nextAtNanos = 0L; private set
    private var remainder = 0L
    init { require(rate in 1..60) }
    fun setRate(value: Int, now: Long) {
        require(value in 1..60)
        if (value == rate) return
        rate = value; remainder = 0; nextAtNanos = now
    }
    fun delayMillis(now: Long): Long = ((nextAtNanos - now).coerceAtLeast(0) + 999_999) / 1_000_000
    fun started(now: Long) {
        val period = 1_000_000_000L / rate
        if (nextAtNanos == 0L || now - nextAtNanos >= period) nextAtNanos = now
        nextAtNanos += period
        remainder += 1_000_000_000L % rate
        if (remainder >= rate) { nextAtNanos += remainder / rate; remainder %= rate }
    }
    fun reset() { nextAtNanos = 0; remainder = 0 }
}
