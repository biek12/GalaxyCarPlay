package com.ecarx.autoservice.platform

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

internal data class AdbEndpoint(val host: String, val port: Int,
    val connectTimeoutMs: Int = 10_000, val ioTimeoutMs: Int = 15_000)

internal interface AdbEndpointResolver { fun resolve(work: ControlWork): AdbEndpoint }

internal class LocalAdbEndpointResolver(private val settings: AdbConnectionSettingsStore,
    private val onEvent: (String) -> Unit = {}) : AdbEndpointResolver {
    override fun resolve(work: ControlWork): AdbEndpoint {
        val saved = settings.read()
        if (!saved.valid()) throw ControlTransportException(ControlFailure.INVALID_REQUEST)
        onEvent("endpoint lookup started; host=${saved.host}; direct=${saved.directConnect}; configured_port=${saved.port}")
        if (saved.directConnect) {
            when (val probe = AdbTlsPortDiscovery.probe(saved.host, saved.port, work, DIRECT_PROBE_TIMEOUT_MS)) {
                AdbTlsProbe.SECURE -> return AdbEndpoint(saved.host, saved.port).also {
                    onEvent("configured endpoint is secure TLS ADB; port=${saved.port}")
                }
                AdbTlsProbe.NOT_SECURE -> {
                    onEvent("configured endpoint refused secure STLS; port=${saved.port}")
                    throw ControlTransportException(ControlFailure.SECURE_ADB_REQUIRED)
                }
                AdbTlsProbe.UNREACHABLE -> {
                    onEvent("configured endpoint unreachable; port=${saved.port}; result=$probe")
                    throw ControlTransportException(ControlFailure.DISCONNECTED)
                }
            }
        }
        AdbTlsPortDiscovery.activePort(saved.host, work)?.let {
            onEvent("TLS ADB property endpoint is reachable; port=$it")
            return AdbEndpoint(saved.host, it)
        }
        val port = AdbTlsPortDiscovery.discover(saved.host, DISCOVERY_TIMEOUT_MS, work)
            ?: throw ControlTransportException(ControlFailure.ADB_TLS_UNAVAILABLE)
        onEvent("TLS ADB endpoint discovered; port=$port")
        return AdbEndpoint(saved.host, port)
    }

    internal fun rediscover(endpoint: AdbEndpoint, work: ControlWork): AdbEndpoint {
        val port = AdbTlsPortDiscovery.discover(endpoint.host, RECOVERY_DISCOVERY_TIMEOUT_MS, work)
        return if (port == null) endpoint else endpoint.copy(port = port)
    }

    private companion object {
        const val DIRECT_PROBE_TIMEOUT_MS = 2_000
        const val DISCOVERY_TIMEOUT_MS = 12_000L
        const val RECOVERY_DISCOVERY_TIMEOUT_MS = 15_000L
    }
}

/** Mirrors EVCC's port-only CNXN/STLS probe; KADB owns every authenticated ADB session. */
internal object AdbTlsPortDiscovery {
    private const val PRE_CONNECT_MAXDATA = 4096
    private const val MAX_CANDIDATE_PORTS = 64

