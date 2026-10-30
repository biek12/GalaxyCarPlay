package com.ecarx.autoservice.platform

import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.shell.AdbShellPacket
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object AdbShellExecutor {
    fun execute(connection: Kadb, command: String, timeout: Int): JSONObject {
        val timedOut = AtomicBoolean(false)
        return connection.openShell(command).use { stream ->
            val deadline = PlatformDeadlines.after(timeout.toLong()) { timedOut.set(true); runCatching { stream.close() } }
            val output = java.io.ByteArrayOutputStream()
            var code = -1
            var truncated = false
            try {
                while (!timedOut.get()) {
                    val packet = stream.read() ?: break
                    if (packet is AdbShellPacket.Exit) {
                        code = packet.payload.take(4).mapIndexed { index, byte -> (byte.toInt() and 255) shl (index * 8) }.fold(0) { a, b -> a or b }
                        break
                    }
                    val bytes = packet.payload
                    val count = minOf(bytes.size, 65536 - output.size())
                    output.write(bytes, 0, count); if (count < bytes.size) truncated = true
                }
            } catch (error: Exception) { if (!timedOut.get()) throw error }
            finally { deadline.cancel(false) }
            JSONObject().put("exitCode", code).put("timedOut", timedOut.get()).put("truncated", truncated).put("output", output.toString("UTF-8"))
        }
    }
}
