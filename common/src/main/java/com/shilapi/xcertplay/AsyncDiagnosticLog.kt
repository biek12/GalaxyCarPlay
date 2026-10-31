package com.shilapi.xcertplay

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 队列只捕获所属文件、配置证据与已脱敏正文；关闭文件和清理代次分别拒绝迟到记录。 */
internal object AsyncDiagnosticLog {
    private data class Entry(
        val target: SessionLogFile,
        val line: String,
        val generation: Long,
        val context: DiagnosticLogContext?,
    )
    @Volatile private var generation = 0L
    private val writer = BoundedDiagnosticWriter<Entry> { entry ->
        synchronized(SessionLogFile.storageLock) {
            if (entry.generation == generation) entry.target.appendNow(entry.line, entry.context)
        }
    }

    fun append(
        target: SessionLogFile?,
        message: String,
        nowMillis: Long = System.currentTimeMillis(),
        context: DiagnosticLogContext? = target?.context,
        eventPrefix: String = "",
    ) {
        if (target == null) return
        val captured = generation
        val safe = DiagnosticRedactor.redact(message) ?: return
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))
        // 前缀也经过同一拒敏边界，队列中不能临时保存未脱敏的调用方字符串。
        val line = DiagnosticRedactor.redact("$timestamp  $eventPrefix$safe") ?: return
        runCatching {
            writer.enqueue(Entry(target, line, captured, context))
        }
    }

    /** 只由用户发起的后台维护调用；新代次可继续写，清理前已排队的行不能恢复旧内容。 */
    internal fun clear(directory: File, additionalNames: Collection<String> = emptyList()) = synchronized(SessionLogFile.storageLock) {
        val folder = directory.canonicalFile
        // 在改变代次或删除任一文件前校验整个输入；只接受当前目录的真实叶文件，不跟随符号链接。
        val files = (SessionLogFile.REPORT_NAMES + additionalNames).distinct().map { name ->
            require(name.isNotBlank() && name !in setOf(".", "..") && '/' !in name && '\\' !in name)
            val file = File(folder, name)
            require(file.canonicalFile.parentFile == folder && file.canonicalFile.name == name)
            file
        }
        generation++
        files.forEach { file ->
            if (file.exists()) {
                if (file.name == "diplay.log") file.writeText("") else check(file.delete())
            }
        }
        runCatching { SessionLogFile.storageChanged?.invoke(File(folder, "diplay.log"), true) }
    }

    internal fun currentGeneration(): Long = generation

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)
}
