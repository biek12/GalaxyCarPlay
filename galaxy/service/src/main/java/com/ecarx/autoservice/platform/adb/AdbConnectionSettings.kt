package com.ecarx.autoservice.platform.adb

/**
 * 本机 ADB 端点偏好。只接受环回主机。
 */
internal data class AdbConnectionSettings(
    val host: String,
    val port: Int,
    val directConnect: Boolean,
) {
    /** 主机必须是环回地址，端口在 1 到 65535。不修改字段。 */
    fun valid(): Boolean = host in setOf("127.0.0.1", "localhost", "::1") && port in 1..65535
}
