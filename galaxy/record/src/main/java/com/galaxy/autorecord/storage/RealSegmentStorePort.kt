package com.galaxy.autorecord.storage

import com.galaxy.autorecord.core.AtomicFiles
import com.galaxy.autorecord.core.SegmentLayout
import com.galaxy.autorecord.core.SegmentManifest
import java.io.File
import java.nio.file.Files

/**
 * 真实磁盘端口：复用既有 [AtomicFiles]（fsync + 同卷原子改名）与 [SegmentLayout]，
 * **不复制**任何落盘/改名实现。仅在本工程 Android 模块内编译运行。
 *
 * 收尾顺序严格遵循技术口径 §3/§4：先 [AtomicFiles.commitVideo]（内部先 fd.sync 再 renameTo），
 * 再由上层调用 [writeManifest]（内部 writeAtomically：tmp → fsync → ATOMIC_MOVE）。
 * 任一失败返回 false，由 [SegmentTransaction] 保留证据并标 partial/failed。
 *
 * 原子独占创建（R1-2）：[ensureSegmentDir] 用 [Files.createDirectory] 做独占创建，
 * 目录已存在即失败；两事务并发时只有一方成功，后者不会复用前者目录去覆盖清单。
 */
class RealSegmentStorePort : SegmentStorePort {
    override fun segmentDirExists(dir: File): Boolean = dir.exists()

    override fun ensureSegmentDir(dir: File): Boolean {
        val parent = dir.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return false
        return try {
            Files.createDirectory(dir.toPath())
            true
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            false
        }
    }

    override fun writeManifest(segmentDir: File, manifest: SegmentManifest): Boolean {
        val file = File(segmentDir, SegmentLayout.FILE_MANIFEST)
        return AtomicFiles.writeAtomically(
            file,
            manifest.toJson().toString().toByteArray(),
            fsyncDir = true,
        )
    }

    override fun commitVideo(video: File): Boolean = AtomicFiles.commitVideo(video)

    override fun fileLength(file: File): Long {
        return try {
            Files.size(file.toPath())
        } catch (e: java.nio.file.NoSuchFileException) {
            0L
        }
    }

    override fun fileExists(file: File): Boolean = file.exists()
}
