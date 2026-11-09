package com.galaxy.autorecord.core

import java.io.Closeable
import java.io.File

/** 本App内的录像使用租约：录制/收尾/导出/解码与删除互斥，文件只在后台线程处理。 */
object SegmentAccess {
    internal val gate = Any()
    private val readers = mutableMapOf<String, Int>()
    private val deleting = mutableSetOf<String>()
    fun hold(directory: File): Closeable {
        val key = directory.canonicalPath
        synchronized(gate) { check(key !in deleting) { "片段正在删除" }; readers[key]=(readers[key]?:0)+1 }
        var closed=false
        return Closeable { synchronized(gate) {
            if (!closed) { closed=true; val count=(readers[key]?:1)-1; if(count==0)readers.remove(key)else readers[key]=count }
        } }
    }
    internal fun deletingId(id: String) = synchronized(gate) { deleting.any { it.substringAfterLast('/')==id } }
    fun delete(directory: File, action: () -> Boolean): Boolean {
        val key=directory.canonicalPath
        synchronized(gate) { if(key in deleting || (readers[key]?:0)>0)return false;deleting.add(key) }
        return try { action() } finally { synchronized(gate) { deleting.remove(key) } }
    }
}

data class LibraryTimeWindow(val fromMillis: Long?, val untilMillis: Long?) {
    init { require(fromMillis==null || untilMillis==null || fromMillis<untilMillis) { "结束时间必须晚于开始时间" } }
    fun matches(entry: SegmentEntry): Boolean {
        val end=entry.startedAtMillis+entry.durationMillis.coerceAtLeast(1)
        return (fromMillis==null || end>fromMillis) && (untilMillis==null || entry.startedAtMillis<untilMillis)
    }
}
