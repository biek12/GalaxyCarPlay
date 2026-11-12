package com.ecarx.autoservice.platform.adb

import java.io.Closeable

/**
 * 一次已认证的 ADB 会话。调用方负责 [close]，重复关闭由实现保持幂等。
 */
internal interface AdbSession : Closeable {
    /** 在调用线程完成握手。失败抛出 IO，不在这里重试。 */
    fun connect()

    /**
     * 执行一条 shell，并把输出截到 [limit]。
     * 调用方拥有返回的文本，本方法不关闭会话。
     */
    fun shell(
        command: String,
        limit: Int,
    ): ShellV2Output

    /** 请求 adb root，返回原始回复文本。不保证进程已经是 root。 */
    fun root(): String
}