    fun discover(host: String, timeoutMs: Long, work: ControlWork): Int? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var pause = 500L
        while (SystemClock.elapsedRealtime() < deadline) {
            work.checkActive()
            work.pause(pause)
            work.checkActive()
            val port = propertyPort(work)
            if (port != null && port > 0 && probe(host, port, work, deadline) == AdbTlsProbe.SECURE) return port
            for (port in listenerPorts()) {
                work.checkActive()
                if (SystemClock.elapsedRealtime() >= deadline) break
                if (probe(host, port, work, deadline) == AdbTlsProbe.SECURE) return port
            }
            pause = (pause * 2).coerceAtMost(2_000L)
        }
        return null
    }

    internal fun listenerPorts(lines: Sequence<String>): List<Int> = lines.mapNotNull { line ->
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.size < 4 || columns[3] != "0A") return@mapNotNull null
        columns[1].substringAfterLast(':', "").toIntOrNull(16)?.takeIf { it > 10_000 }
        }.distinct().sorted().take(MAX_CANDIDATE_PORTS).toList()

    private fun listenerPorts(): List<Int> = sequenceOf("/proc/net/tcp", "/proc/net/tcp6")
        .flatMap { path -> runCatching { File(path).readLines().asSequence() }.getOrElse { emptySequence() } }
        .let(::listenerPorts)

    private fun propertyPort(work: ControlWork): Int? {
        val reflected = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
                .invoke(null, "service.adb.tls.port", "") as String
        }.getOrNull()?.toIntOrNull()
        if (reflected != null) return reflected
        val process = runCatching { Runtime.getRuntime().exec(arrayOf("getprop", "service.adb.tls.port")) }
            .getOrNull() ?: return null
        val owner = java.io.Closeable { process.destroyForcibly() }
        work.own(owner)
        return try {
            if (!process.waitFor(PROPERTY_COMMAND_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) return null
            process.inputStream.bufferedReader().use { it.readText().trim().toIntOrNull() }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.IOException("cancelled", error)
        } finally {
            work.release(owner)
        }
    }

    fun activePort(host: String, work: ControlWork): Int? {
        val port = propertyPort(work)?.takeIf { it > 0 } ?: return null
        val socket = Socket()
        return try {
            work.own(socket)
            socket.connect(InetSocketAddress(host, port), ACTIVE_PORT_TIMEOUT_MS)
            port
        } catch (_: java.io.IOException) {
            null
        } finally {
            work.release(socket)
        }
    }

    internal fun probe(host: String, port: Int, work: ControlWork, timeoutMs: Int): AdbTlsProbe =
        probe(host, port, work, SystemClock.elapsedRealtime() + timeoutMs)

    private fun probe(host: String, port: Int, work: ControlWork, deadline: Long): AdbTlsProbe {
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
            if (readCommand(socket.getInputStream()) == AdbPacket.STLS) AdbTlsProbe.SECURE
            else AdbTlsProbe.NOT_SECURE
        } catch (_: SocketTimeoutException) {
            if (work.cancelled.get()) throw java.io.IOException("cancelled")
            if (connected) AdbTlsProbe.NOT_SECURE else AdbTlsProbe.UNREACHABLE
        } catch (_: java.io.IOException) {
            if (work.cancelled.get()) throw java.io.IOException("cancelled")
            if (connected) AdbTlsProbe.NOT_SECURE else AdbTlsProbe.UNREACHABLE
        } catch (_: Exception) {
            if (connected) AdbTlsProbe.NOT_SECURE else AdbTlsProbe.UNREACHABLE
        } finally {
            work.release(socket)
        }
    }

    private fun remainingTimeout(deadline: Long, ceiling: Int): Int =
        (deadline - SystemClock.elapsedRealtime()).coerceIn(1L, ceiling.toLong()).toInt()

    /** EVCC gu4.l1 reads only the first little-endian command word of the STLS response. */
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

    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val RESPONSE_TIMEOUT_MS = 3_000
    private const val ACTIVE_PORT_TIMEOUT_MS = 1_000
    private const val PROPERTY_COMMAND_TIMEOUT_MS = 500L
}

internal enum class AdbTlsProbe { SECURE, NOT_SECURE, UNREACHABLE }

internal class LocalAdbEndpointSettings(context: Context) : AdbConnectionSettingsStore {
    private val preferences = context.applicationContext.getSharedPreferences("platform", Context.MODE_PRIVATE)

    override fun read() = AdbConnectionSettings(
        preferences.getString(KEY_HOST, "127.0.0.1") ?: "127.0.0.1",
        preferences.getInt(KEY_PORT, 5555), preferences.getBoolean(KEY_DIRECT, false))

    override fun save(settings: AdbConnectionSettings): Boolean {
        if (!settings.valid()) return false
        preferences.edit().putString(KEY_HOST, settings.host.trim()).putInt(KEY_PORT, settings.port)
            .putBoolean(KEY_DIRECT, settings.directConnect).apply()
        return true
    }

    private companion object {
        const val PREFERENCES = "adb_connection_settings"
        const val KEY_HOST = "adbHost"
        const val KEY_PORT = "adbPort"
        const val KEY_DIRECT = "adbDirect"
    }
}
