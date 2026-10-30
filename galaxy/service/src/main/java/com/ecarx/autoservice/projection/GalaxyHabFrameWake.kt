package com.ecarx.autoservice.projection

/** 采集线程可唤醒，发送worker执行；最多一个待办，运动帧提前替换静态心跳等待。
 * stop/start替换Runnable身份，已出队的旧代回调也不能操作新代调度状态。
 */
internal class GalaxyHabFrameWake(
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
) {
    private var task: Runnable? = null
    private var queued = false
    private var immediate = false

    @Synchronized fun start(action: () -> Unit) {
        stop()
        task = object : Runnable {
            override fun run() {
                val accepted = synchronized(this@GalaxyHabFrameWake) {
                    if (task !== this) false else {
                        queued = false; immediate = false; true
                    }
                }
                if (accepted) action()
            }
        }
    }

    @Synchronized fun wake() {
        val current = task ?: return
        if (queued && immediate) return
        if (queued) remove(current)
        queued = true; immediate = true
        post(current, 0)
    }

    @Synchronized fun idle(delay: Long) {
        val current = task ?: return
        // 发送期间新帧已唤醒时，不能用心跳延迟覆盖它。
        if (queued) return
        queued = true; immediate = false
        post(current, delay.coerceAtLeast(1))
    }

    @Synchronized fun stop() {
        task?.let(remove)
        task = null; queued = false; immediate = false
    }
}
