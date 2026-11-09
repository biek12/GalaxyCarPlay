package com.galaxy.autorecord.core

/**
 * 有界诊断日志（进程内环形缓冲）。
 *
 * 用途：P11 诊断页要能看到"最近发生了什么"，但又不能无限增长、不能写用户录像数据。
 * 约束：只在内存、最多 [MAX_LINES] 行、不含车牌/路径以外的敏感信息；导出由用户主动触发。
 * 说明：这是**界面诊断日志**，不是崩溃日志；崩溃由系统 logcat 承担，两者不混用。
 */
object RecordLog {
    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(tag: String, message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        lines.addLast("$stamp [$tag] $message")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
    }
}
