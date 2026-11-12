package com.ecarx.autoservice.projection

import android.os.Looper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GalaxyHabProcessTest {
    @Test fun applicationKeyIsStableAndStopIsDistinctFromIdleHeartbeat() {
        val context = RuntimeEnvironment.getApplication()
        val child = Child()
        val process = GalaxyHabProcess(context, {}, { child })
        val key = child.input.toByteArray()
        assertEquals(32, key.size)
        assertArrayEquals(GalaxyHabKey.load(context), key)
        process.awaitReady(1000)
        process.frame(null)
        process.finish()
        val bytes = child.input.toByteArray()
        assertEquals(64, bytes.size)
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), bytes.copyOfRange(32, 36))
        assertTrue(bytes.copyOfRange(48, 64).all { it == 0.toByte() })
        assertEquals(1, child.signalled)
        process.close()
        assertEquals(1, child.signalled)
        child.destroyForcibly()
    }
    @Test fun blockedChildIsForciblyReapedWithoutWaitingOnTheCallerThread() {
        val child = Child()
        val process = GalaxyHabProcess(RuntimeEnvironment.getApplication(), {}, { child })
        process.close()
        assertEquals(0, child.forced)
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(1, child.forced)
    }
    @Test fun launchAndWritablePipeAreNotReceiverReadyAndCancellationWakesWaiter() {
        val child = Child(emitReady = false)
        val process = GalaxyHabProcess(RuntimeEnvironment.getApplication(), {}, { child })
        try { process.awaitReady(20); fail("pipe alone cannot prove handshake") }
        catch (error: IllegalStateException) { assertEquals("hab_handshake_timeout", error.message) }
        process.close()
        try { process.awaitReady(1000); fail("cancelled sender cannot report ready") }
        catch (_: IllegalStateException) { }
        child.destroyForcibly()
    }
    @Test fun readySessionIsRetainedForFirstFrameAndEofInvalidatesIt() {
        val child = Child()
        var launched = 0
        val exited = java.util.concurrent.CountDownLatch(1)
        val process = GalaxyHabProcess(RuntimeEnvironment.getApplication(), { line -> if (line.startsWith("HAB: closed ")) exited.countDown() }, { ++launched; child })
        process.awaitReady(1000)
        process.frame(byteArrayOf(1, 2, 3, 4))
        assertEquals(1, launched)
        assertEquals(52, child.input.size())
        child.endOutput()
        assertTrue(exited.await(1000, TimeUnit.MILLISECONDS))
        try { process.frame(null); fail("dead child cannot accept frames") } catch (_: IllegalStateException) { }
        process.close(); child.destroyForcibly()
    }
    @Test fun writablePipeDoesNotAcknowledgeQnxConsumptionAndSlowPeerDoesNotQueueAnotherFrame() {
        val child=Child(emitPosted=false)
        val process=GalaxyHabProcess(RuntimeEnvironment.getApplication(),{}, {child})
        process.awaitReady(1000)
        val done=java.util.concurrent.CountDownLatch(1)
        var failed: Throwable?=null
        val sender=Thread {try{process.frame(byteArrayOf(1,2,3,4))}catch(error:Throwable){failed=error}finally{done.countDown()}}
        sender.start();assertTrue(child.frameRead.await(1000,TimeUnit.MILLISECONDS))
        assertEquals(1L,done.count)
        child.posted(1)
        assertTrue(done.await(1000,TimeUnit.MILLISECONDS));assertNull(failed)
        process.close();child.destroyForcibly()
    }
    @Test fun missingPostConfirmationTimesOutAndClosesOnlyThisChild() {
        val child=Child(emitPosted=false)
        val process=GalaxyHabProcess(RuntimeEnvironment.getApplication(),{}, {child})
        process.awaitReady(1000)
        try{process.frame(null,20);fail("pipe写入不代表接收端已经post")}
        catch(error:IllegalStateException){assertEquals("hab_frame_post_timeout",error.message)}
        assertEquals(1,child.signalled)
        child.destroyForcibly()
    }
    private class Child(emitReady: Boolean = true,private val emitPosted: Boolean=true) : Process() {
        private val status = java.io.PipedInputStream()
        private val writer = java.io.PipedOutputStream(status)
        val frameRead=java.util.concurrent.CountDownLatch(1)
        private var consumed=32
        val input = object: ByteArrayOutputStream(){
            override fun flush(){
                val bytes=toByteArray()
                while(bytes.size>=consumed+16){
                    val header=java.nio.ByteBuffer.wrap(bytes,consumed,16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val size=header.int
                    val payload=if(size>0)size else 0
                    if(bytes.size<consumed+16+payload)return
                    header.int;header.int;val ticket=header.int.toLong() and 0xFFFFFFFFL
                    consumed+=16+payload
                    if(size!=0){frameRead.countDown();if(emitPosted)posted(ticket)}
                }
            }
        }
        var signalled = 0
        var forced = 0
        init { if (emitReady) { writer.write("HAB: ready display=2 size=1920x532 slots=6\n".toByteArray()); writer.flush() } }
        fun endOutput() { writer.write("HAB: closed result=0 cleanup=0\n".toByteArray()); writer.flush(); writer.close() }
        fun posted(ticket: Long){writer.write("HAB: frame-posted ticket=$ticket sequence=1\n".toByteArray());writer.flush()}
        override fun getOutputStream() = input
        override fun getInputStream() = status
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = forced > 0
        override fun exitValue(): Int = if (forced == 0) throw IllegalThreadStateException() else 0
        override fun isAlive() = forced == 0
        override fun destroy() { ++signalled }
        override fun destroyForcibly(): Process { ++forced; writer.close(); return this }
    }
}
