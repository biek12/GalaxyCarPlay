package com.ecarx.autoservice.platform

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 仅登记当前App的公钥；既有其它客户端记录保留。UID0 helper已验证调用owner。 */
internal object AdbKeyAuthorization {
    @Synchronized fun authorize(publicKey: String, forceReload: Boolean = false): JSONObject = install(
        publicKey, File("/data/misc/adb/adb_keys"), ::repairAndVerify,
        {
            val result = ShellExecutor.execute("setprop ctl.restart adbd", 5000)
            check(result.optInt("exitCode", -1) == 0) { "adb_authorization_restart_failed" }
        }, forceReload)

    /** 测试注入文件/元数据操作；生产只允许上面的固定adb_keys路径。备份仅留车机原目录。 */
    internal fun install(publicKey: String, file: File, repair: (File) -> Unit, restart: () -> Unit,
                         forceReload: Boolean = false): JSONObject {
        require(publicKey.matches(Regex("[A-Za-z0-9+/=]{600,800} autoservice@local")))
        val original = if (file.exists()) file.readText() else ""
        require(original.length <= 1024 * 1024)
        if (contains(original, publicKey)) {
            repair(file)
            check(contains(file.readText(), publicKey)) { "adb_authorization_readback_failed" }
            if (forceReload) restart()
            return JSONObject().put("state", "already_authorized")
        }
        val temporary = File(file.parentFile, "adb_keys.autoservice-${android.os.Process.myPid()}")
        val existed = file.exists()
        val backup = File(file.parentFile, "adb_keys.bak.autoservice-${System.currentTimeMillis()}-${android.os.Process.myPid()}")
        if (existed) { file.copyTo(backup); repair(backup) }
        var moved = false
        try {
            temporary.writeText(original + (if (original.isNotEmpty() && !original.endsWith('\n')) "\n" else "") + publicKey + "\n")
            repair(temporary)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            moved = true
            repair(file)
            check(contains(file.readText(), publicKey)) { "adb_authorization_readback_failed" }
        } catch (error: Exception) {
            if (moved) {
                if (existed) { backup.copyTo(file, overwrite = true); repair(file) } else file.delete()
            }
            throw error
        } finally { temporary.delete() }
        restart()
        return JSONObject().put("state", "authorized")
    }
    private fun contains(text: String, key: String) = text.lineSequence().any { it.substringBefore(' ') == key.substringBefore(' ') }
    private fun repairAndVerify(file: File) {
        android.system.Os.chmod(file.path, 416) // 0640，与本车原厂属主/权限一致。
        android.system.Os.chown(file.path, 1000, 2000)
        val path = "'" + file.path.replace("'", "'\\''") + "'"
        val result = ShellExecutor.execute("restorecon $path", 5000)
        check(result.optInt("exitCode", -1) == 0) { "adb_authorization_context_failed" }
        val stat = android.system.Os.stat(file.path)
        check(stat.st_uid == 1000 && stat.st_gid == 2000 && (stat.st_mode and 511) == 416) { "adb_authorization_mode_failed" }
    }
}
