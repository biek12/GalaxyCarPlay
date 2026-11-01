package com.ecarx.autoservice.platform

import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 独立子进程、固定超时和有界输出；命令/输出仅回给授权调用方，不进入日志。 */
internal object ShellExecutor {
    fun execute(command: String, timeoutMillis: Int, shell: String = "/system/bin/sh"): JSONObject {
        require(command.length in 1..8192 && '\u0000' !in command)
        require(timeoutMillis in 100..30000)
        val process = ProcessBuilder(shell, "-c", command).redirectErrorStream(true).start()
        val captured = java.io.ByteArrayOutputStream()
        var truncated = false
        val drain = Thread {
            runCatching { process.inputStream.use { stream ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer); if (count < 0) break
                    synchronized(captured) {
                        val retained = minOf(count, 65536 - captured.size())
                        if (retained > 0) captured.write(buffer, 0, retained)
                        if (retained < count) truncated = true
                    }
                }
            } }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        if (!finished) { process.destroy(); if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly() }
        drain.join(300)
        runCatching { process.inputStream.close() }
        return synchronized(captured) { JSONObject().apply {
            put("exitCode", if (finished) process.exitValue() else -1); put("timedOut", !finished)
            put("output", captured.toString("UTF-8")); put("truncated", truncated)
        } }
    }
}
