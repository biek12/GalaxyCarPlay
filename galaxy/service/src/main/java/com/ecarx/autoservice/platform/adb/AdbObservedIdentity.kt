package com.ecarx.autoservice.platform.adb

/**
 * 从已连接会话读取本机 ADB UID。不打开第二套连接。
 */
internal object AdbObservedIdentity {
    /**
     * 在调用线程执行 `id -u`。
     * 退出码不是 0 时抛出 `adb_identity_failed`；UID 不能解析时抛出 `adb_identity_invalid`。
     * 不关闭 [session]。
     */
    fun read(session: AdbSession): ControlIdentity {
        val result = session.shell("id -u", 128)
        check(result.exitCode == 0) { "adb_identity_failed" }
        val uid =
            result.stdout
                .trim()
                .toIntOrNull()
                ?.takeIf { it >= 0 } ?: error("adb_identity_invalid")
        return ControlIdentity(uid)
    }
}
