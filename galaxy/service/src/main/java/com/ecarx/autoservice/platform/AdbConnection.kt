package com.ecarx.autoservice.platform

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.shell.AdbShellPacket
import java.io.Closeable

/** 沿星枢真实连接的CNXN/STLS校验；每次adbd重启重新解析端点，I/O归属于可取消操作。 */
internal class AdbConnection(
    private val context: Context,
    private val work: ControlWork,
    private val resolver: AdbEndpointResolver,
) : AdbSession {
    private var client: Kadb? = null
    private var owner: Closeable? = null
    var connectedPort: Int? = null
        private set
    override fun connect() {
        if (client != null) return
        AdbIdentity.configure(context)
        repeat(2) { attempt ->
            work.checkActive()
            val endpoint = resolver.resolve(work)
            val gate = AdbStlsTunnel(endpoint, work)
            try {
                val next = Kadb.create("127.0.0.1", gate.localPort, endpoint.connectTimeoutMs, endpoint.ioTimeoutMs)
                owner = Closeable { runCatching { next.close() }; work.release(gate) }.also(work::own)
                check(next.supportsFeature("shell_v2")) { "shell_v2_unavailable" }
                client = next
                connectedPort = endpoint.port
                return
            } catch (error: Exception) {
                val failure = gate.failure
                close(); work.release(gate); work.checkActive()
                if (attempt == 0 && failure != ControlFailure.SECURE_ADB_REQUIRED && recoverAuthorization()) {
                    work.pause(3000) // adbd可能更换TLS端口；下一次循环重新解析，不能沿用旧endpoint。
                } else throw ControlTransportException(failure ?: ControlFailure.TRANSPORT_ERROR)
            }
        }
        throw ControlTransportException(ControlFailure.TRANSPORT_ERROR)
    }
    private fun recoverAuthorization(): Boolean = runCatching {
        work.checkActive()
        if (!RootServiceClient.healthy(context)) return@runCatching false
        val key = java.io.File(context.noBackupFilesDir, "adb/client.pub").readText().trim()
        work.checkActive()
        val reply = RootServiceClient.request(context, 7, org.json.JSONObject().put("publicKey", key).put("forceReload", true), 10000)
        reply.optString("state") in setOf("authorized", "already_authorized")
    }.getOrDefault(false)
    override fun shell(command: String, limit: Int): ShellV2Output {
        connect()
        val stdout = java.io.ByteArrayOutputStream()
        val stderr = java.io.ByteArrayOutputStream()
        var exit: Int? = null
        val stream = client!!.openShell(command)
        val streamOwner = Closeable { stream.close() }.also(work::own)
        try {
            while (true) {
                work.checkActive()
                when (val packet = stream.read() ?: break) {
                    is AdbShellPacket.StdOut -> stdout.write(packet.payload, 0, minOf(packet.payload.size, (limit - stdout.size()).coerceAtLeast(0)))
                    is AdbShellPacket.StdError -> stderr.write(packet.payload, 0, minOf(packet.payload.size, (limit - stderr.size()).coerceAtLeast(0)))
                    is AdbShellPacket.Exit -> { exit = packet.payload.single().toInt() and 255; break }
                }
            }
            return ShellV2Output(stdout.toString("UTF-8"), stderr.toString("UTF-8"), exit)
        } finally { work.release(streamOwner) }
    }
    fun execute(command: String, timeout: Int): org.json.JSONObject { connect(); return AdbShellExecutor.execute(client!!, command, timeout) }
    override fun root(): String { connect(); return client!!.root() }
    override fun close() { owner?.let(work::release); owner = null; client = null; connectedPort = null }
}
