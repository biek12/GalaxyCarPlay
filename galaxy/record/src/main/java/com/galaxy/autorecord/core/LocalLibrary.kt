package com.galaxy.autorecord.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 一天的分组（P2 列表按日期分组，组头显示日期 + 片段数）。 */
data class DayGroup(val dayMillis: Long, val label: String, val segments: List<SegmentEntry>)

/**
 * 录像库状态。
 *
 * 关键区分（原稿最容易搞错的地方）：**"还没扫描"、"扫描失败"、"扫描成功但为空"是三件事**。
 * 界面必须分别显示，只有第三种才允许出现"还没有录像"的空态。
 */
data class LibraryState(
    val segments: List<SegmentEntry> = emptyList(),
    val error: String? = null,
    val rootExists: Boolean = false,
    val scanned: Boolean = false,
) {
    val isEmptyButKnown: Boolean get() = scanned && error == null && segments.isEmpty()

    val totalBytes: Long get() = segments.sumOf { it.bytes }
    val protectedCount: Int get() = segments.count { it.isProtected }
    val abnormalCount: Int get() = segments.count { it.completeness.isAbnormal }
    val eventBearingCount: Int get() = segments.count { it.isEventBearing }

    /** 应用筛选与时间条件；排序始终按时间倒序（规格 §5 P2）。 */
    fun query(
        filter: LibraryFilter,
        range: LibraryDateRange = LibraryDateRange.ALL,
        period: LibraryPeriod = LibraryPeriod.ALL,
        nowMillis: Long = System.currentTimeMillis(),
        window: LibraryTimeWindow? = null,
    ): List<SegmentEntry> {
        val todayStart = startOfDay(nowMillis)
        val dayMillis = 24L * 60 * 60 * 1000
        return segments.asSequence()
            .filter { filter.matches(it) }
            .filter { window?.matches(it) != false }
            .filter { entry ->
                when (range) {
                    LibraryDateRange.ALL -> true
                    LibraryDateRange.TODAY -> entry.startedAtMillis >= todayStart
                    LibraryDateRange.YESTERDAY ->
                        entry.startedAtMillis >= todayStart - dayMillis && entry.startedAtMillis < todayStart
                    LibraryDateRange.WEEK -> entry.startedAtMillis >= todayStart - 6 * dayMillis
                }
            }
            .filter { entry ->
                if (period == LibraryPeriod.ALL) true
                else {
                    val hour = hourOf(entry.startedAtMillis)
                    hour >= period.fromHour && hour < period.toHour
                }
            }
            .sortedByDescending { it.startedAtMillis }
            .toList()
    }

    /** 按自然日分组（组头用"今天/昨天/yyyy-MM-dd"）。 */
    fun groupByDay(entries: List<SegmentEntry>, nowMillis: Long = System.currentTimeMillis()): List<DayGroup> {
        val today = startOfDay(nowMillis)
        val dayMillis = 24L * 60 * 60 * 1000
        return entries.groupBy { startOfDay(it.startedAtMillis) }
            .toSortedMap(compareByDescending { it })
            .map { (day, list) ->
                val label = when (day) {
                    today -> "今天"
                    today - dayMillis -> "昨天"
                    else -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(day))
                }
                DayGroup(day, label, list.sortedByDescending { it.startedAtMillis })
            }
    }

    fun find(id: String): SegmentEntry? = segments.firstOrNull { it.id == id }

    /** 相邻片段（跨段回放用）：同会话内时间上紧邻且间隔 ≤ 1 个分段时长。 */
    fun neighbors(entry: SegmentEntry, segmentSeconds: Int): Pair<SegmentEntry?, SegmentEntry?> {
        val sorted = segments.sortedBy { it.startedAtMillis }
        val index = sorted.indexOfFirst { it.id == entry.id }
        if (index < 0) return null to null
        val gap = segmentSeconds.coerceAtLeast(1) * 1000L
        val prev = sorted.getOrNull(index - 1)?.takeIf {
            entry.startedAtMillis - (it.startedAtMillis + it.durationMillis) <= gap
        }
        val next = sorted.getOrNull(index + 1)?.takeIf {
            it.startedAtMillis - (entry.startedAtMillis + entry.durationMillis) <= gap
        }
        return prev to next
    }

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun hourOf(millis: Long): Int = Calendar.getInstance().apply { timeInMillis = millis }
        .get(Calendar.HOUR_OF_DAY)
}

/**
 * 本地录像库加载：扫描段目录 + 合并锁定/已看标记。
 *
 * 顺序固定：先扫目录（事实），再读标记（用户意图），最后把两者合并成条目。
 * 任一环节失败都要带原因返回，不能把失败折叠成"空库"。
 */
object LocalLibrary {
    fun load(baseDir: File?, marks: SegmentMarks?, volumeLabel: String): LibraryState {
        if (baseDir == null) {
            return LibraryState(scanned = true, error = "存储卷不可用，无法列出片段")
        }
        val segmentsRoot = SegmentLayout.segmentsRoot(baseDir)
        val scan = SegmentScanner.scan(segmentsRoot)
        val locks = runCatching { marks?.locks() }.getOrNull() ?: emptySet()
        val played = runCatching { marks?.played() }.getOrNull() ?: emptySet()
        val entries = scan.segments.map { entry ->
            entry.copy(
                locked = entry.id in locks,
                seen = entry.id in played,
                volumeLabel = volumeLabel,
            )
        }
        return LibraryState(
            segments = entries,
            error = scan.error,
            rootExists = scan.rootExists,
            scanned = true,
        )
    }
}
