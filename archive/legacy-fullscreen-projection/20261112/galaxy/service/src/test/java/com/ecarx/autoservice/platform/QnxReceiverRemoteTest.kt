package com.ecarx.autoservice.platform

import org.junit.Assert.*
import org.junit.Test

class QnxReceiverRemoteTest {
    private val bytes = ByteArray(1024) { it.toByte() }
    private val key = ByteArray(32) { it.toByte() }
    @Test fun actualWakeCommandsStartInstalledProgramWithoutCopyingOrPromotingFiles() {
        val commands = mutableListOf<String>()
        val crc = PosixChecksum.calculate(bytes)
        QnxReceiverRemote({ command ->
            commands.add(command)
            when {
                command.contains("AUTOSERVICE_RECEIVER_LISTENING") -> "AUTOSERVICE_RECEIVER_INSTALLED\r\n"
                command == "cksum /var/data/autoservice-hab/receiver" -> "$crc 1024 /var/data/autoservice-hab/receiver\r\n"
                command.endsWith(" self-test") -> "SELFTEST_PASS\r\n"
                command.endsWith(" inventory") -> "QNX_DISPLAY_ID=2\r\nWIDTH=1920\r\nHEIGHT=532\r\n"
                else -> ""
            }
        }, bytes, key, "a".repeat(64)).use { remote ->
            assertEquals(QnxReceiverLifecycle.Action.WOKEN, QnxReceiverLifecycle(remote).ensureRunning())
        }
        assertTrue(commands.last().contains("receiver serve /var/data/autoservice-hab/owner.key 2 1920 532 4"))
        assertTrue(commands.last().contains("RECEIVER_LISTEN_SESSION=1"))
        assertFalse(commands.any { it.contains("cp /otaupdate") || it.contains("mv ") || it.contains("receiver.new") })
        assertTrue(commands[1].contains("pidin"))
    }
    @Test fun inventoryMustProvideOneUnambiguousStateMarker() {
        for (reply in listOf("", "AUTOSERVICE_RECEIVER_INSTALLED\nAUTOSERVICE_RECEIVER_LISTENING\n")) {
            QnxReceiverRemote({ reply }, bytes, key, "a".repeat(64)).use { remote ->
                try { remote.inspect(); fail() } catch (error: IllegalStateException) { assertEquals("qnx_receiver_inventory_invalid", error.message) }
            }
        }
    }
    @Test fun posixWakeHandlesRebootPidReuseWithoutTerminatingForeignProcessOrReinstalling() {
        val folder = java.nio.file.Files.createTempDirectory("qnx-wake-fixture-").toFile()
        val program = "#!/bin/sh\ncase \"\$1\" in self-test) printf 'SELFTEST_PASS\\n';; inventory) printf 'QNX_DISPLAY_ID=2\\nWIDTH=1920\\nHEIGHT=532\\n';; serve) printf 'RECEIVER_LISTEN_SESSION=1\\n';; esac\n".toByteArray()
        try {
            java.io.File(folder, "receiver").apply { writeBytes(program); setExecutable(true) }
            java.io.File(folder, "owner.key").writeBytes(key)
            java.io.File(folder, "version").writeText("b".repeat(64))
            java.io.File(folder, "receiver.pid").writeText("12345")
            val forbidden = java.io.File(folder, "foreign-kill-request")
            QnxReceiverRemote({ command ->
                val redirected = command.replace("/var/data/autoservice-hab", folder.path)
                val functions = "kill() { if [ \"\$1\" = -TERM ]; then : > '${forbidden.path}'; return 1; fi; return 0; }; " +
                    "pidin() { printf '12345 /system/foreign-HMI\\n'; }; "
                val process = ProcessBuilder("/bin/sh", "-c", functions + redirected).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(output, 0, process.exitValue())
                output
            }, program, key, "b".repeat(64)).use { remote ->
                assertEquals(QnxReceiverLifecycle.Action.WOKEN, QnxReceiverLifecycle(remote).ensureRunning())
            }
            assertFalse(forbidden.exists())
            assertArrayEquals(program, java.io.File(folder, "receiver").readBytes())
            assertFalse(java.io.File(folder, "receiver.new").exists())
            assertEquals("b".repeat(64), java.io.File(folder, "version").readText())
        } finally { folder.deleteRecursively() }
    }

}
