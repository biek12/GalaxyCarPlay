package com.ecarx.autoservice.platform

import android.net.LocalSocket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** helper独占自有HAB子进程；owner死亡/EOF都关闭pipe并终止子进程。 */
internal object RootHabSession {
    private val children = ConcurrentHashMap.newKeySet<java.lang.Process>()
    fun run(client: LocalSocket, input: DataInputStream, output: DataOutputStream, apk: String) {
        val native = File(input.readUTF()).canonicalFile
        check(native.name == "libgalaxy_hab_bridge.so" && native.path.startsWith(File(apk).parentFile!!.canonicalPath + "/lib/"))
        val child = ProcessBuilder(native.path).redirectErrorStream(true).start()
        children.add(child)
        com.ecarx.autoservice.ServiceTrace.event("root_hab_started")
        output.writeInt(0); output.flush(); client.soTimeout = 0
        val feeder = Thread({
            try {
                val buffer = ByteArray(8192)
                val source = client.inputStream
                val target = child.outputStream
                var first = true
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    target.write(buffer, 0, count)
                    // Process输出流有缓冲。首包只有32字节密钥，必须立即送到native，
                    // 否则双方分别等待密钥/READY直到超时；帧头与心跳同样不能等待EOF。
                    target.flush()
                    if (first) {
                        first = false
                        com.ecarx.autoservice.ServiceTrace.event("root_hab_first_input bytes=$count")
                    }
                }
            } catch (_: Exception) { }
            finally { runCatching { child.outputStream.close() }; child.destroy() }
        }, "AutoService-hab-input").apply { isDaemon = true; start() }
        try {
            val buffer = ByteArray(4096)
            val source = child.inputStream
            val target = client.outputStream
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                // 仅登记固定阶段/数值，转发仍逐字节保真，不保存其他子进程正文。
                val status = String(buffer, 0, count, Charsets.US_ASCII)
                if (status.contains("HAB: ready display="))
                    com.ecarx.autoservice.ServiceTrace.event("root_hab_native_ready")
                Regex("HAB: closed result=(-?[0-9]+) cleanup=(-?[0-9]+)").find(status)?.let {
                    com.ecarx.autoservice.ServiceTrace.event("root_hab_native_closed result=${it.groupValues[1]} cleanup=${it.groupValues[2]}")
                }
                target.write(buffer, 0, count)
                target.flush()
            }
        }
        finally {
            runCatching { child.outputStream.close() }; child.destroy()
            if (!child.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                child.destroyForcibly(); child.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            client.close(); feeder.interrupt(); children.remove(child)
        }
    }
    fun close() { children.forEach { it.destroy(); runCatching { it.outputStream.close() } }; children.clear() }
}
