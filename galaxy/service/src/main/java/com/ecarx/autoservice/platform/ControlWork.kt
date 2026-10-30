package com.ecarx.autoservice.platform

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** One operation's sockets/work. Cancellation closes I/O; callback still reports an indeterminate outcome. */
internal class ControlWork : java.io.Closeable {
    val cancelled = AtomicBoolean(false)
    private val resources = CopyOnWriteArrayList<Closeable>()
    fun checkActive() { if (cancelled.get()) throw IOException("cancelled") }
    fun own(resource: Closeable) {
        resources.add(resource)
        if (cancelled.get()) runCatching { resource.close() }
    }
    fun release(resource: Closeable) { resources.remove(resource); runCatching { resource.close() } }
    fun pause(delayMs: Long) {
        var remaining = delayMs.coerceAtLeast(0L)
        while (remaining > 0 && !cancelled.get()) {
            val slice = minOf(remaining, 100L)
            val before = android.os.SystemClock.elapsedRealtime()
            try { Thread.sleep(slice) } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("cancelled", error)
            }
            remaining -= (android.os.SystemClock.elapsedRealtime() - before).coerceAtLeast(slice)
        }
        if (cancelled.get()) throw IOException("cancelled")
    }
    fun cancel() {
        cancelled.set(true)
        resources.forEach { runCatching { it.close() } }
    }
    override fun close() { resources.forEach { runCatching { it.close() } }; resources.clear() }

}
