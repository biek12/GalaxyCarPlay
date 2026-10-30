package com.ecarx.autoservice.platform

/** 旧debug入口迁至系统只读诊断，禁止用另一个PID冒充App owner。 */
object RootDebugProbe {
    @JvmStatic fun main(args: Array<String>) {
        println("Use dumpsys activity service com.ecarx.autoservice/.AutoService for redacted diagnostics")
    }
}
