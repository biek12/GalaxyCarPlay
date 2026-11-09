package com.galaxy.autorecord.export

/** 导出线程独占。最多持有两帧；未来帧只在目标时钟到达后才移交为当前帧。 */
internal class TimedFrames<T>(private val dispose: (T) -> Unit) {
    var current: T? = null
        private set
    private var future: T? = null
    private var futurePtsUs = 0L
    val hasFuture: Boolean get() = future != null

    fun advance(targetUs: Long) {
        if (future != null && futurePtsUs <= targetUs) {
            current?.let(dispose)
            current = future
            future = null
        }
    }

    fun offer(ptsUs: Long, frame: T, targetUs: Long) {
        check(!hasFuture) { "future_frame_already_owned" }
        if (ptsUs <= targetUs) {
            current?.let(dispose)
            current = frame
        } else {
            future = frame
            futurePtsUs = ptsUs
        }
    }

    fun close() {
        current?.let(dispose)
        future?.let(dispose)
        current = null
        future = null
    }
}
