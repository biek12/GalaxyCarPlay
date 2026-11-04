package com.galaxy.autorecord.ui

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 界面侧的一次性后台任务。
 *
 * 用途：卷枚举（StorageManager）、可写探测（真写 1 字节）、编码器枚举
 * 都可能阻塞数百毫秒，不能在 UI 线程直接做；结果回主线程再更新行内文字。
 *
 * 边界：
 * - 单线程串行，避免同一时刻并发探测同一介质；
 * - Activity 销毁后丢弃回投，不触碰已销毁的 View；
 * - 任务内部异常一律转成 [Result] 交给调用方就地报错，不静默吞掉。
 */
internal class RecordIo {
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "record-ui-probe").apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    fun <T> run(task: () -> T, deliver: (Result<T>) -> Unit) {
        if (closed) return
        worker.execute {
            val result = runCatching { task() }
            main.post { if (!closed) deliver(result) }
        }
    }

    fun close() {
        closed = true
        worker.shutdownNow()
    }
}