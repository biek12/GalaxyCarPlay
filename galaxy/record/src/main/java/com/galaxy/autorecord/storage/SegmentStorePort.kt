package com.galaxy.autorecord.storage

import com.galaxy.autorecord.core.SegmentManifest
import java.io.File

/**
 * 段事务对磁盘的端口（依赖反转）。
 *
 * 真实实现 [RealSegmentStorePort] 委托既有 AtomicFiles/SegmentLayout；
 * 测试用假实现模拟空文件、改名失败、清单写入失败等，不触真文件系统/Android。
 *
 * 设计依据（docs/AutoRecord编码与存储技术口径-20261103.md §3/§4/§6）：
 * - 收尾顺序固定为 编码 EOS → drain → muxer.stop/release → fsync → 原子改名 → 写清单；
 *   本端口只负责后三段（fsync/改名/清单），前三段属于真实写入器（下一批）。
 * - 改名与清单写入必须可失败且被上层感知；失败不得静默吞掉。
 */
interface SegmentStorePort {
    /** 段目录是否已存在（用于"不复用既有同名段"）。 */
    fun segmentDirExists(dir: File): Boolean

    /** 创建段目录（含父级）。失败返回 false，由上层报错。 */
    fun ensureSegmentDir(dir: File): Boolean

    /** 原子发布段清单（writing 或最终）。返回是否成功。 */
    fun writeManifest(segmentDir: File, manifest: SegmentManifest): Boolean

    /**
     * 视频收尾：`<name>.mp4.writing` → `<name>.mp4`（先 fsync 再原子改名）。
     * 成功返回 true；失败（文件缺失/空/改名失败）返回 false，上层保留 `.writing/.partial` 证据。
     */
    fun commitVideo(video: File): Boolean

    /** 取文件字节数（用于空文件校验；不存在返回 0）。 */
    fun fileLength(file: File): Long

    /** 文件是否存在（用于证据保留判定）。 */
    fun fileExists(file: File): Boolean
}
