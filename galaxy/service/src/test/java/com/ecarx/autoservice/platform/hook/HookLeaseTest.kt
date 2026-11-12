package com.ecarx.autoservice.platform.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.ArrayDeque

/**
 * 租约判断只走生产 [HookLease.active]。
 *
 * 时钟替身只提供先后两次当前时间，不复制 32 字节和 6000 毫秒判断。
 * 读失败必须是 false，不能把缺文件当成有效租约。
 */
class HookLeaseTest {
    @Test
    fun validLeaseMatchesGenerationFlagsMagicAndSixSecondWindow() {
        val directory = Files.createTempDirectory("hook-lease").toFile()
        try {
            val file = leaseFile(directory, expiry = 7_000)
            assertTrue(HookLease(file) { 1_000L }.active(7))
            assertTrue(HookLease(leaseFile(directory, flags = -1, expiry = 7_000)) { 1_000L }.active(7))
            assertFalse(HookLease(file) { 1_000L }.active(8))
            assertFalse(HookLease(leaseFile(directory, flags = 0, expiry = 7_000)) { 1_000L }.active(7))
            assertFalse(HookLease(leaseFile(directory, magic = 0x314c4848, expiry = 7_000)) { 1_000L }.active(7))
            assertFalse(HookLease(leaseFile(directory, expiry = 1_000)) { 1_000L }.active(7))
            assertFalse(HookLease(leaseFile(directory, expiry = 999)) { 1_000L }.active(7))
            assertTrue(HookLease(leaseFile(directory, expiry = 7_000)) { 1_000L }.active(7))
            assertFalse(HookLease(leaseFile(directory, expiry = 7_001)) { 1_000L }.active(7))
            assertFalse(HookLease(leaseFile(directory, length = 31, expiry = 7_000)) { 1_000L }.active(7))
            assertFalse(HookLease(leaseFile(directory, length = 33, expiry = 7_000)) { 1_000L }.active(7))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun activeReadsTheClockTwiceInOrder() {
        val directory = Files.createTempDirectory("hook-lease-clock").toFile()
        try {
            val expiry = 10_000L
            val file = leaseFile(directory, expiry = expiry)
            val reads = ArrayDeque(listOf(expiry - 1, expiry - 6_001))
            assertFalse(HookLease(file) { reads.removeFirst() }.active(7))
            assertTrue(reads.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unreadableLeaseIsInactive() {
        val directory = Files.createTempDirectory("hook-lease-missing").toFile()
        try {
            assertFalse(HookLease(File(directory, "missing.bin")).active(7))
            assertFalse(HookLease(directory).active(7))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun leaseFile(
        directory: File,
        magic: Int = 0x314c4847,
        flags: Int = 1,
        generation: Long = 7,
        expiry: Long = 7_000,
        length: Int = 32,
    ): File {
        val header =
            ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(magic)
                putInt(flags)
                putLong(generation)
                putLong(expiry)
            }
        val bytes = ByteArray(length)
        header.array().copyInto(bytes, endIndex = minOf(header.array().size, length))
        val file = Files.createTempFile(directory.toPath(), "lease", ".bin").toFile()
        file.writeBytes(bytes)
        return file
    }
}
