package com.ecarx.autoservice.projection

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.SecureRandom

/** 本应用自己的接收端认证；既不读取OEM材料，也不把诊断fixture放入APK。 */
internal object GalaxyHabKey {
    @Synchronized fun load(context: Context): ByteArray {
        val directory = File(context.noBackupFilesDir, "hab").apply { check(isDirectory || mkdirs()) }
        val file = AtomicFile(File(directory, "owner.key"))
        if (file.baseFile.exists()) return file.openRead().use { input ->
            val bytes = ByteArray(33)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            check(size == 32) { "HAB key length" }
            bytes.copyOf(32).also { bytes.fill(0) }
        }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); bytes.fill(0); throw error }
        return bytes
    }
}
