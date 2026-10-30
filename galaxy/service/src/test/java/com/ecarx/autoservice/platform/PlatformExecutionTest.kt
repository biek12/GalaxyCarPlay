package com.ecarx.autoservice.platform

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class PlatformExecutionTest {
    @Test fun shellKeepsExitCodeBoundsOutputAndTerminatesAtDeadline() {
        val failed = ShellExecutor.execute("printf hello; exit 7", 2000, "/bin/sh")
        assertEquals(7, failed.getInt("exitCode")); assertEquals("hello", failed.getString("output"))
        val large = ShellExecutor.execute("yes x | head -c 100000", 3000, "/bin/sh")
        assertEquals(65536, large.getString("output").length); assertTrue(large.getBoolean("truncated"))
        assertTrue(ShellExecutor.execute("sleep 3", 100, "/bin/sh").getBoolean("timedOut"))
    }
    @Test fun posixChecksumMatchesSystemToolAndIncludesFileLength() {
        for (data in listOf(ByteArray(0), "123456789".toByteArray(), ByteArray(1024) { it.toByte() })) {
            val process = ProcessBuilder("cksum").start()
            process.outputStream.use { it.write(data) }
            val expected = process.inputStream.bufferedReader().readText().trim().substringBefore(' ').toLong()
            assertEquals(expected, PosixChecksum.calculate(data))
        }
    }
    @Test fun qnxLoginRequiresIndependentQnxRootIdentityAndCommandStatusMarker() {
        ServerSocket(0).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            val future = pool.submit {
                server.accept().use { peer ->
                    val input = peer.getInputStream().bufferedReader()
                    val output = peer.getOutputStream()
                    fun send(text: String) { output.write(text.toByteArray()); output.flush() }
                    send("login:"); assertEquals("fixture", input.readLine())
                    send("Password:"); assertEquals("synthetic", input.readLine()); send("# ")
                    val first = input.readLine()
                    val marker = Regex("AS_[a-f0-9]+").find(first)!!.value
                    send("QNX\r\n0\r\n$marker:0\r\n")
                    val second = input.readLine()
                    val next = Regex("AS_[a-f0-9]+").find(second)!!.value
                    send("SELFTEST_PASS\r\n$next:0\r\n")
                }
            }
            try {
                QnxTelnet("127.0.0.1", server.localPort).use {
                    it.login("fixture", "synthetic")
                    assertTrue(it.execute("receiver self-test").contains("SELFTEST_PASS"))
                }
                future.get(5, java.util.concurrent.TimeUnit.SECONDS)
            } finally { pool.shutdownNow() }
        }
    }
}
