package com.ecarx.autoservice.projection

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** 每代输出一个自有子进程；单worker调用write/finish，主线程cancel不等待管道或驱动。 */
internal class GalaxyHabProcess(context: Context, private val report: (String) -> Unit,
    launch: (String) -> Process = { com.ecarx.autoservice.platform.RootHabProcess(context, it) },
) : Closeable {
    private val closed = AtomicBoolean()
    private val child = launch(File(context.applicationInfo.nativeLibraryDir,
        "libgalaxy_hab_bridge.so").absolutePath)
    private val output = BufferedOutputStream(child.outputStream, 64 * 1024)
    private val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)

    init {
        Thread({
            runCatching { child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { if (!closed.get()) report(it.take(256)) }
            } }
        }, "GalaxyPlay-hab-status").apply { isDaemon = true; start() }
        try {
            val key = GalaxyHabKey.load(context)
            try { output.write(key); output.flush() } finally { key.fill(0) }
        } catch (error: Exception) { close(); throw error }
    }

    fun frame(pixels: ByteArray?) {
        if (closed.get()) return
        header.clear()
        val length = pixels?.size ?: -1
        header.putInt(length).putInt(GalaxyHabLayout.WIDTH * 4).putInt(4).putInt(0)
        output.write(header.array())
        if (pixels != null) output.write(pixels)
        output.flush()
    }

    /** 先发STOP，随后释放pipe；close的SIGTERM只负责解开可能阻塞的本代写操作。 */
    fun finish() {
        runCatching { output.write(ByteArray(16)); output.flush() }
        runCatching { output.close() }
        close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        child.destroy()
        Handler(Looper.getMainLooper()).postDelayed({ if (child.isAlive) child.destroyForcibly() }, 2000)
    }
}
