package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File

/** 由装配方提供已脱敏上下文；核心只关心不透明标识和独立头部，不解析车型或产品字段。 */
internal interface DiagnosticLogContext {
    val id: String
    val header: String
}

/** 每行先脱敏再落盘，按UTF-8总字节预算轮转；上下文的脱敏由注入方负责。 */
internal class SessionLogFile(
    val file: File,
    private val archiveNames: List<String> = ARCHIVE_NAMES,
) : Closeable {
    private val lock = storageLock
    private var closed = false
    @Volatile var context: DiagnosticLogContext? = null
    private var writtenConfiguration: String? = null

    init {
        require(archiveNames.isNotEmpty() && archiveNames.distinct().size == archiveNames.size)
        require(archiveNames.all { it.isNotBlank() && File(it).name == it && it !in setOf(".", "..", file.name) })
    }

    fun reset(header: String) = synchronized(lock) {
        if (!closed) {
            file.parentFile?.mkdirs()
            rotate()
            file.writeText("")
            writtenConfiguration = null
            notifyStorage(true)
            append(header)
        }
    }

    fun append(line: String, evidence: DiagnosticLogContext? = context) = synchronized(lock) {
        if (closed) return@synchronized
        val safe = DiagnosticRedactor.redact(line) ?: return@synchronized
        val bytes = ((evidence?.let { "config_ref=${it.id} " } ?: "") + safe + "\n").toByteArray(Charsets.UTF_8)
        runCatching {
            file.parentFile?.mkdirs()
            val header = evidence?.let { (it.header + "\n").toByteArray(Charsets.UTF_8) }
            // 实际写入点核总预算，不依赖装配方预算，也不把字符数误作磁盘字节数。
            if (bytes.size + (header?.size ?: 0) > MAX_BYTES) return@runCatching
            val needsHeader = header != null && (writtenConfiguration != evidence?.id || file.length() == 0L)
            val rotated = file.length() + bytes.size + (if (needsHeader) header!!.size else 0) > MAX_BYTES
            if (rotated) {
                rotate()
                file.writeText("")
                writtenConfiguration = null
            }
            if (header != null && (writtenConfiguration != evidence?.id || file.length() == 0L)) {
                // 不重用普通行的截断器；此头部只能来自已脱敏并校验摘要的配置证据。
                file.appendBytes(header)
                writtenConfiguration = evidence?.id
            }
            file.appendBytes(bytes)
            notifyStorage(rotated)
        }
        Unit
    }

    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in archiveNames.lastIndex downTo 1) {
            val source = File(file.parentFile, archiveNames[index - 1])
            val destination = File(file.parentFile, archiveNames[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, archiveNames.first()), overwrite = true)
    }

    override fun close() = synchronized(lock) { closed = true }
    private fun notifyStorage(rescan: Boolean) { runCatching { storageChanged?.invoke(file, rescan) } }

    companion object {
        // 写入、轮转、清理与产品保留策略共用一个边界，不在清理后回写旧队列。
        internal val storageLock = Any()
        @Volatile internal var storageChanged: ((File, Boolean) -> Unit)? = null
        const val MAX_BYTES = 512 * 1024L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"
    }
}
