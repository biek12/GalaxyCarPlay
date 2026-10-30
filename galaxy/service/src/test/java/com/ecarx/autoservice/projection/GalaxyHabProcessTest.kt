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
        process.frame(null)
        process.finish()
        val bytes = child.input.toByteArray()
        assertEquals(64, bytes.size)
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), bytes.copyOfRange(32, 36))
        assertTrue(bytes.copyOfRange(48, 64).all { it == 0.toByte() })
        assertEquals(1, child.signalled)
        process.close()
        assertEquals(1, child.signalled)
    }

    @Test fun blockedChildIsForciblyReapedWithoutWaitingOnTheCallerThread() {
        val child = Child()
        val process = GalaxyHabProcess(RuntimeEnvironment.getApplication(), {}, { child })
        process.close()
        assertEquals(0, child.forced)
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(1, child.forced)
    }

    private class Child : Process() {
        val input = ByteArrayOutputStream()
        var signalled = 0
        var forced = 0
        override fun getOutputStream() = input
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = 0
        override fun exitValue(): Int = if (forced == 0) throw IllegalThreadStateException() else 0
        override fun isAlive() = forced == 0
        override fun destroy() { ++signalled }
        override fun destroyForcibly(): Process { ++forced; return this }
    }
}
