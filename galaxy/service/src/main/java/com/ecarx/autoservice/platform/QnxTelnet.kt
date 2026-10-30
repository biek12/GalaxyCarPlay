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
    // 原厂工作流经echo向QNX发送LF；直接Socket不再经busybox telnet转换换行。
    private fun send(value: String) { socket.getOutputStream().write((value + "\n").toByteArray(Charsets.UTF_8)); socket.getOutputStream().flush() }
    private fun readByte(): Int {
        val input = socket.getInputStream()
        while (true) {
            val value = input.read(); check(value >= 0) { "qnx_connection_closed" }
            if (value != 255) return value
            val operation = input.read(); check(operation >= 0)
            if (operation == 255) return 255
            if (operation in 251..254) {
                val option = input.read(); check(option >= 0)
                // 与星枢TelnetWire一致：WONT/DONT是终止协商的否定应答，不能再应答造成循环。
                if (operation == 251 || operation == 253) {
                    // 与本车已跑通的Channel一致：接受服务端ECHO/SGA，不启用本端终端选项。
                    val reply = if (operation == 251) { if (option == 1 || option == 3) 253 else 254 } else 252
                    socket.getOutputStream().write(byteArrayOf(255.toByte(), reply.toByte(), option.toByte()))
                }
            } else if (operation == 250) {
                var previous = 0
                do { val next = input.read(); check(next >= 0); if (previous == 255 && next == 240) break; previous = next } while (true)
            }
        }
    }
    private fun await(pattern: Regex, prompt: Boolean = false, diagnosticMarker: String? = null): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 262144) {
            val value = try { readByte() } catch (error: java.net.SocketTimeoutException) {
                val text = bytes.toString("UTF-8").replace("\r", "")
                com.ecarx.autoservice.ServiceTrace.event("qnx_prompt_timeout bytes=${bytes.size()} login=${text.contains("login", true)} password=${text.contains("password", true)} shell=${Regex("(?m)[#$] ?$").containsMatchIn(text)}")
                if (diagnosticMarker != null) {
                    val found = Regex("${Regex.escape(diagnosticMarker)}:[0-9]+", RegexOption.IGNORE_CASE).find(text)
                    val before = found?.range?.first?.let { text.getOrNull(it - 1)?.code } ?: -1
                    val after = found?.range?.last?.let { text.getOrNull(it + 1)?.code } ?: -1
                    val position = text.lastIndexOf(diagnosticMarker, ignoreCase = true)
                    val suffix = if (position >= 0) text.drop(position + diagnosticMarker.length).take(12).map { it.code }.joinToString(",") else "absent"
                    com.ecarx.autoservice.ServiceTrace.event("qnx_command_frame marker=${found != null} before=$before after=$after suffix=$suffix qnx=${text.lineSequence().any { it.trim() == "QNX" }} syntax=${text.contains("syntax", true)} missing=${text.contains("not found", true)}")
                }
                throw error
            }
            bytes.write(value)
            if ((prompt && bytes.size() <= 8192) || value == '\n'.code || value == ':'.code || value == '#'.code || value == '$'.code) {
                val text = bytes.toString("UTF-8").replace("\r", "")
                if (pattern.containsMatchIn(text)) return text
            }
        }
        error("qnx_response_limit")
    }
    fun login(user: String, password: String) {
        require(user.length in 1..64 && password.length in 1..256 && (user + password).none { it == '\r' || it == '\n' || it == '\u0000' })
        com.ecarx.autoservice.ServiceTrace.event("qnx_wait_login")
        await(Regex("login:", RegexOption.IGNORE_CASE), prompt = true); send(user)
        com.ecarx.autoservice.ServiceTrace.event("qnx_wait_password")
        // 本车提示包含password后的说明文字；沿已跑通脚本按password词识别，不要求紧邻冒号。
        await(Regex("password", RegexOption.IGNORE_CASE), prompt = true); send(password)
        com.ecarx.autoservice.ServiceTrace.event("qnx_wait_shell")
        await(Regex("(?m)(?:^|\\n)[^\\n]{0,80}[#$] ?$"), prompt = true)
        // 本车id程序实际返回126；使用接收端生命周期已在用的原生pidin。
        // QNX文档user视图提供real/effective/saved UID，不用账号名或#提示代验UID0。
        val identity = execute("uname -s; pidin -p \$\$ user")
        val lines = identity.lineSequence().map { it.trim() }.toList()
        val credentials = lines.map { it.split(Regex("\\s+")) }.firstOrNull {
            it.size >= 8 && it[0].toIntOrNull() != null && it.drop(2).take(6).all { number -> number.toIntOrNull() != null }
        }
        check("QNX" in lines && credentials != null && credentials[2] == "0" && credentials[4] == "0") { "qnx_root_identity_rejected" }
        com.ecarx.autoservice.ServiceTrace.event("qnx_uid0_verified")
    }
    fun execute(command: String): String {
        require(command.length <= 8192 && '\n' !in command && '\r' !in command)
        val marker = "as_" + UUID.randomUUID().toString().replace("-", "")
        send("( $command ); echo $marker:\$?")
        val text = await(Regex("(?:^|\\n)$marker:([0-9]+)\\n", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)), diagnosticMarker = marker)
        val code = Regex("(?:^|\\n)$marker:([0-9]+)\\n", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).find(text)!!.groupValues[1].toInt()
        if (code != 0) com.ecarx.autoservice.ServiceTrace.event("qnx_command_exit=$code")
        check(code == 0) { "qnx_command_failed" }
        return text
    }
    override fun close() { runCatching { socket.close() }; deadline?.cancel(false) }
}
