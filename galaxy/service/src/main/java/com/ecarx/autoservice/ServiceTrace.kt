package com.ecarx.autoservice

import android.os.SystemClock
import android.util.Log
import java.io.File

/** App/helper分别留两份有界日志。只写固定阶段、异常类与源码栈，不记录异常消息、命令或载荷。 */
internal object ServiceTrace {
    private var destination: File? = null
    private val recent = linkedMapOf<String, Long>()
    @Synchronized fun configure(file: File, uid: Int? = null) {
        runCatching {
            file.parentFile!!.mkdirs()
            if (!file.exists()) file.createNewFile()
            if (uid != null) android.system.Os.chown(file.path, uid, uid)
            destination = file
        }
    }
    @Synchronized fun event(stage: String, error: Throwable? = null) {
        val now = SystemClock.elapsedRealtime()
        val key = stage + error?.javaClass?.name.orEmpty()
        if (now - (recent[key] ?: -30000) < 30000) return
        if (recent.size >= 128) recent.remove(recent.keys.first())
        recent[key] = now
        val line = buildString {
            append(System.currentTimeMillis()).append(" elapsed=").append(now).append(" pid=").append(android.os.Process.myPid())
            append(" uid=").append(android.os.Process.myUid()).append(' ').append(stage)
            var cause = error
            repeat(4) {
                val current = cause ?: return@repeat
                append("\n").append(current.javaClass.name)
                current.stackTrace.take(12).forEach { append("\n  at ").append(it) }
                cause = current.cause?.takeUnless { it === current }
            }
        }
        Log.i("AutoServiceTrace", line)
        runCatching {
            destination?.let { file ->
                if (file.length() > 512 * 1024) {
                    val previous = File(file.path + ".previous")
                    previous.delete(); file.copyTo(previous, overwrite = true); file.writeText("")
                }
                file.appendText(line + "\n")
            }
        }
    }
}
