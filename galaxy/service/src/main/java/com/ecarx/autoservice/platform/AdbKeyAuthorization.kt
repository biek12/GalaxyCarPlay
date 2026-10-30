package com.ecarx.autoservice.platform

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 仅登记当前App的公钥；既有其它客户端记录保留。UID0 helper已验证调用owner。 */
internal object AdbKeyAuthorization {
    @Synchronized fun authorize(publicKey: String): JSONObject {
        require(publicKey.matches(Regex("[A-Za-z0-9+/=]{600,800} autoservice@local")))
        val file = File("/data/misc/adb/adb_keys")
        val original = if (file.exists()) file.readText() else ""
        require(original.length <= 1024 * 1024)
        if (original.lineSequence().any { it.substringBefore(' ') == publicKey.substringBefore(' ') })
            return JSONObject().put("state", "already_authorized")
        val temporary = File(file.parentFile, "adb_keys.autoservice-${android.os.Process.myPid()}")
        try {
            temporary.writeText(original.trimEnd() + "\n" + publicKey + "\n")
            android.system.Os.chmod(temporary.path, 416) // 0640，与本车原厂属主/权限一致。
            android.system.Os.chown(temporary.path, 1000, 2000)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            val result = ShellExecutor.execute("restorecon /data/misc/adb/adb_keys; setprop ctl.restart adbd", 5000)
            check(result.optInt("exitCode", -1) == 0) { "adb_authorization_restart_failed" }
            return JSONObject().put("state", "authorized")
        } finally { temporary.delete() }
    }
}
