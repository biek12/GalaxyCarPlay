package com.ecarx.autoservice.platform

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AndroidRootBootstrapTest {
    private fun fixture(uid: Int, block: (Int) -> Unit): Int {
        var launches = 0
        ServerSocket(0, 1, InetAddress.getByName("::1")).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            val future = pool.submit {
                server.accept().use { peer ->
                    peer.soTimeout = 3000
                    val input = peer.getInputStream().bufferedReader()
                    val output = peer.getOutputStream()
                    val request = input.readLine()
                    val marker = Regex("ASBOOT_[a-f0-9]+").find(request)!!.value
                    output.write((request + "\r\n${marker}_BEGIN\r\nLinux\r\n$uid\r\n${marker}_END\r\n").toByteArray()); output.flush()
                    val command = input.readLine()
                    if (command != null) {
                        ++launches
                        assertTrue(command.contains("owned-helper-fixture"))
                        output.write(("\r\n${marker}_LAUNCH:0\r\n${marker}_DONE\r\n").toByteArray()); output.flush()
                    }
                }
            }
            try { block(server.localPort); future.get(5, TimeUnit.SECONDS) } finally { pool.shutdownNow() }
        }
        return launches
    }
    @Test fun rootIdentityPrecedesAppOwnedLaunchAndHandlesTerminalEcho() {
        assertEquals(1, fixture(0) { AndroidRootBootstrap.launch(ControlWork(), "owned-helper-fixture", it) })
    }
    @Test fun nonRootShellNeverReceivesBootstrapMutations() {
        assertEquals(0, fixture(2000) { port ->
            try { AndroidRootBootstrap.launch(ControlWork(), "owned-helper-fixture", port); fail() }
            catch (error: IllegalStateException) { assertEquals("android_bootstrap_not_root", error.message) }
        })
    }
}
