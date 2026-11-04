package com.galaxy.autocluster.theme

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class ThemeLibraryEntry(val metadata: ThemeMetadata, val builtIn: Boolean)
internal data class ThemeLibrary(val entries: List<ThemeLibraryEntry>, val unreadableIds: List<String>)
internal class ThemeRevisionConflict : IllegalStateException("theme_revision_conflict")

/** 主题IO线程独占调用；同目录实例/进程共用锁，读改写和revision检查是同一事务。
 * 库文件与active快照分别原子提交：修改保存的主题不会偷偷改变正在投屏的版本。
 */
internal class ThemeStore(
    private val root: File,
    builtIns: List<ThemePackage> = emptyList(),
    private val writeAtomic: (AtomicFile, ByteArray) -> Unit = ::commit,
) {
    private val builtIns = builtIns.associateBy { it.metadata.id }
    private val mutex = locks.computeIfAbsent(root.canonicalPath) { Any() }

    init { require(this.builtIns.size == builtIns.size) { "theme_builtin_id_duplicate" } }

    fun library(): ThemeLibrary = transaction {
        val good = builtIns.values.map { ThemeLibraryEntry(it.metadata, true) }.toMutableList()
        val unreadable = mutableListOf<String>()
        val ids = root.listFiles().orEmpty().map { it.name.removeSuffix(".bak") }
            .filter { it.endsWith(SUFFIX) }.map { it.removeSuffix(SUFFIX) }.distinct().sorted()
        for (id in ids) {
            if (!validId(id) || id in builtIns) { unreadable.add(id); continue }
            try { good.add(ThemeLibraryEntry(checkNotNull(loadLocked(id)).metadata, false)) }
            catch (_: Exception) { unreadable.add(id) }
        }
        ThemeLibrary(good, unreadable)
    }

    fun load(id: String): ThemePackage? = transaction { loadLocked(id) }

    /** expectedRevision=null只允许新建；任何旧编辑窗口都不能覆盖另一个窗口刚保存的版本。 */
    fun save(draft: ThemePackage, expectedRevision: Long?): ThemePackage = transaction {
        val id = draft.metadata.id
        require(id !in builtIns) { "theme_builtin_requires_copy" }
        val previous = loadLocked(id)
        if (previous?.metadata?.revision != expectedRevision) throw ThemeRevisionConflict()
        val revision = if (previous == null) 1 else Math.addExact(previous.metadata.revision, 1)
        val result = ThemePackageCodec.withMetadata(draft, draft.metadata.copy(revision = revision))
        writeAtomic(file(id), ThemePackageCodec.bytes(result))
        result
    }

    /** 导入和另存一律分配自有ID；外部包不能覆盖内置模板或本机同名主题。 */
    fun import(input: InputStream): ThemePackage {
        val parsed = ThemePackageCodec.read(input)
        return import(parsed)
    }
    fun import(parsed: ThemePackage): ThemePackage=transaction { createCopyLocked(parsed,parsed.metadata.name) }

    fun duplicate(id: String, name: String): ThemePackage = transaction {
        createCopyLocked(requireNotNull(loadLocked(id)) { "theme_missing" }, name)
    }

    fun rename(id: String, name: String, expectedRevision: Long): ThemePackage = transaction {
        require(id !in builtIns) { "theme_builtin_requires_copy" }
        val previous = requireNotNull(loadLocked(id)) { "theme_missing" }
        if (previous.metadata.revision != expectedRevision) throw ThemeRevisionConflict()
        val result = ThemePackageCodec.withMetadata(previous,
            previous.metadata.copy(name = name, revision = Math.addExact(expectedRevision, 1)))
        writeAtomic(file(id), ThemePackageCodec.bytes(result))
        result
    }

    /** 场景编译/资源准备成功后由运行时调用；具体组件合法性不由包存储代验。 */
    fun apply(id: String, expectedRevision: Long): ThemePackage = transaction {
        val prepared = requireNotNull(loadLocked(id)) { "theme_missing" }
        if (prepared.metadata.revision != expectedRevision) throw ThemeRevisionConflict()
        writeAtomic(activeFile(), ThemePackageCodec.bytes(prepared))
        prepared
    }

    fun active(): ThemePackage? = transaction {
        val stored = read(activeFile()) ?: return@transaction null
        // 内置主题本身不可编辑；用户副本具有独立ID，升级只更新选中的旧内置版本。
        val updated = builtIns[stored.metadata.id]?.takeIf { it.metadata.revision > stored.metadata.revision }
        if (updated != null) writeAtomic(activeFile(), ThemePackageCodec.bytes(updated))
        updated ?: stored
    }
    fun syncEnergy(): ThemePackage? = transaction {
        val original=read(activeFile())?:return@transaction null
        val next=EnergyThemeSync.merge(original)
        if(next===original)return@transaction original
        val backup=File(root,"backups/${original.metadata.id}-before-energy-sync.gctheme")
        backup.parentFile?.mkdirs()
        if(!backup.exists())writeAtomic(AtomicFile(backup),ThemePackageCodec.bytes(original))
        val library=loadLocked(original.metadata.id)
        val entries=original.copyEntries()
        val matches=library?.copyEntries()?.let{other->entries.keys==other.keys&&entries.all{(key,bytes)->bytes.contentEquals(other.getValue(key))}}==true
        if(original.metadata.id !in builtIns&&matches)writeAtomic(file(original.metadata.id),ThemePackageCodec.bytes(next))
        writeAtomic(activeFile(),ThemePackageCodec.bytes(next))
        next
    }

    fun delete(id: String, expectedRevision: Long) = transaction {
        require(id !in builtIns) { "theme_builtin_readonly" }
        val previous = requireNotNull(loadLocked(id)) { "theme_missing" }
        if (previous.metadata.revision != expectedRevision) throw ThemeRevisionConflict()
        check(read(activeFile())?.metadata?.id != id) { "theme_active_select_another_first" }
        val target = file(id)
        target.delete()
        if (target.baseFile.exists() || File(target.baseFile.path + ".bak").exists()) throw IOException("theme_delete_failed")
    }

    private fun createCopyLocked(source: ThemePackage, name: String): ThemePackage {
        val metadata = ThemeMetadata("theme-${UUID.randomUUID()}", name, 1)
        val result = ThemePackageCodec.withMetadata(source, metadata)
        writeAtomic(file(metadata.id), ThemePackageCodec.bytes(result))
        return result
    }

    private fun loadLocked(id: String): ThemePackage? {
        require(validId(id)) { "theme_id_invalid" }
        val result = builtIns[id] ?: read(file(id))
        check(result == null || result.metadata.id == id) { "theme_file_id_mismatch" }
        return result
    }

    private fun read(file: AtomicFile): ThemePackage? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().use { ThemePackageCodec.read(it) }
    }

    private fun file(id: String): AtomicFile { require(validId(id)); return AtomicFile(File(root, id + SUFFIX)) }
    private fun activeFile() = AtomicFile(File(root, "active.snapshot"))
    private fun <T> transaction(action: () -> T): T = synchronized(mutex) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("theme_directory_unavailable")
        RandomAccessFile(File(root, ".library.lock"), "rw").use { handle ->
            handle.channel.lock().use { action() }
        }
    }

    companion object {
        private const val SUFFIX = ".gctheme"
        private val locks = ConcurrentHashMap<String, Any>()
        private fun validId(id: String) = id.matches(Regex("[a-z][a-z0-9-]{0,79}"))
        private fun commit(file: AtomicFile, bytes: ByteArray) {
            val output = file.startWrite()
            try { output.write(bytes); output.flush(); output.fd.sync(); file.finishWrite(output) }
            catch (error: Throwable) { file.failWrite(output); throw error }
        }
    }
}
