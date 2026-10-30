package com.ecarx.autoservice.platform

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 全进程共用截止时间调度，不为每条Shell/连接创建一个计时线程。 */
internal object PlatformDeadlines {
    private val timer = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "AutoService-deadlines").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    fun after(milliseconds: Long, action: () -> Unit) = timer.schedule(action, milliseconds, TimeUnit.MILLISECONDS)
}
