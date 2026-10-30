package com.ecarx.autoservice.platform

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * 车机逆向live-root-debug-2026-09-17已确认的Android TCP/23 root调试入口。
 * 仅连接车机自身loopback，先独立读回Linux/UID0；只执行调用层生成的自有helper启动命令。
 * 不扫描车辆网络、不复用QNX口令，也不把单纯能连接23端口当成Root。
 */
internal object AndroidRootBootstrap {
    fun launch(work: ControlWork, command: String, port: Int = 23) {
        require(port in 1..65535)
        val socket = Socket()
        val owned = Closeable { socket.close() }.also(work::own)
        val deadline = PlatformDeadlines.after(10000) { runCatching { socket.close() } }
        try {
            socket.connect(InetSocketAddress("::1", port), 2000); socket.soTimeout = 3000
            val input = socket.getInputStream(); val output = socket.getOutputStream()
            fun send(text: String) { work.checkActive(); output.write((text + "\r\n").toByteArray()); output.flush() }
            fun until(marker: String): String {
                val bytes = java.io.ByteArrayOutputStream()
                while (bytes.size() < 8192) {
                    work.checkActive()
                    val value = input.read(); check(value >= 0) { "android_bootstrap_closed" }
                    if (value == 255) {
                        val operation = input.read()
                        if (operation in 251..254) {
                            val option = input.read(); check(option >= 0)
                            output.write(byteArrayOf(255.toByte(), (if (operation in 251..252) 254 else 252).toByte(), option.toByte())); output.flush()
                        } else if (operation == 250) {
                            var previous = 0
                            while (true) { val next = input.read(); check(next >= 0); if (previous == 255 && next == 240) break; previous = next }
                        } else if (operation == 255) bytes.write(255)
                        continue
                    }
                    bytes.write(value)
                    if (value == 10) {
                        val text = bytes.toString("UTF-8")
                        if (text.lines().any { it.trim() == marker }) return text
                    }
                }
                error("android_bootstrap_limit")
            }
            val marker = "ASBOOT_" + UUID.randomUUID().toString().replace("-", "")
            send("printf '\\n${marker}_BEGIN\\n'; uname -s; id -u; printf '${marker}_END\\n'")
            val response = until("${marker}_END").replace("\r", "").substringAfter("\n${marker}_BEGIN\n", "")
            check(response.lines().filter { it.isNotBlank() }.take(2) == listOf("Linux", "0")) { "android_bootstrap_not_root" }
            send("( $command ); _as_rc=\$?; printf '\\n${marker}_LAUNCH:%s\\n${marker}_DONE\\n' \"\$_as_rc\"")
            val result = until("${marker}_DONE")
            check(result.lines().any { it.trim() == "${marker}_LAUNCH:0" }) { "android_bootstrap_launch_failed" }
        } finally { deadline.cancel(false); work.release(owned) }
    }
}
