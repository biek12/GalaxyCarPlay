package com.ecarx.autoservice.platform

import android.net.Credentials
import android.os.Process
import java.io.File

/** 沿星枢以UID/PID/进程起始时间绑定一次App生命期，不能由复用PID继承旧helper。 */
internal data class RootOwner(val uid: Int, val pid: Int, val started: String) {
    val socketName get() = "autoservice_root_${uid}_${pid}_$started"
    fun accepts(peer: Credentials) = peer.uid == uid && peer.pid == pid && valid()
    fun valid() = runCatching {
        val status = File("/proc/$pid/status").readLines().first { it.startsWith("Uid:") }.trim().split(Regex("\\s+"))[1].toInt()
        status == uid && ticks(pid) == started &&
            File("/proc/$pid/cmdline").readText().substringBefore('\u0000').let { it == "com.ecarx.autoservice" || it.startsWith("com.ecarx.autoservice:") }
    }.getOrDefault(false)
    companion object {
        fun ticks(pid: Int): String = File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19]
        fun current() = RootOwner(Process.myUid(), Process.myPid(), ticks(Process.myPid()))
    }
}
