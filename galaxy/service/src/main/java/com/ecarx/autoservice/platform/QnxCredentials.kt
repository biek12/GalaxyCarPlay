package com.ecarx.autoservice.platform

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** 用户经文件选择器指定的两行凭据，仅保存在AutoService私有noBackup，不记录、不备份。 */
internal object QnxCredentials {
    fun store(context: Context, input: java.io.InputStream) {
        val bytes = input.use { stream ->
            val buffer = ByteArray(1025)
            var count = 0
            while (count < buffer.size) { val next = stream.read(buffer, count, buffer.size - count); if (next < 0) break; count += next }
            require(count in 3..1024); buffer.copyOf(count).also { buffer.fill(0) }
        }
        val lines = String(bytes, Charsets.UTF_8).lines(); bytes.fill(0)
        require(lines.size in 2..3 && lines[0].length in 1..64 && lines[1].length in 1..256)
        val data = JSONObject().put("username", lines[0]).put("password", lines[1]).toString().toByteArray()
        val file = AtomicFile(File(context.noBackupFilesDir, "qnx-login.json"))
        val output = file.startWrite()
        try { output.write(data); file.finishWrite(output) } catch (error: Exception) { file.failWrite(output); throw error }
        finally { data.fill(0) }
    }
    fun loadOptional(context: Context): JSONObject = if (File(context.noBackupFilesDir, "qnx-login.json").exists()) load(context) else JSONObject()
    fun load(context: Context): JSONObject {
        val file = File(context.noBackupFilesDir, "qnx-login.json")
        check(file.exists() && file.length() in 3..2048) { "qnx_credentials_missing" }
        return JSONObject(file.readText())
    }
}
