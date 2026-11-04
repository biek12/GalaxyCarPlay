package com.galaxy.autorecord.recording

import com.galaxy.autorecord.core.SegmentCompleteness
import com.galaxy.autorecord.core.SegmentEntry
import com.galaxy.autorecord.core.SegmentMarks
import com.galaxy.autorecord.core.SegmentScanner
import java.io.File

/**
 * 循环覆盖腾空间（技术口径 §4、页面规格 §14.3）。
 *
 * 判定顺序：配额与剩余空间不足时删最旧且**未锁定、未在写、未导出、非事件保护**的段；
 * 单轮最多删 [maxDelete] 段；受保护段永不删、也不静默关闭保护。
 * 无"可删片段"（配额全被锁定/事件保护占满）→ 返回 [Result.blockedReason] 由界面报
 * "受保护片段占满配额（锁定 X 段 / 事件 Y 段）"，给用户管理入口，**不删受保护片段**。
 *
 * 只删除本产品自己的段目录（AutoRecord/segments/...），绝不触碰用户其他文件。
 */
object RotationJanitor {

    data class Result(
        val deleted: List<String>,
        val freedBytes: Long,
        /** 非 null 表示空间仍不足且无法继续：原因可直接展示。 */
        val blockedReason: String?,
        val protectedLocked: Int,
        val protectedEvent: Int,
    )

    /**
     * @param quotaBytes 配额
     * @param freeBytes 介质剩余
     * @param minFreeBytes 绝对下限（S-STOR-04）
     * @param incomingBytes 即将写入的段预估字节
     *
     * 已用字节以本函数扫描段目录的实际大小为准（与删除回补同单位，避免账实不符）。
     */
    fun makeRoom(
        segmentsRoot: File,
        indexDir: File,
        quotaBytes: Long,
        freeBytes: Long,
        minFreeBytes: Long,
        incomingBytes: Long,
        rotate: Boolean,
        eventProtect: Boolean,
        maxDelete: Int = 8,
    ): Result {
        val scan = SegmentScanner.scan(segmentsRoot)
        var used = scan.segments.sumOf { it.bytes }
        var free = freeBytes
        val deleted = mutableListOf<String>()

        fun shortOfSpace(): Boolean =
            used + incomingBytes > quotaBytes || free - minFreeBytes < incomingBytes

        if (!shortOfSpace()) return Result(emptyList(), 0L, null, 0, 0)

        if (!rotate) {
            return Result(
                emptyList(), 0L,
                "循环覆盖已关闭：空间不足（需 ${incomingBytes / MB} MB），请删除片段或改配额",
                0, 0,
            )
        }

        val marks = SegmentMarks(indexDir)
        val locks = runCatching { marks.locks() }.getOrDefault(emptySet())
        val candidates = scan.segments
            .filter { it.completeness != SegmentCompleteness.WRITING }
            .filter { it.id !in locks }
            .filter { !(eventProtect && it.isEventBearing) }
            .sortedBy { it.startedAtMillis }

        val protectedLocked = scan.segments.count { it.id in locks }
        val protectedEvent = scan.segments.count { it.isEventBearing && it.id !in locks }

        for (entry in candidates) {
            if (deleted.size >= maxDelete) break
            if (!shortOfSpace()) break
            if (deleteSegment(entry)) {
                deleted += entry.id
                used -= entry.bytes
                free += entry.bytes
            }
        }

        if (!shortOfSpace()) return Result(deleted, freedOf(deleted, scan.segments), null, protectedLocked, protectedEvent)

        val reason = if (candidates.isEmpty() || deleted.size >= candidates.size) {
            "受保护片段占满配额（锁定 $protectedLocked 段 / 事件 $protectedEvent 段）：请管理锁定片段、关闭事件保护或改配额"
        } else {
            "空间不足：已删 ${deleted.size} 段仍不够写下一段（需 ${incomingBytes / MB} MB）"
        }
        return Result(deleted, freedOf(deleted, scan.segments), reason, protectedLocked, protectedEvent)
    }

    private fun freedOf(deleted: List<String>, entries: List<SegmentEntry>): Long =
        deleted.sumOf { id -> entries.firstOrNull { it.id == id }?.bytes ?: 0L }

    /** 删除单个段目录（本产品自有数据）。失败返回 false，不重试、不吞掉。 */
    private fun deleteSegment(entry: SegmentEntry): Boolean {
        val dir = entry.dir
        // 防御：只允许删除 segments 根下的段目录
        if (!dir.isDirectory) return false
        if (dir.name != entry.id) return false
        return runCatching { dir.deleteRecursively() }.getOrDefault(false) && !dir.exists()
    }

    private const val MB = 1024L * 1024L
}
