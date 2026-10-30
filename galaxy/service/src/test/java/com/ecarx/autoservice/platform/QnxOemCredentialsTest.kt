package com.ecarx.autoservice.platform

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.security.MessageDigest

class QnxOemCredentialsTest {
    private fun dex(vararg strings: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        output.write(ByteArray(112 + strings.size * 4))
        val offsets = strings.map {
            val offset = output.size()
            val bytes = it.toByteArray(); require(bytes.size < 128)
            output.write(bytes.size); output.write(bytes); output.write(0); offset
        }
        val data = output.toByteArray(); "dex\n035\u0000".toByteArray().copyInto(data)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(32, data.size); buffer.putInt(36, 112); buffer.putInt(40, 0x12345678)
        buffer.putInt(56, strings.size); buffer.putInt(60, 112)
        offsets.forEachIndexed { index, offset -> buffer.putInt(112 + index * 4, offset) }
        return data
    }
    private val workflow = "{ echo \"fixture\"; echo \"synthetic\"; echo destructive; } | busybox telnet 192.168.118.2"
    @Test fun apkParserReadsTwoMatchingWorkflowsWithoutExecutingCommandsOrEchoingSecret() {
        val apk = File.createTempFile("qnx-fixture", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("classes.dex")); zip.write(dex("ordinary", workflow, workflow)); zip.closeEntry() }
            val sha = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
            val login = QnxOemCredentials.fromApk(apk, sha)
            assertEquals("fixture", login.username); assertEquals("synthetic", login.password)
            assertFalse(login.toString().contains("synthetic"))
            try { QnxOemCredentials.fromApk(apk, "0".repeat(64)); fail() } catch (_: IllegalStateException) {}
        } finally { apk.delete() }
    }
    @Test fun malformedDexOffsetsAndUnterminatedLengthAreRejectedBeforeAllocating() {
        val data = dex(workflow)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(60, Int.MAX_VALUE)
        try { QnxOemCredentials.fromDex(data); fail() } catch (_: IllegalArgumentException) {}
        val bad = dex(workflow); val pointer = ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).getInt(112)
        repeat(5) { bad[pointer + it] = 128.toByte() }
        try { QnxOemCredentials.fromDex(bad); fail() } catch (_: IllegalArgumentException) {}
    }
}
