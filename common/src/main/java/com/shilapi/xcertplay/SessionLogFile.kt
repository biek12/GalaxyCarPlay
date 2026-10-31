package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File

/** 由装配方提供已脱敏上下文；核心不解析车型或产品字段。 */
internal interface DiagnosticLogContext {
    val id: String
    val header: String
}

/** 写盘/轮转在有界后台队列；入队前脱敏，并冻结配置、清理代次和reset代次。 */
internal class SessionLogFile(
    val file: File,
    private val archiveNames: List<String> = ARCHIVE_NAMES,
) : Closeable {
    private val lock = storageLock
    @Volatile private var closed = false
    @Volatile var context: DiagnosticLogContext? = null
    @Volatile private var resetGeneration = 0L
    private var writtenConfiguration: String? = null
    private data class Entry(val line: String, val evidence: DiagnosticLogContext?,
        val storageGeneration: Long, val resetGeneration: Long)
    private var reportedDrops = 0L

    init {
        require(archiveNames.isNotEmpty() && archiveNames.distinct().size == archiveNames.size)
        require(archiveNames.all { it.isNotBlank() && File(it).name == it && it !in setOf(".", "..", file.name) })
    }

    private val writer: BoundedDiagnosticWriter<Entry> = BoundedDiagnosticWriter(MAX_PENDING, ::drain)

    fun reset(header: String) = synchronized(lock) {
        if (!closed) {
            resetGeneration++
            file.parentFile?.mkdirs()
            rotate()
            file.writeText("")
            writtenConfiguration = null
            notifyStorage(true)
            DiagnosticRedactor.redact(header)?.let { write(it, null) }
        }
    }

    /** 连接/UI线程不等待文件锁；队列从不保存未脱敏正文。 */
    fun append(line: String, evidence: DiagnosticLogContext? = context) {
        if (closed) return
        val generation = AsyncDiagnosticLog.currentGeneration()
        val epoch = resetGeneration
        val safe = DiagnosticRedactor.redact(line) ?: return
        writer.enqueue(Entry(safe, evidence, generation, epoch))
    }

    /** AsyncDiagnosticLog已有线程和代次保护，直接写盘，避免双层队列。 */
    fun appendNow(line: String, evidence: DiagnosticLogContext? = context) = synchronized(lock) {
        if (!closed) DiagnosticRedactor.redact(line)?.let { write(it, evidence) }
        Unit
    }

    private fun drain(entry: Entry): Unit = synchronized(lock) {
        if (entry.storageGeneration != AsyncDiagnosticLog.currentGeneration() ||
            entry.resetGeneration != resetGeneration) return@synchronized
        val drops = writer.droppedCount
        if (drops > reportedDrops) {
            write("${drops - reportedDrops} lines dropped", entry.evidence)
            reportedDrops = drops
        }
        // 关闭前已接受的行允许有限flush；关闭后外部迟到append/appendNow均拒绝。
        write(entry.line, entry.evidence)
    }

    private fun write(safe: String, evidence: DiagnosticLogContext?) {
        val bytes = ((evidence?.let { "config_ref=${it.id} " } ?: "") + safe + "\n").toByteArray(Charsets.UTF_8)
        runCatching {
            file.parentFile?.mkdirs()
            val header = evidence?.let { (it.header + "\n").toByteArray(Charsets.UTF_8) }
            if (bytes.size + (header?.size ?: 0) > MAX_BYTES) return@runCatching
            val needsHeader = header != null && (writtenConfiguration != evidence?.id || file.length() == 0L)
            val rotated = file.length() + bytes.size + (if (needsHeader) header!!.size else 0) > MAX_BYTES
            if (rotated) {
                rotate()
                file.writeText("")
                writtenConfiguration = null
            }
            if (header != null && (writtenConfiguration != evidence?.id || file.length() == 0L)) {
                file.appendBytes(header)
                writtenConfiguration = evidence?.id
            }
            file.appendBytes(bytes)
            notifyStorage(rotated)
        }
    }

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)

    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in archiveNames.lastIndex downTo 1) {
            val source = File(file.parentFile, archiveNames[index - 1])
            val destination = File(file.parentFile, archiveNames[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, archiveNames.first()), overwrite = true)
    }

    override fun close() {
        closed = true
        writer.close()
        writer.awaitIdle(CLOSE_FLUSH_MILLIS)
    }

    private fun notifyStorage(rescan: Boolean) { runCatching { storageChanged?.invoke(file, rescan) } }

    companion object {
        internal val storageLock = Any()
        @Volatile internal var storageChanged: ((File, Boolean) -> Unit)? = null
        const val MAX_BYTES = 512 * 1024L
        private const val MAX_PENDING = 4096
        private const val CLOSE_FLUSH_MILLIS = 250L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"
    }
}
