package com.ecarx.autoservice.platform

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** 通过本App UID认证的RootService传输stdin/stdout，HAB设备始终由自有UID0子进程打开。 */
internal class RootHabProcess(context: Context, path: String) : java.lang.Process() {
    private val socket = RootServiceClient.connect(context)
    private val closed = AtomicBoolean()
    init {
        socket.soTimeout = 4000
        DataOutputStream(socket.outputStream).apply { writeInt(6); writeUTF(path); flush() }
        check(DataInputStream(socket.inputStream).readInt() == 0) { "root_hab_launch_failed" }
        socket.soTimeout = 0
    }
    override fun getOutputStream(): OutputStream = socket.outputStream
    override fun getInputStream(): InputStream = socket.inputStream
    override fun getErrorStream(): InputStream = java.io.ByteArrayInputStream(ByteArray(0))
    override fun isAlive() = !closed.get()
    override fun exitValue(): Int { if (isAlive) throw IllegalThreadStateException(); return 0 }
    override fun waitFor(): Int { while (isAlive) Thread.sleep(20); return 0 }
    override fun destroy() { if (closed.compareAndSet(false, true)) runCatching { socket.close() } }
    override fun destroyForcibly(): java.lang.Process { destroy(); return this }
}
