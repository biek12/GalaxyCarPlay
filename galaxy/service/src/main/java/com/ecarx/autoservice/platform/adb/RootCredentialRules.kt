package com.ecarx.autoservice.platform.adb

/**
 * VPWD 字面量规则。不访问存储，任意线程都可调用。
 */
internal object RootCredentialRules {
    /**
     * 长度在 1 到 256，且不含换行或 NUL。
     * 不合规时返回 false，不修改传入数组。
     */
    fun valid(value: CharArray): Boolean =
        value.size in 1..256 && value.none { it == '\n' || it == '\r' || it == '\u0000' }
}
