package com.ecarx.autoservice.platform.adb

/**
 * 一次本机 ADB 连接的主机、端口和超时。
 *
 * 不打开套接字。调用方拥有解析出的端点，并在自己的连接里使用超时。
 */
internal data class AdbEndpoint(
    val host: String,
    val port: Int,
    val connectTimeoutMs: Int = 10_000,
    val ioTimeoutMs: Int = 15_000,
)
