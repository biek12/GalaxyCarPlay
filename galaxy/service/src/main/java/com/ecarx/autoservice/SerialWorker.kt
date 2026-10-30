package com.ecarx.autoservice

import android.os.Handler
import android.os.HandlerThread
import java.io.Closeable

/** 按第一次任务创建串行worker，空功能不占线程；关闭后拒绝排队。 */
internal class SerialWorker(private val name: String) : Closeable {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var closed = false
    @Synchronized private fun target(): Handler? {
        if (closed) return null
        if (handler == null) {
            val next = HandlerThread(name).apply { start() }
            thread = next; handler = Handler(next.looper)
        }
        return handler
    }
    fun post(action: Runnable) { target()?.post(action) }
    @Synchronized fun removeCallbacks(action: Runnable) { handler?.removeCallbacks(action) }
    @Synchronized override fun close() {
        closed = true
        // 已接收的任务完成其finally（例如关闭借入FD）；不删除有资源所有权的队列项。
        thread?.quitSafely()
        handler = null; thread = null
    }
}
