package com.ecarx.autoservice.platform.hook

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * APK 固定资产和版本摘要只走生产 [HookMaterials]。
 *
 * 缺项、cluster 资产体积和摘要输入顺序都会改变可观察结果。
 * 读完后 Zip 必须已经关闭，调用方才能删除临时 APK。
 */
class HookMaterialsTest {
    private val clusterNames =
        listOf(
            "libgalaxy_g733_cluster.so",
            "g733_hook_runtime.sh",
            "hmi.cksum",
            "libc.cksum",
            "background.cksum",
            "rightmenu.cksum",
            "hook.cksum",
            "build.json",
        )

    @Test
    fun readReturnsNineAssetsAndClosesTheArchive() {
        val apk = zip(standardEntries())
        try {
            val files = HookMaterials.read(apk.absolutePath)
            assertEquals(clusterNames.toSet() + "health", files.keys)
            clusterNames.forEach { name ->
                assertArrayEquals(name.encodeToByteArray(), files.getValue(name))
            }
            assertArrayEquals(byteArrayOf(9), files.getValue("health"))
            assertTrue(apk.delete())
        } finally {
            apk.delete()
        }
    }

    @Test
    fun missingOrOversizedClusterAssetKeepsTheOriginalFailure() {
        val missing = zip(standardEntries().filterKeys { it != "assets/cluster/hmi.cksum" })
        val empty = zip(standardEntries(clusterBytes = ByteArray(0), clusterName = "build.json"))
        val oversized =
            zip(standardEntries(clusterBytes = ByteArray(2 * 1024 * 1024 + 1), clusterName = "build.json"))
        val largest =
            zip(standardEntries(clusterBytes = ByteArray(2 * 1024 * 1024), clusterName = "build.json"))
        try {
            val missingError =
                assertThrows(IllegalStateException::class.java) { HookMaterials.read(missing.absolutePath) }
            assertEquals("cluster_asset_missing", missingError.message)
            val emptyError =
                assertThrows(IllegalArgumentException::class.java) { HookMaterials.read(empty.absolutePath) }
            assertEquals("Failed requirement.", emptyError.message)
            val oversizedError =
                assertThrows(IllegalArgumentException::class.java) { HookMaterials.read(oversized.absolutePath) }
            assertEquals("Failed requirement.", oversizedError.message)
            val files = HookMaterials.read(largest.absolutePath)
            assertEquals(2 * 1024 * 1024, files.getValue("build.json").size)
        } finally {
            listOf(missing, empty, oversized, largest).forEach { it.delete() }
        }
    }

    @Test
    fun missingReceiverAssetIsReportedSeparatelyAndEmptyHealthIsKept() {
        val missing = zip(standardEntries().filterKeys { it != "assets/qnx/receiver-candidate" })
        val emptyHealth = zip(standardEntries(health = ByteArray(0)))
        try {
            val error =
                assertThrows(IllegalStateException::class.java) { HookMaterials.read(missing.absolutePath) }
            assertEquals("receiver_asset_missing", error.message)
            assertEquals(0, HookMaterials.read(emptyHealth.absolutePath).getValue("health").size)
        } finally {
            missing.delete()
            emptyHealth.delete()
        }
    }

    @Test
    fun versionFollowsSortedValuesAndChangesWhenOneMaterialChanges() {
        val first = mapOf("b" to byteArrayOf(1), "a" to byteArrayOf(2))
        val reversed = mapOf("a" to byteArrayOf(2), "b" to byteArrayOf(1))
        val differentKeys = mapOf("d" to byteArrayOf(1), "c" to byteArrayOf(2))
        assertEquals(HookMaterials.version(first), HookMaterials.version(reversed))
        assertEquals(HookMaterials.version(first), HookMaterials.version(differentKeys))
        val apk = zip(standardEntries())
        try {
            val files = HookMaterials.read(apk.absolutePath)
            val reordered = files.keys.reversed().associateWith { files.getValue(it) }
            assertEquals(HookMaterials.version(files), HookMaterials.version(reordered))
            val changed = files.toMutableMap()
            changed["health"] = byteArrayOf(8)
            assertNotEquals(HookMaterials.version(files), HookMaterials.version(changed))
            assertFalse(HookMaterials.version(files).isEmpty())
        } finally {
            apk.delete()
        }
    }

    private fun standardEntries(
        health: ByteArray = byteArrayOf(9),
        clusterName: String? = null,
        clusterBytes: ByteArray? = null,
    ): Map<String, ByteArray> {
        val entries =
            clusterNames.associate { name ->
                val bytes =
                    if (name == clusterName && clusterBytes != null) {
                        clusterBytes
                    } else {
                        name.encodeToByteArray()
                    }
                "assets/cluster/$name" to bytes
            }
        return entries + ("assets/qnx/receiver-candidate" to health)
    }

    private fun zip(entries: Map<String, ByteArray>): File {
        val file = Files.createTempFile("hook-materials", ".apk").toFile()
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return file
    }
}
