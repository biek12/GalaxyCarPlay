package com.ecarx.autoservice.platform

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Root进程内沿现场NFS桥部署自有接收程序。只写AutoService独立目录，不改HMI/原车窗口。 */
internal object QnxReceiverDeployer {
    private const val SHARED = "/data/vendor/nfs/nfs_ota"
    private const val DIRECTORY = "/var/data/autoservice-hab"
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
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
        val stageName = "autoservice-" + UUID.randomUUID().toString()
        val stage = File(SHARED, stageName)
        try {
            connection(request).use { remote ->
                val keyCrc = PosixChecksum.calculate(key)
                val healthy = remote.execute("if [ -f $DIRECTORY/version ] && [ \"\$(cat $DIRECTORY/version)\" = ${quote(sha)} ] && [ -f $DIRECTORY/receiver.pid ]; then p=\$(cat $DIRECTORY/receiver.pid); case \"\$p\" in ''|*[!0-9]*) ;; *) if kill -0 \"\$p\" 2>/dev/null && pidin -p \"\$p\" ar | grep $DIRECTORY/receiver >/dev/null && cksum $DIRECTORY/owner.key | grep '^$keyCrc 32 ' >/dev/null && grep RECEIVER_LISTEN_SESSION $DIRECTORY/receiver.log >/dev/null; then printf '\\nAUTOSERVICE_REUSE\\n'; fi;; esac; fi")
                if (healthy.lines().any { it.trim() == "AUTOSERVICE_REUSE" })
                    return JSONObject().put("state", "receiver_listening").put("sha256", sha).put("displayId", 2).put("reused", true)
                check(stage.mkdir()) { "nfs_stage_unavailable" }
                File(stage, "receiver").writeBytes(binary)
                File(stage, "owner.key").apply { writeBytes(key); setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true) }
                // PID记录同时绑定本目录的程序路径；只停止本服务记录的子进程。
                remote.execute("umask 077; mkdir -p $DIRECTORY; if [ -f $DIRECTORY/receiver.pid ]; then p=\$(cat $DIRECTORY/receiver.pid); case \"\$p\" in ''|*[!0-9]*) exit 21;; esac; if kill -0 \"\$p\" 2>/dev/null; then pidin -p \"\$p\" ar | grep $DIRECTORY/receiver >/dev/null || exit 22; kill -TERM \"\$p\" || exit 23; sleep 1; kill -0 \"\$p\" 2>/dev/null && exit 24; fi; rm -f $DIRECTORY/receiver.pid; fi")
                remote.execute("cp /otaupdate/$stageName/receiver $DIRECTORY/receiver.new && cp /otaupdate/$stageName/owner.key $DIRECTORY/owner.key && chmod 700 $DIRECTORY/receiver.new && chmod 600 $DIRECTORY/owner.key")
                // 完整性按目标POSIX cksum验证，不假定QNX安装sha256工具。
                val crc = PosixChecksum.calculate(binary)
                val verified = remote.execute("cksum $DIRECTORY/receiver.new")
                check(Regex("(?m)^$crc[ \\t]+${binary.size}[ \\t]+").containsMatchIn(verified)) { "qnx_binary_checksum_mismatch" }
                val selfTest = remote.execute("$DIRECTORY/receiver.new self-test")
                check(selfTest.contains("SELFTEST_PASS")) { "qnx_self_test_failed" }
                val inventory = remote.execute("$DIRECTORY/receiver.new inventory")
                check(Regex("QNX_DISPLAY_ID=2\\r?\\nWIDTH=1920\\r?\\nHEIGHT=532").containsMatchIn(inventory)) { "qnx_display_profile_mismatch" }
                remote.execute("mv $DIRECTORY/receiver.new $DIRECTORY/receiver; printf '%s' ${quote(sha)} > $DIRECTORY/version; (trap '' HUP; exec $DIRECTORY/receiver serve $DIRECTORY/owner.key 2 1920 532 4) >$DIRECTORY/receiver.log 2>&1 </dev/null & p=\$!; echo \"\$p\" > $DIRECTORY/receiver.pid; sleep 1; kill -0 \"\$p\" && grep RECEIVER_LISTEN_SESSION=1 $DIRECTORY/receiver.log >/dev/null")
            }
            return JSONObject().put("state", "receiver_listening").put("sha256", sha).put("displayId", 2)
        } finally { key.fill(0); stage.listFiles()?.forEach { it.delete() }; stage.delete() }
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
