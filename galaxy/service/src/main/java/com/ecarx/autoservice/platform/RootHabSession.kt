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
        output.writeInt(0); output.flush(); client.soTimeout = 0
        val feeder = Thread({
            try { client.inputStream.copyTo(child.outputStream) } catch (_: Exception) { }
            finally { runCatching { child.outputStream.close() }; child.destroy() }
        }, "AutoService-hab-input").apply { isDaemon = true; start() }
        try { child.inputStream.copyTo(client.outputStream) }
        finally {
            runCatching { child.outputStream.close() }; child.destroy(); client.close(); feeder.interrupt()
            children.remove(child)
        }
    }
    fun close() { children.forEach { it.destroy(); runCatching { it.outputStream.close() } }; children.clear() }
}
