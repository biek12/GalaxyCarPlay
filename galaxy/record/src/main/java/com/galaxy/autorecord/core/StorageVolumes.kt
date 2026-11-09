package com.galaxy.autorecord.core

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 存储卷枚举与可写探测。
 *
 * 纪律（docs/AutoRecord编码与存储技术口径-20261103.md §4）：
 * - 持久化只存卷 UUID，不存绝对路径；解析不到即"卷已移除"，不得静默回切内置。
 * - externalFilesDirs 的下标顺序与 storageVolumes 顺序不保证一致，必须用卷目录前缀匹配绑定。
 * - 可写探测真的写一个 .probe 文件并 fsync，再删除；不用 canWrite() 当结论。
 */
data class VolumeInfo(
    /** null＝内置存储；否则为卷 UUID。 */
    val id: String?,
    val label: String,
    val removable: Boolean,
    val mounted: Boolean,
    /** 本 App 在该卷上的落盘目录；不可用时为 null。 */
    val appDir: File?,
    val freeBytes: Long,
    val totalBytes: Long,
    val writable: Boolean,
) {
    val isUsable: Boolean get() = mounted && appDir != null && writable
}

interface StorageVolumes {
    fun list(): List<VolumeInfo>

    /** 按 UUID 解析；null＝内置。卷已移除时返回 null，调用方必须显式报错。 */
    fun resolve(uuid: String?): VolumeInfo?

    /** 真写一次探测文件（1 字节 + fsync + 删除）。 */
    fun probeWritable(dir: File): Boolean
}

class AndroidStorageVolumes(context: Context) : StorageVolumes {
    private val app = context.applicationContext
    private val packageName = app.packageName

    override fun list(): List<VolumeInfo> {
        val result = mutableListOf<VolumeInfo>()
        result += internalVolume()
        val manager = app.getSystemService(StorageManager::class.java) ?: return result
        val appDirs = app.getExternalFilesDirs(null).filterNotNull()
        for (volume in manager.storageVolumes) {
            if (!volume.isRemovable) continue
            val dir = volumeDirectory(volume)
            // 无稳定 UUID 的可移除介质不能保存为 null，否则下次解析会悄悄变成内置存储。
            val appDir = if (volume.uuid.isNullOrBlank()) null else bindAppDir(volume, dir, appDirs)
            result += VolumeInfo(
                id = volume.uuid?.takeIf { it.isNotBlank() },
                label = runCatching { volume.getDescription(app) }.getOrDefault("U 盘"),
                removable = true,
                mounted = volume.state == android.os.Environment.MEDIA_MOUNTED,
                appDir = appDir,
                freeBytes = appDir?.usableSpace ?: 0L,
                totalBytes = appDir?.totalSpace ?: 0L,
                writable = appDir?.canWrite() == true,
            )
        }
        return result
    }

    override fun resolve(uuid: String?): VolumeInfo? =
        list().firstOrNull { it.id == uuid }

    override fun probeWritable(dir: File): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val probe = File(dir, ".probe_${System.nanoTime()}")
        return try {
            FileOutputStream(probe).use { out ->
                out.write(1)
                out.flush()
                out.fd.sync()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "可写探测失败：${dir.absolutePath}", e)
            false
        } finally {
            probe.delete()
        }
    }

    private fun internalVolume(): VolumeInfo {
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        return VolumeInfo(
            id = null,
            label = "内置存储",
            removable = false,
            mounted = true,
            appDir = dir,
            freeBytes = dir.usableSpace,
            totalBytes = dir.totalSpace,
            writable = dir.canWrite(),
        )
    }

    @Suppress("DEPRECATION")
    private fun volumeDirectory(volume: StorageVolume): File? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volume.directory else null

    private fun bindAppDir(volume: StorageVolume, volumeDir: File?, appDirs: List<File>): File? {
        if (volumeDir == null) return null
        val root = runCatching { volumeDir.canonicalPath }.getOrNull() ?: return null
        appDirs.forEach { candidate ->
            val path = runCatching { candidate.canonicalPath }.getOrNull() ?: return@forEach
            if (path == root || path.startsWith(root + File.separator)) return candidate
        }
        // 系统尚未为本 App 在该卷建目录时给出预期路径；由探测步骤确认可写性。
        return File(volumeDir, "Android/data/$packageName/files")
    }

    private companion object {
        const val TAG = "AutoRecordStorage"
    }
}
