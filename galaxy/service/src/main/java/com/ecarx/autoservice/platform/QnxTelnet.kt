package com.ecarx.autoservice.platform

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 仅内网显式地址；认证和终端回显留在进程内，错误只报告阶段码。 */
internal class QnxTelnet(host: String, port: Int = 23) : Closeable {
    private val socket = Socket()
    private var deadline: java.util.concurrent.ScheduledFuture<*>? = null
    init {
        require(host == "192.168.118.2" || host == "127.0.0.1")
        try { socket.connect(InetSocketAddress(host, port), 4000); socket.soTimeout = 5000 }
        catch (error: Exception) { socket.close(); throw error }
        deadline = PlatformDeadlines.after(45000) { runCatching { socket.close() } }
    }
    private fun send(value: String) { socket.getOutputStream().write((value + "\r\n").toByteArray(Charsets.UTF_8)); socket.getOutputStream().flush() }
    private fun readByte(): Int {
        val input = socket.getInputStream()
        while (true) {
            val value = input.read(); check(value >= 0) { "qnx_connection_closed" }
            if (value != 255) return value
            val operation = input.read(); check(operation >= 0)
            if (operation == 255) return 255
            if (operation in 251..254) {
                val option = input.read(); check(option >= 0)
                val reply = if (operation == 251 || operation == 252) 254 else 252
                socket.getOutputStream().write(byteArrayOf(255.toByte(), reply.toByte(), option.toByte()))
            } else if (operation == 250) {
                var previous = 0
                do { val next = input.read(); check(next >= 0); if (previous == 255 && next == 240) break; previous = next } while (true)
            }
        }
    }
    private fun await(pattern: Regex): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 262144) {
            val value = readByte()
            bytes.write(value)
            if (value == '\n'.code || value == ':'.code || value == '#'.code || value == '$'.code) {
                val text = bytes.toString("UTF-8")
                if (pattern.containsMatchIn(text)) return text
            }
        }
        error("qnx_response_limit")
    }
    fun login(user: String, password: String) {
        require(user.length in 1..64 && password.length in 1..256 && (user + password).none { it == '\r' || it == '\n' || it == '\u0000' })
        await(Regex("login:", RegexOption.IGNORE_CASE)); send(user)
        await(Regex("password:", RegexOption.IGNORE_CASE)); send(password)
        await(Regex("(?m)(?:^|\\n)[^\\n]{0,80}[#$] ?$"))
        val identity = execute("uname -s; id -u")
        val lines = identity.lineSequence().map { it.trim() }.toList()
        check("QNX" in lines && "0" in lines) { "qnx_root_identity_rejected" }
    }
    fun execute(command: String): String {
        require(command.length <= 8192 && '\n' !in command && '\r' !in command)
        val marker = "AS_" + UUID.randomUUID().toString().replace("-", "")
        send("( $command ); _as_rc=\$?; printf '\\n$marker:%s\\n' \"\$_as_rc\"")
        val text = await(Regex("\\n$marker:([0-9]+)\\r?\\n"))
        check(Regex("\\n$marker:0\\r?\\n").containsMatchIn(text)) { "qnx_command_failed" }
        return text
    }
    override fun close() { runCatching { socket.close() }; deadline?.cancel(false) }
}
