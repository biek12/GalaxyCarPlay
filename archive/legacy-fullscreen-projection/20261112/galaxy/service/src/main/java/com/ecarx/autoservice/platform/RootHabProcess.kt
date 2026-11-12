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
    private val ended = java.util.concurrent.CountDownLatch(1)
    private val status = object : java.io.FilterInputStream(socket.inputStream) {
        override fun read(): Int = super.read().also { if (it < 0) { ended.countDown(); runCatching { socket.close() } } }
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int = super.read(bytes, offset, count).also { if (it < 0) { ended.countDown(); runCatching { socket.close() } } }
    }
    init {
        try {
            socket.soTimeout = 4000
            DataOutputStream(socket.outputStream).apply { writeInt(6); writeUTF(path); flush() }
            check(DataInputStream(socket.inputStream).readInt() == 0) { "root_hab_launch_failed" }
            socket.soTimeout = 0
        } catch (error: Exception) { socket.close(); throw error }
    }
    override fun getOutputStream(): OutputStream = socket.outputStream
    override fun getInputStream(): InputStream = status
    override fun getErrorStream(): InputStream = java.io.ByteArrayInputStream(ByteArray(0))
    override fun isAlive() = ended.count > 0
    override fun exitValue(): Int { if (isAlive) throw IllegalThreadStateException(); return 0 }
    override fun waitFor(): Int { ended.await(); return 0 }
    override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = ended.await(timeout, unit)
    override fun destroy() {
        if (closed.compareAndSet(false, true)) runCatching { socket.shutdownOutput() }
        // EOF让Root端先结束并回收子进程，再关闭stdout；客户端仍读取最终状态/EOF。
    }
    override fun destroyForcibly(): java.lang.Process { destroy(); return this }
}
