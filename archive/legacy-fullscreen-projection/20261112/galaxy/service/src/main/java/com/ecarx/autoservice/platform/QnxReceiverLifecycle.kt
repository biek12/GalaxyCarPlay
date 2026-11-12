package com.ecarx.autoservice.platform

/** 接收端有限监听可以退出；投屏需求由AutoService主动确保其重新运行，无需原车启动项。 */
internal class QnxReceiverLifecycle(private val port: Port) {
    enum class State { LISTENING, INSTALLED, NEEDS_INSTALL }
    enum class Action(val wire: String) { REUSED("reused"), WOKEN("woken"), INSTALLED("installed") }
    interface Port {
        fun inspect(): State
        fun stopOwned()
        fun install()
        fun verify()
        fun start()
    }
    fun ensureRunning(): Action {
        val state = port.inspect()
        if (state == State.LISTENING) return Action.REUSED
        port.stopOwned() // 清理过期PID或停止本服务的旧程序，外部进程不得被终止。
        if (state == State.NEEDS_INSTALL) port.install()
        port.verify()
        port.start()
        return if (state == State.INSTALLED) Action.WOKEN else Action.INSTALLED
    }
}
