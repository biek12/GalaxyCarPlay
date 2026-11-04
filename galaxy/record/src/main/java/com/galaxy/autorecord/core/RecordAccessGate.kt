package com.galaxy.autorecord.core

/** 主线程串行授权屏障：检测→向服务申请→本进程复核→唯一 READY。期间不允许拉数据或开相机。 */
class RecordAccessGate(
    private val hasPermission: () -> Boolean,
    private val request: ((Boolean, String) -> Unit) -> Unit,
    private val onReady: () -> Unit,
    private val onBlocked: (String) -> Unit,
) {
    enum class State { WAITING, REQUESTING, READY, BLOCKED, CLOSED }
    var state = State.WAITING
        private set
    private var generation = 0
    fun begin() {
        if (state in setOf(State.CLOSED, State.REQUESTING, State.READY)) return
        val ticket = ++generation
        if (hasPermission()) { state = State.READY; onReady(); return }
        state = State.REQUESTING
        request { granted, reason ->
            if (ticket != generation || state != State.REQUESTING) return@request
            if (granted && hasPermission()) {
                state = State.READY
                onReady()
            } else {
                state = State.BLOCKED
                onBlocked(reason.ifEmpty { "授权未通过本进程复核" })
            }
        }
    }
    fun block(reason: String) {
        if (state == State.CLOSED) return
        ++generation; state = State.BLOCKED; onBlocked(reason)
    }
    fun reset() { if (state != State.CLOSED) { ++generation; state = State.WAITING } }
    fun close() { ++generation; state = State.CLOSED }
}
