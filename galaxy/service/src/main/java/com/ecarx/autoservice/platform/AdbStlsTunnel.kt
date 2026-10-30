package com.ecarx.autoservice.platform

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Gates the actual KADB socket: the same upstream session must answer CNXN with STLS before TLS starts. */
internal class AdbStlsTunnel(private val endpoint: AdbEndpoint, private val work: ControlWork) : Closeable {
    private val server = ServerSocket()
    private val sockets = CopyOnWriteArrayList<Socket>()
    @Volatile private var closed = false
    @Volatile var failure: ControlFailure? = null
        private set
    val localPort: Int get() = server.localPort

    init {
        server.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
        work.own(this)
        thread(isDaemon = true, name = "adb-stls-gate") { acceptLoop() }
    }

    private fun acceptLoop() {
        repeat(MAX_ATTEMPTS) {
            if (closed || work.cancelled.get()) return
            try {
                val client = server.accept()
                sockets += client
                serve(client)
            } catch (_: SocketTimeoutException) {
                fail(ControlFailure.DISCONNECTED)
                return
            } catch (_: IOException) {
                if (!closed && !work.cancelled.get()) fail(ControlFailure.DISCONNECTED)
                return
            }
        }
    }

    private fun serve(client: Socket) {
        val upstream = Socket()
        sockets += upstream
        try {
            upstream.connect(java.net.InetSocketAddress(endpoint.host, endpoint.port), endpoint.connectTimeoutMs)
            upstream.soTimeout = endpoint.ioTimeoutMs
            client.soTimeout = endpoint.ioTimeoutMs
            val request = AdbPacket.read(client.getInputStream(), MAX_STARTUP_PAYLOAD, version = null)
            if (request.command != AdbPacket.CNXN || request.arg1 !in 1..AdbPacket.MAX_PEER_DATA) {
                fail(ControlFailure.PROTOCOL_ERROR)
                return
            }
            request.writeOriginalFrame(upstream.getOutputStream())
            val response = AdbPacket.read(upstream.getInputStream(), MAX_STARTUP_PAYLOAD, version = null)
            if (response.command != AdbPacket.STLS || response.arg0 != TLS_VERSION ||
                response.arg1 != 0 || response.payload.isNotEmpty()) {
                fail(ControlFailure.SECURE_ADB_REQUIRED)
                return
            }
            response.writeOriginalFrame(client.getOutputStream())
            relay(client, upstream)
        } catch (error: ControlTransportException) {
            if (!closed && !work.cancelled.get() && failure == null) fail(error.reason)
        } catch (_: IOException) {
            if (!closed && !work.cancelled.get() && failure == null) fail(ControlFailure.DISCONNECTED)
        } finally {
            closeSocket(client)
            closeSocket(upstream)
        }
    }

    private fun relay(client: Socket, upstream: Socket) {
        val done = CountDownLatch(2)
        pipe(client, upstream, done)
        pipe(upstream, client, done)
        try {
            while (!closed && !work.cancelled.get() && !done.await(RELAY_POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) Unit
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun pipe(source: Socket, target: Socket, done: CountDownLatch) = thread(isDaemon = true, name = "adb-stls-pipe") {
        try { source.getInputStream().copyTo(target.getOutputStream()) } catch (_: IOException) { }
        finally {
            runCatching { target.shutdownOutput() }
            done.countDown()
        }
    }

    private fun fail(reason: ControlFailure) { if (failure == null) failure = reason }

    private fun closeSocket(socket: Socket) {
        sockets.remove(socket)
        runCatching { socket.close() }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        sockets.toList().forEach(::closeSocket)
    }

    private companion object {
        const val TLS_VERSION = 0x01000000
        const val MAX_STARTUP_PAYLOAD = 1 shl 20
        const val MAX_ATTEMPTS = 5
        const val RELAY_POLL_MS = 100L
    }
}
