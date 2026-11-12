package com.ecarx.autoservice.platform.adb

import android.os.SystemClock
import com.ecarx.autoservice.platform.root.ControlWork
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 只探测 TLS 端口的 CNXN/STLS。已认证会话仍由 KADB 持有。
 *
 * 套接字和 getprop 进程登记在 [ControlWork] 上，探测结束或取消时由 work 释放。
 * 调用线程阻塞到给定截止时间。
 */
internal object AdbTlsPortDiscovery {
    private const val PRE_CONNECT_MAXDATA = 4096
    private const val MAX_CANDIDATE_PORTS = 64
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val RESPONSE_TIMEOUT_MS = 3_000
    private const val ACTIVE_PORT_TIMEOUT_MS = 1_000
    private const val PROPERTY_COMMAND_TIMEOUT_MS = 500L

    /**
     * 在截止时间前查找一个返回 STLS 的端口。
     * 先看属性端口，再看监听表。没有找到时返回 null，不抛出“不可用”。
     */
    fun discover(
        host: String,
        timeoutMs: Long,
        work: ControlWork,
    ): Int? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var pause = 500L
        while (SystemClock.elapsedRealtime() < deadline) {
            work.checkActive()
            work.pause(pause)
            work.checkActive()
            val property = propertyPort(work)
            if (property != null &&
                property > 0 &&
                probe(host, property, work, deadline) == AdbTlsProbe.SECURE
            ) {
                return property
            }
            for (candidate in listenerPorts()) {
                work.checkActive()
                if (SystemClock.elapsedRealtime() >= deadline) break
                if (probe(host, candidate, work, deadline) == AdbTlsProbe.SECURE) return candidate
            }
            pause = (pause * 2).coerceAtMost(2_000L)
        }
        return null
    }

    /**
     * 从 `/proc/net/tcp` 文本选出状态为 `0A`、端口大于 10000 的候选。
     * 去重、排序后最多 64 个。不读文件，也不打开套接字。
     */
    internal fun listenerPorts(lines: Sequence<String>): List<Int> =
        lines
            .mapNotNull { line ->
                val columns = line.trim().split(Regex("\\s+"))
                if (columns.size < 4 || columns[3] != "0A") return@mapNotNull null
                columns[1].substringAfterLast(':', "").toIntOrNull(16)?.takeIf { it > 10_000 }
            }.distinct()
            .sorted()
            .take(MAX_CANDIDATE_PORTS)
            .toList()

    /** 读取本机 tcp/tcp6 监听表。文件不存在时跳过该表。 */
    private fun listenerPorts(): List<Int> =
        sequenceOf("/proc/net/tcp", "/proc/net/tcp6")
            .flatMap { path ->
                runCatching { File(path).readLines().asSequence() }.getOrElse { emptySequence() }
            }.let(::listenerPorts)

    /**
     * 读取 `service.adb.tls.port`。先反射，失败再执行 getprop。
     * getprop 进程登记在 [work] 上，返回前释放。取消时恢复中断并抛出 `cancelled`。
     */
    private fun propertyPort(work: ControlWork): Int? {
        val reflected =
            runCatching {
                Class
                    .forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java, String::class.java)
                    .invoke(null, "service.adb.tls.port", "") as String
            }.getOrNull()
                ?.toIntOrNull()
        if (reflected != null) return reflected
        val process =
            runCatching { Runtime.getRuntime().exec(arrayOf("getprop", "service.adb.tls.port")) }
                .getOrNull() ?: return null
        val owner = Closeable { process.destroyForcibly() }
        work.own(owner)
        return try {
            if (!process.waitFor(PROPERTY_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
            process.inputStream.bufferedReader().use { it.readText().trim().toIntOrNull() }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("cancelled", error)
        } finally {
            work.release(owner)
        }
    }

    /**
     * 属性端口能完成 TCP 连接时返回该端口。不检查 STLS。
     * 套接字登记在 [work] 上，返回前释放。
     */
    fun activePort(
        host: String,
        work: ControlWork,
    ): Int? {
        val port = propertyPort(work)?.takeIf { it > 0 } ?: return null
        val socket = Socket()
        return try {
            work.own(socket)
            socket.connect(InetSocketAddress(host, port), ACTIVE_PORT_TIMEOUT_MS)
            port
        } catch (_: IOException) {
            null
        } finally {
            work.release(socket)
        }
    }

    /**
     * 在给定超时内做一次 CNXN/STLS 探测。截止时间从调用时刻起算。
     */
    internal fun probe(
        host: String,
        port: Int,
        work: ControlWork,
        timeoutMs: Int,
    ): AdbTlsProbe = probe(host, port, work, SystemClock.elapsedRealtime() + timeoutMs)

    /**
     * 向端口发送原始 CNXN，并只看回复的第一个小端命令字。
     * 套接字由 [work] 释放。取消时抛出 `cancelled`；其它已连接失败视为非 STLS。
     */
    private fun probe(
        host: String,
        port: Int,
        work: ControlWork,
        deadline: Long,
    ): AdbTlsProbe {
        work.checkActive()
        val socket = Socket()
        var connected = false
        return try {
            work.own(socket)
            socket.connect(InetSocketAddress(host, port), remainingTimeout(deadline, CONNECT_TIMEOUT_MS))
            connected = true
            socket.soTimeout = remainingTimeout(deadline, RESPONSE_TIMEOUT_MS)
            val banner = "host::\u0000".toByteArray(Charsets.UTF_8)
            AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION_ORIGINAL, PRE_CONNECT_MAXDATA, banner)
                .write(socket.getOutputStream(), AdbPacket.VERSION_ORIGINAL)
            if (readCommand(socket.getInputStream()) == AdbPacket.STLS) {
                AdbTlsProbe.SECURE
            } else {
                AdbTlsProbe.NOT_SECURE
            }
        } catch (_: SocketTimeoutException) {
            if (work.cancelled.get()) throw IOException("cancelled")
            if (connected) AdbTlsProbe.NOT_SECURE else AdbTlsProbe.UNREACHABLE
        } catch (_: IOException) {
            if (work.cancelled.get()) throw IOException("cancelled")
            if (connected) AdbTlsProbe.NOT_SECURE else AdbTlsProbe.UNREACHABLE
        } catch (_: Exception) {
            if (connected) AdbTlsProbe.NOT_SECURE else AdbTlsProbe.UNREACHABLE
        } finally {
            work.release(socket)
        }
    }

    /** 剩余时间夹在 1 毫秒和 [ceiling] 之间，避免把 0 传给套接字。 */
    private fun remainingTimeout(
        deadline: Long,
        ceiling: Int,
    ): Int = (deadline - SystemClock.elapsedRealtime()).coerceIn(1L, ceiling.toLong()).toInt()

    /**
     * 只读回复的前 4 字节，按小端解释为命令字。EVCC gu4.l1 同样只看这一个字。
     * 流提前结束时返回 null。不读取后续载荷，套接字仍由调用方关闭。
     */
    private fun readCommand(input: InputStream): Int? {
        val bytes = ByteArray(4)
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) return null
            if (count > 0) offset += count
        }
        return (bytes[0].toInt() and 0xff) or
            ((bytes[1].toInt() and 0xff) shl 8) or
            ((bytes[2].toInt() and 0xff) shl 16) or
            ((bytes[3].toInt() and 0xff) shl 24)
    }
}
