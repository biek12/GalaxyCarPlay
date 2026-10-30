package com.ecarx.autoservice.platform

import org.json.JSONObject
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Root进程内沿现场NFS桥部署自有接收程序。只写AutoService独立目录，不改HMI/原车窗口。 */
internal object QnxReceiverDeployer {
    private const val DIRECTORY = "/var/data/autoservice-hab"
    private fun connection(request: JSONObject) = QnxTelnet("192.168.118.2").also {
        try {
            val login = if (request.has("username") && request.has("password"))
                QnxOemCredentials.Login(request.getString("username"), request.getString("password")) else QnxOemCredentials.load()
            it.login(login.username, login.password)
        }
        catch (error: Exception) { it.close(); throw error }
    }
    fun deploy(request: JSONObject): JSONObject {
        val key = android.util.Base64.decode(request.getString("ownerKey"), android.util.Base64.NO_WRAP)
        require(key.size == 32)
        val apk = System.getenv("CLASSPATH")?.substringBefore(':') ?: error("apk_missing")
        val binary = ZipFile(apk).use { zip -> zip.getInputStream(zip.getEntry("assets/qnx/receiver-candidate") ?: error("receiver_asset_missing")).use { it.readBytes() } }
        require(binary.size in 1024..4 * 1024 * 1024)
        val sha = MessageDigest.getInstance("SHA-256").digest(binary).joinToString("") { "%02x".format(it) }
        try {
            connection(request).use { remote ->
                QnxReceiverRemote(remote::execute, binary, key, sha).use { port ->
                    val action = QnxReceiverLifecycle(port).ensureRunning()
                    return JSONObject().put("state", "receiver_listening").put("sha256", sha).put("displayId", 2)
                        .put("action", action.wire).put("reused", action == QnxReceiverLifecycle.Action.REUSED)
                }
            }
        } finally { key.fill(0) }
    }
    fun stop(request: JSONObject): JSONObject {
        connection(request).use { remote -> remote.execute("if [ -f $DIRECTORY/receiver.pid ]; then p=\$(cat $DIRECTORY/receiver.pid); case \"\$p\" in ''|*[!0-9]*) exit 21;; esac; if kill -0 \"\$p\" 2>/dev/null; then pidin -p \"\$p\" ar | grep $DIRECTORY/receiver >/dev/null || exit 22; kill -TERM \"\$p\" || exit 23; sleep 1; kill -0 \"\$p\" 2>/dev/null && exit 24; fi; rm -f $DIRECTORY/receiver.pid; fi") }
        return JSONObject().put("state", "receiver_stop_requested")
    }
}

/** POSIX cksum附加长度，区别于普通CRC32；现场QNX/Android已有cksum。 */
internal object PosixChecksum {
    fun calculate(bytes: ByteArray): Long {
        var crc = 0L
        fun update(value: Int) {
            crc = crc xor ((value.toLong() and 255) shl 24)
            repeat(8) { crc = if (crc and 0x80000000L != 0L) ((crc shl 1) xor 0x04c11db7L) and 0xffffffffL else (crc shl 1) and 0xffffffffL }
        }
        bytes.forEach { update(it.toInt()) }
        var length = bytes.size
        while (length != 0) { update(length and 255); length = length ushr 8 }
        return crc.inv() and 0xffffffffL
    }
}
