package com.ecarx.autoservice.platform

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * QNX终端协议的替身服务端。替身只模拟本车已核验的交互形状：
 * login/password提示、shell提示、以及 `uname -s; pidin -p $$ user` 的输出列。
 *
 * 注意两处不能想当然：
 * 1. 命令标记是 `as_` + uuid（QnxTelnet.execute），不是大写AS_；
 * 2. login会解析pidin的user行核对real/effective UID都是0，因此回包必须带真实列形状，
 *    只回一个“0”会被正确拒绝（见另一个用例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class PlatformExecutionTest {
    private val marker = Regex("as_[a-f0-9]+", RegexOption.IGNORE_CASE)

    /**
     * pidin -p $$ user 的一行。列形状按 login() 的解析口径给足8个数字列：
     * 第2列是real UID、第4列是effective/saved UID，两者都为0才算UID0。
     */
    private fun pidinRow(real: Int = 0, effective: Int = 0) =
        "  4242   8   $real   $effective   0   0   0   0"

    private fun serve(server: ServerSocket, identityRow: String) = Executors.newSingleThreadExecutor().submit {
        server.accept().use { peer ->
            val input = peer.getInputStream().bufferedReader()
            val output = peer.getOutputStream()
            fun send(text: String) { output.write(text.toByteArray()); output.flush() }
            send("login:"); assertEquals("fixture", input.readLine())
            send("Password:"); assertEquals("synthetic", input.readLine()); send("# ")
            val first = input.readLine()
            val commandMarker = marker.find(first)!!.value
            send("QNX\r\n$identityRow\r\n$commandMarker:0\r\n")
            // 身份被拒时客户端不会再发命令；替身不能因此阻塞或空指针。
            val second = input.readLine() ?: return@use
            val nextMarker = marker.find(second)!!.value
            send("SELFTEST_PASS\r\n$nextMarker:0\r\n")
        }
    }

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
            val future = serve(server, pidinRow())
            try {
                QnxTelnet("127.0.0.1", server.localPort).use {
                    it.login("fixture", "synthetic")
                    assertTrue(it.execute("receiver self-test").contains("SELFTEST_PASS"))
                }
                future.get(5, java.util.concurrent.TimeUnit.SECONDS)
            } finally { pool.shutdownNow() }
        }
    }

    /**
     * 独立UID的核心断言：shell提示符出现但real/effective UID不是0时必须拒绝登录，
     * 不能拿“拿到#提示”当已取得QNX UID0。
     */
    @Test fun qnxLoginRejectsShellThatIsNotUid0() {
        ServerSocket(0).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            serve(server, pidinRow(real = 1000, effective = 1000)).also { future ->
                try {
                    val failure = runCatching {
                        QnxTelnet("127.0.0.1", server.localPort).use { it.login("fixture", "synthetic") }
                    }.exceptionOrNull()
                    assertNotNull("非UID0的shell必须登录失败", failure)
                    assertEquals("qnx_root_identity_rejected", failure?.message)
                    future.get(5, java.util.concurrent.TimeUnit.SECONDS)
                } finally { pool.shutdownNow() }
            }
        }
    }
}