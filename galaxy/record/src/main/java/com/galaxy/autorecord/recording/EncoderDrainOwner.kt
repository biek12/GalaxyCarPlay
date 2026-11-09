package com.galaxy.autorecord.recording

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** drain 工作和 native 资源释放由同一线程拥有。等待超时只请求终止，不跨线程释放。 */
internal class EncoderDrainOwner(name: String, private val timeoutMs: Long = 8_000,
    private val drain: (EncoderDrainOwner) -> Unit, private val release: () -> Unit) {
    private val done = CountDownLatch(1)
    private var started = false
    @Volatile private var deadlineNs = Long.MAX_VALUE
    val stopping get() = deadlineNs != Long.MAX_VALUE
    val expired get() = System.nanoTime() >= deadlineNs
    val completed get() = done.count == 0L
    private val thread = Thread({
        try { drain(this) } finally { try { release() } finally { done.countDown() } }
    }, name).apply { priority = Thread.NORM_PRIORITY - 1 }

    @Synchronized fun start() {
        if (!started) { started = true; thread.start() }
    }

    fun finish(): Boolean {
        synchronized(this) {
            if (!stopping) deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            start()
        }
        return try { done.await(timeoutMs + 1_000, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }
}
