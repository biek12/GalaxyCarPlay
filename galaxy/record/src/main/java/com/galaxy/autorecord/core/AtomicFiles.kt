package com.galaxy.autorecord.core

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 原子落盘：写临时文件 → fsync → 同目录原子改名。
 *
 * 依据 docs/AutoRecord编码与存储技术口径-20261103.md §6：
 * 片段收尾顺序为 编码器 EOS → drain → muxer.stop() → release() → fsync → 原子改名 → 写索引。
 * 索引可重建；sidecar 与视频文件是真相。崩溃后残留的 `.writing/.partial` 必须被识别，
 * 不能当成"已完成的片段"出现在录像库里。
 */
object AtomicFiles {
    /**
     * 把 [bytes] 原子写入 [target]：成功后 [target] 内容完整或保持旧内容，不存在半截文件。
     *
     * @param fsyncDir 是否对父目录也 fsync（新目录首次落盘时用于确保目录项持久）
     */
    fun writeAtomically(target: File, bytes: ByteArray, fsyncDir: Boolean = false): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) {
            Log.w(TAG, "创建目录失败：${parent.absolutePath}")
            return false
        }
        val tmp = runCatching { File.createTempFile(".${target.name}.", ".tmp", parent) }.getOrElse { return false }
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            // 同目录原子覆盖失败就保留旧文件；绝不能先删除旧清单再尝试改名。
            java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            if (fsyncDir) fsyncDirectory(parent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "写入失败：${target.absolutePath}", e)
            tmp.delete()
            false
        }
    }

    /** 目录 fsync；平台不保证支持，失败只记录不抛。 */
    fun fsyncDirectory(dir: File) {
        runCatching {
            val fd = android.system.Os.open(dir.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
        }.onFailure { Log.d(TAG, "目录 fsync 不受支持：${dir.absolutePath}") }
    }

    /** 视频文件收尾：`.writing` → 最终名。失败即视为该路未完成，保留 .writing/.partial。 */
    fun commitVideo(video: File): Boolean {
        val writing = SegmentLayout.writingFile(video)
        if (!writing.exists()) return video.isFile && video.length() > 0
        if (!writing.isFile || writing.length() == 0L) return false
        try { java.io.RandomAccessFile(writing, "rw").use { it.fd.sync() } }
        catch (error: IOException) { Log.w(TAG, "视频同步失败", error); return false }
        return if (writing.renameTo(video)) {
            fsyncDirectory(video.parentFile ?: return false)
            true
        } else {
            Log.w(TAG, "视频改名失败：${writing.absolutePath} → ${video.absolutePath}")
            false
        }
    }

    private const val TAG = "AutoRecordFiles"
}
