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
    launch: (String) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
) : Closeable {
    private val closed = AtomicBoolean()
    private val ready = java.util.concurrent.CountDownLatch(1)
    private val statusEnded = java.util.concurrent.CountDownLatch(1)
    @Volatile private var handshakeReady = false
    @Volatile private var senderExited = false
    @Volatile private var startupFailure = "hab_handshake_failed"
    private val child = launch(File(context.applicationInfo.nativeLibraryDir,
        "libgalaxy_hab_bridge.so").absolutePath)
    private val output = BufferedOutputStream(child.outputStream, 64 * 1024)
    private val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
    private val frameMonitor=Object()
    private var nextTicket=0L
    @Volatile private var postedTicket=0L

    init {
        Thread({
            try {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if(line.startsWith("HAB: frame-posted ticket=")){
                            val value=line.substringAfter("ticket=").substringBefore(' ').toLongOrNull()
                            if(value!=null)synchronized(frameMonitor){postedTicket=value;frameMonitor.notifyAll()}
                            return@forEach // 每帧完成令牌只供流控，不写Android日志。
                        }
                        if (line == READY_LINE) { handshakeReady = true; ready.countDown() }
                        if (line.startsWith("HAB: closed ")) { senderExited = true; startupFailure = "hab_sender_closed"; ready.countDown() }
                        // native输出只有状态；不回传任意子进程正文。
                        if (!closed.get() && line.startsWith("HAB: ")) report(line.take(256))
                    }
                }
            } catch (error: Exception) {
                // 主动关闭是正常退出；运行中读端失败保留类型/源码栈，绝不记录子进程任意正文。
                if (!closed.get()) com.ecarx.autoservice.ServiceTrace.event("hab_status_reader_failed", error)
            }
            finally { statusEnded.countDown(); ready.countDown();synchronized(frameMonitor){frameMonitor.notifyAll()} }
        }, "GalaxyPlay-hab-status").apply { isDaemon = true; start() }
        try {
            val key = GalaxyHabKey.load(context)
            try { output.write(key); output.flush() } finally { key.fill(0) }
        } catch (error: Exception) { abortAndWait(); throw error }
    }

    /** 必须等AUTH/INIT/ACK完成后的native READY；成功保留当前子进程和VC送帧。 */
    fun awaitReady(timeoutMillis: Long = 12000) {
        check(ready.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) { "hab_handshake_timeout" }
        check(handshakeReady && !senderExited && statusEnded.count > 0 && !closed.get()) { startupFailure }
    }

    /** worker-only：取消失败尝试并确认退出，避免下一次HAB open与旧owner重叠。 */
    fun abortAndWait() {
        close()
        if (!child.waitFor(2200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            child.destroyForcibly()
            if (!child.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) throw GalaxyHabOwnerBusy()
        }
    }

    fun frame(pixels: ByteArray?,timeoutMillis: Long=2000) {
        check(!closed.get() && !senderExited && statusEnded.count > 0) { "hab_sender_closed" }
        require(timeoutMillis>0)
        nextTicket=if(nextTicket>=0xFFFFFFFFL)1 else nextTicket+1
        val ticket=nextTicket
        val deadline=System.nanoTime()+timeoutMillis*1_000_000
        val main=Handler(Looper.getMainLooper())
        val watchdog=Runnable {if(postedTicket!=ticket&&!closed.get())close()}
        main.postDelayed(watchdog,timeoutMillis)
        header.clear()
        val length = pixels?.size ?: -1
        header.putInt(length).putInt(GalaxyHabLayout.WIDTH * 4).putInt(4).putInt(ticket.toInt())
        try {
            output.write(header.array())
            if (pixels != null) output.write(pixels)
            output.flush()
            synchronized(frameMonitor){
                while(postedTicket!=ticket&&!closed.get()&&!senderExited&&statusEnded.count>0){
                    val remaining=deadline-System.nanoTime()
                    if(remaining<=0){close();error("hab_frame_post_timeout")}
                    frameMonitor.wait(((remaining+999999)/1000000).coerceAtLeast(1))
                }
            }
            check(postedTicket==ticket&&!closed.get()){ "hab_frame_post_unavailable" }
        } finally { main.removeCallbacks(watchdog) }
    }

    /** 先发STOP，随后释放pipe；close的SIGTERM只负责解开可能阻塞的本代写操作。 */
    fun finish() {
        runCatching { output.write(ByteArray(16)); output.flush() }
        runCatching { output.close() }
        close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready.countDown()
        synchronized(frameMonitor){frameMonitor.notifyAll()}
        child.destroy()
        Handler(Looper.getMainLooper()).postDelayed({ if (child.isAlive) child.destroyForcibly() }, 2000)
    }
    private companion object { const val READY_LINE = "HAB: ready display=2 size=1920x532 slots=6" }
}
