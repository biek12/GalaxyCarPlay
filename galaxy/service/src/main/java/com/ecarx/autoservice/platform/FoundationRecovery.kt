package com.ecarx.autoservice.platform

/** 沿星枢恢复循环：每轮I/O有截止时间，轮间退避；停止使迟到完成失效。 */
internal class FoundationRecovery(
    private val enabled: () -> Boolean,
    private val schedule: (Long, () -> Unit) -> AutoCloseable,
    private val recover: ((() -> Boolean), (Boolean) -> Unit) -> Unit,
) : AutoCloseable {
    @Volatile private var generation = 0L
    @Volatile private var running = false
    private var pending: AutoCloseable? = null
    private var failures = 0
    fun start() {
        if (running || !enabled()) return
        running = true; failures = 0; queue(0, ++generation)
    }
    private fun queue(delay: Long, token: Long) {
        pending = schedule(delay) {
            pending = null
            if (!current(token)) return@schedule
            recover({ current(token) }) { success ->
                if (!current(token)) return@recover
                failures = if (success) 0 else (failures + 1).coerceAtMost(5)
                queue(if (success) 60000 else (30000L shl (failures - 1)).coerceAtMost(300000), token)
            }
        }
    }
    private fun current(token: Long) = running && generation == token && enabled()
    override fun close() { running = false; ++generation; pending?.close(); pending = null }
}
