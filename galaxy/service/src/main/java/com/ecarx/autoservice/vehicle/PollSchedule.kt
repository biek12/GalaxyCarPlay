package com.ecarx.autoservice.vehicle

import android.os.Handler

/**
 * 单一可取消的轮询调度：任何时刻最多只有一个待执行任务。
 *
 * 订阅驱动的节拍由`Handler.postDelayed`承载，而Handler不会去重：同一个动作重复排队就是多条
 * 并行的轮询链，Root IPC、Bundle构造和回调次数按链数成倍上升，订阅侧却看不出任何异常
 * （服务端照样只看到“已订阅、有数据”）。所以“先取消上一次、只排这一次”必须是唯一用法；
 * 取消也只针对本任务——`removeCallbacksAndMessages(null)` 会连排在后面的新订阅一起删掉。
 *
 * `start()`/`stop()` 在调用线程上执行，`schedule()` 也在采集线程上执行，
 * 因此排程与取消在同一把锁里做，避免两个线程各排一条。
 */
internal class PollSchedule(private val task: Runnable) {
    private val lock = Any()
    private var pending: Handler? = null

    /** 在[handler]上替换排定的下一拍；`delayMillis = 0` 表示立即执行（仍排在当前消息之后）。 */
    fun schedule(handler: Handler, delayMillis: Long) = synchronized(lock) {
        pending?.removeCallbacks(task)
        pending = handler
        handler.postDelayed(task, delayMillis)
    }

    /** 取消已排的下一拍；重复调用幂等。正在执行的这一拍不受影响（由调用方的enabled/代次守卫兜住）。 */
    fun cancel() = synchronized(lock) {
        pending?.removeCallbacks(task)
        pending = null
    }
}