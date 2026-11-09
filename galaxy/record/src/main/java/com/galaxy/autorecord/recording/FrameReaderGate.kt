package com.galaxy.autorecord.recording

/** 取帧、使用图像、归还图像与关闭Reader共用所有权锁；关闭后到达的回调不再取帧。 */
internal class FrameReaderGate {
    private val lock = Any()
    private var closed = false
    private var readerReleased = false

    fun <T> consume(acquire: () -> T?, use: (T) -> Unit, release: (T) -> Unit) = synchronized(lock) {
        if (closed) return@synchronized
        val frame = acquire() ?: return@synchronized
        try { use(frame) } finally { release(frame) }
    }

    fun stop() = synchronized(lock) { closed = true }

    fun close(releaseReader: () -> Unit) = synchronized(lock) {
        if (readerReleased) return@synchronized
        closed = true
        readerReleased = true
        releaseReader()
    }
}
