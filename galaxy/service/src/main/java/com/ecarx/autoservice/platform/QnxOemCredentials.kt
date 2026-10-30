package com.ecarx.autoservice.platform

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 本车原厂登录信息取得器。只解析数据，不执行原厂PCM/删除命令；真实值只留在Root进程。 */
internal object QnxOemCredentials {
    internal class Login(val username: String, val password: String) {
        override fun toString() = "QnxLogin(<redacted>)"
    }
    private const val PACKAGE = "com.flyme.auto.logreport"
    private const val KNOWN_SHA = "1c6c9069e7364d75128d507cdc26fe95068bf8ef6846d5abc5af7a4eb63b2e7b"
    private var cached: Pair<String, Login>? = null
    @Synchronized fun load(): Login {
        val result = ShellExecutor.execute("pm path $PACKAGE", 5000)
        check(result.optInt("exitCode", -1) == 0) { "qnx_oem_package_unavailable" }
        val paths = result.optString("output").lineSequence().filter { it.startsWith("package:") }.map { File(it.removePrefix("package:").trim()).canonicalFile }.toList()
        val apk = paths.singleOrNull { it.name == "base.apk" } ?: paths.singleOrNull() ?: error("qnx_oem_apk_ambiguous")
        check(apk.isFile && apk.length() in 112..67108864) { "qnx_oem_apk_invalid" }
        val stamp = "${apk.path}:${apk.length()}:${apk.lastModified()}"
        cached?.takeIf { it.first == stamp }?.let { return it.second }
        return fromApk(apk, KNOWN_SHA).also { cached = stamp to it }
    }
    internal fun fromApk(apk: File, expectedSha: String): Login {
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input -> val bytes = ByteArray(8192); while (true) { val size = input.read(bytes); if (size < 0) break; digest.update(bytes, 0, size) } }
        check(digest.digest().joinToString("") { "%02x".format(it) } == expectedSha) { "qnx_oem_source_changed" }
        val logins = mutableListOf<Login>()
        ZipFile(apk).use { archive ->
            val entries = archive.entries()
            var total = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.matches(Regex("classes[0-9]*\\.dex"))) continue
                require(entry.size in 112..33554432) { "qnx_dex_size" }
                total += entry.size.toInt(); require(total <= 67108864) { "qnx_dex_total" }
                val bytes = archive.getInputStream(entry).use { input ->
                    val buffer = ByteArray(entry.size.toInt()); java.io.DataInputStream(input).readFully(buffer); buffer
                }
                try { logins.addAll(fromDex(bytes)) } finally { bytes.fill(0) }
            }
        }
        check(logins.size == 2 && logins[0].username == logins[1].username && logins[0].password == logins[1].password) { "qnx_login_workflow_mismatch" }
        return logins[0]
    }
    internal fun fromDex(data: ByteArray): List<Login> {
        require(data.size >= 112 && data.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10))) { "qnx_dex_header" }
        fun u32(offset: Int): Int {
            require(offset >= 0 && offset.toLong() + 4 <= data.size) { "qnx_dex_bounds" }
            val value = (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8) or
                ((data[offset + 2].toInt() and 255) shl 16) or ((data[offset + 3].toInt() and 255) shl 24)
            require(value >= 0) { "qnx_dex_integer" }; return value
        }
        require(u32(32) == data.size && u32(36) == 112 && u32(40) == 0x12345678) { "qnx_dex_header" }
        val count = u32(56); val table = u32(60)
        require(count <= 1000000 && table.toLong() + count.toLong() * 4 <= data.size) { "qnx_dex_table" }
        val result = mutableListOf<Login>()
        for (index in 0 until count) {
            var position = u32(table + index * 4)
            var lengthDone = false
            repeat(5) {
                if (!lengthDone) {
                    require(position in data.indices) { "qnx_dex_string_length" }
                    if ((data[position++].toInt() and 128) == 0) lengthDone = true
                }
            }
            require(lengthDone) { "qnx_dex_string_length" }
            var end = position
            while (end < data.size && data[end] != 0.toByte() && end - position < 65536) ++end
            require(end < data.size && data[end] == 0.toByte()) { "qnx_dex_string_bounds" }
            val text = String(data, position, end - position, Charsets.UTF_8)
            if (!text.contains("busybox telnet 192.168.118.2")) continue
            val fields = Regex("echo \"([^\"]*)\"").findAll(text).take(2).map { it.groupValues[1] }.toList()
            require(fields.size == 2 && fields[0].length in 1..64 && fields[1].length in 1..256 &&
                fields.all { value -> value.none { it == '\n' || it == '\r' || it == '\u0000' } }) { "qnx_login_workflow_invalid" }
            result.add(Login(fields[0], fields[1]))
        }
        return result
    }
}
