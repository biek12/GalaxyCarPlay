package com.galaxy.autorecord.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 片段完整度（规格 v1.1 §5 P2 角标、§8 状态矩阵）。
 *
 * 纪律：写入中、可恢复、部分有效、文件缺失、损坏**都不得**显示成正常"已播放/未播放"态；
 * 判定只依赖磁盘事实（后缀、manifest、终名文件），不依赖内存状态。
 */
enum class SegmentCompleteness(val label: String) {
    COMPLETE("完整"),
    RECOVERABLE("可恢复"),
    PARTIAL("部分有效"),
    MISSING_FILE("文件缺失"),
    WRITING("写入中"),
    CORRUPT("损坏"),
    ;

    val isAbnormal: Boolean get() = this != COMPLETE
}

/** 录像库主筛选（v1.1 任务导向，替代原 8 项工程状态筛选）。 */
enum class LibraryFilter(val label: String) {
    ALL("全部"),
    IMPORTANT("重要"),
    ABNORMAL("异常"),
    UNSEEN("未看"),
    ;

    fun matches(entry: SegmentEntry): Boolean = when (this) {
        ALL -> entry.completeness != SegmentCompleteness.WRITING
        IMPORTANT -> entry.locked || entry.isEventBearing
        ABNORMAL -> entry.completeness.isAbnormal && entry.completeness != SegmentCompleteness.WRITING
        UNSEEN -> !entry.locked && !entry.seen && !entry.completeness.isAbnormal
    }
}

/** 日期范围（"更多筛选"里的次级条件）。 */
enum class LibraryDateRange(val label: String, val days: Int) {
    ALL("全部日期", -1),
    TODAY("今天", 0),
    YESTERDAY("昨天", 1),
    WEEK("最近 7 天", 7),
}

/** 时段。 */
enum class LibraryPeriod(val label: String, val fromHour: Int, val toHour: Int) {
    ALL("全部时段", 0, 24),
    NIGHT("凌晨 00–06", 0, 6),
    MORNING("上午 06–12", 6, 12),
    AFTERNOON("下午 12–18", 12, 18),
    EVENING("夜间 18–24", 18, 24),
}

/**
 * 单个片段（界面只读模型）。
 *
 * @param id 段目录名 `yyyyMMdd-HHmmss-NNN`，同时是锁定/已看的持久化键
 * @param durationMillis 取自 manifest；无 manifest 时为 0（界面显示"—"而不是猜）
 * @param missingDirections manifest 里 status 非 complete 的方向，或终名文件缺失的方向
 */
data class SegmentEntry(
    val id: String,
    val dir: File,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val bytes: Long,
    val completeness: SegmentCompleteness,
    val missingDirections: List<String> = emptyList(),
    val eventCount: Int = 0,
    val hasVehicleData: Boolean = false,
    val locked: Boolean = false,
    val seen: Boolean = false,
    val volumeLabel: String = "内置存储",
    val encoderSummary: String = "",
    val resolutionSummary: String = "",
    val bitrateSummary: String = "",
    val ruleVersion: String? = null,
) {
    /** 含事件：参与保留保护（规格 v1.1 §5 P9 分组卡 4）。 */
    val isEventBearing: Boolean get() = eventCount > 0

    /** 受保护 = 已锁定或含事件；二者都不参与循环覆盖。 */
    val isProtected: Boolean get() = locked || isEventBearing

    /** 可被自动覆盖删除：受保护、写入中、损坏段都不在其中。 */
    val deletableByRotation: Boolean
        get() = !isProtected && completeness != SegmentCompleteness.WRITING

    /** 未看角标：只有完整且没有异常的片段才谈得上"看没看过"。 */
    val showUnseenBadge: Boolean get() = !seen && !completeness.isAbnormal

    /**
     * 角标优先级（v1.1 §5 P2）：文件缺失 > 部分有效 > 可恢复 > 缺路 > 已锁定 > 含事件 > 未播放 > 含车辆数据。
     * 界面最多显示两个，其余收进 `+N`。
     */
    fun badges(): List<String> = buildList {
        when (completeness) {
            SegmentCompleteness.MISSING_FILE -> add("文件缺失")
            SegmentCompleteness.PARTIAL -> add("部分有效")
            SegmentCompleteness.RECOVERABLE -> add("可恢复")
            SegmentCompleteness.CORRUPT -> add("损坏")
            SegmentCompleteness.WRITING -> add("写入中")
            SegmentCompleteness.COMPLETE -> Unit
        }
        if (missingDirections.isNotEmpty()) add("缺路 ${missingDirections.joinToString("/") { directionLabel(it) }}")
        if (locked) add("已锁定")
        if (isEventBearing) add("含事件 $eventCount")
        if (showUnseenBadge) add("未播放")
        if (hasVehicleData) add("含车辆数据")
    }
}

/** 方向中文名（core 不依赖 ui，放这里的唯一副本，ui 侧同名函数转调）。 */
internal fun directionLabel(direction: String): String = when (direction) {
    "front" -> "前"
    "rear" -> "后"
    "left" -> "左"
    "right" -> "右"
    else -> direction
}

/** 扫描结果：既给出条目，也给出"扫描本身是否成功"，避免把失败当成空库。 */
data class ScanResult(
    val segments: List<SegmentEntry>,
    /** null 表示扫描成功；非 null 是失败原因（目录不可读等）。 */
    val error: String? = null,
    /** 目录是否存在。不存在 ≠ 空库：首次运行目录尚未创建。 */
    val rootExists: Boolean = false,
)

/**
 * 段目录扫描器：**纯函数式**，只读磁盘，不写任何东西，便于单测。
 *
 * 真相来源是段目录本身（技术口径 §6）：manifest + 四路终名文件 + 后缀。
 * 索引是可重建的缓存，所以这里不做增量，直接全量扫（段数量级 10^3，可接受）。
 */
object SegmentScanner {
    private const val TAG = "AutoRecordScan"

    fun scan(segmentsRoot: File): ScanResult {
        if (!segmentsRoot.exists()) return ScanResult(emptyList(), rootExists = false)
        if (!segmentsRoot.isDirectory) return ScanResult(emptyList(), error = "段目录不可读", rootExists = false)
        val entries = mutableListOf<SegmentEntry>()
        val dayDirs = segmentsRoot.listFiles()?.filter { it.isDirectory } ?: return ScanResult(
            emptyList(),
            error = "段目录无法列出",
            rootExists = true,
        )
        for (dayDir in dayDirs) {
            val segmentDirs = dayDir.listFiles()?.filter { it.isDirectory } ?: continue
            for (dir in segmentDirs) {
                runCatching { readSegment(dir) }
                    .onSuccess { if (it != null) entries += it }
                    .onFailure { Log.w(TAG, "段目录解析失败：${dir.name}", it) }
            }
        }
        return ScanResult(entries.sortedByDescending { it.startedAtMillis }, rootExists = true)
    }

    internal fun readSegment(dir: File): SegmentEntry? {
        val files = dir.listFiles() ?: return null
        val byName = files.associateBy { it.name }

        val writing = files.any { it.name.endsWith(SegmentLayout.SUFFIX_WRITING) }
        val partial = files.any { it.name.endsWith(SegmentLayout.SUFFIX_PARTIAL) }
        val presentDirections = SegmentLayout.DIRECTIONS.filter { byName.containsKey("$it.mp4") }
        val partialDirections = SegmentLayout.DIRECTIONS.filter { byName.containsKey("$it.mp4" + SegmentLayout.SUFFIX_PARTIAL) }

        val manifestFile = byName[SegmentLayout.FILE_MANIFEST]
        val manifest = manifestFile?.let { parseManifest(it) }
        val manifestBroken = manifestFile != null && manifest == null
        // 终态清单下残留的 `.writing` 是失败路证据（保留可查），不是"仍在写入"；
        // 只有清单未落终态（缺失或仍 writing）时 .writing 才表示写入中/崩溃残留。
        val manifestTerminal = manifest != null && manifest.state != SegmentManifest.STATE_WRITING
        // 下一段已提前建清单但尚无视频文件时仍在写入流程，不能当作异常可删片段。
        val activeWriting = manifest?.state == SegmentManifest.STATE_WRITING || (writing && !manifestTerminal)

        val completeness = when {
            manifestBroken -> SegmentCompleteness.CORRUPT
            activeWriting -> SegmentCompleteness.WRITING
            partial || partialDirections.isNotEmpty() -> SegmentCompleteness.PARTIAL
            manifest == null && presentDirections.isNotEmpty() -> SegmentCompleteness.RECOVERABLE
            manifest == null && presentDirections.isEmpty() -> null
            else -> {
                val m = manifest!!
                val completeChannels = m.channels.count { it.status == SegmentManifest.CHANNEL_COMPLETE }
                val declaredCompleteButGone = m.channels.any {
                    it.status == SegmentManifest.CHANNEL_COMPLETE && it.direction !in presentDirections
                }
                when {
                    declaredCompleteButGone -> SegmentCompleteness.MISSING_FILE
                    completeChannels == 0 -> SegmentCompleteness.PARTIAL // failed 段：无可播内容但证据保留
                    completeChannels < m.channels.size -> SegmentCompleteness.PARTIAL
                    else -> SegmentCompleteness.COMPLETE
                }
            }
        } ?: return null

        val missing = when {
            manifest != null -> manifest.channels.filter { it.status != "complete" }.map { it.direction }
                .ifEmpty { SegmentLayout.DIRECTIONS.filter { it !in presentDirections } }
            else -> SegmentLayout.DIRECTIONS.filter { it !in presentDirections }
        }.distinct()

        val firstChannel = manifest?.channels?.firstOrNull()
        return SegmentEntry(
            id = dir.name,
            dir = dir,
            startedAtMillis = manifest?.startedAtWallMillis ?: parseDirStamp(dir.name) ?: dir.lastModified(),
            durationMillis = manifest?.durationMillis ?: 0L,
            // 一次NIO属性读取同时取得类型/长度，避免FUSE上isFile+length双倍往返。
            bytes = files.sumOf { file -> runCatching {
                val attributes=java.nio.file.Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java)
                if(attributes.isRegularFile)attributes.size()else 0L
            }.getOrDefault(0L) },
            completeness = completeness,
            missingDirections = missing,
            eventCount = (manifest?.events?.count ?: 0)+runCatching{EventReader.sentinelEvents(dir).size}.getOrDefault(0),
            hasVehicleData = manifest?.vehicle != null,
            encoderSummary = firstChannel?.let { "${it.encoderName}（${implLabel(it.implementation)}）" } ?: "",
            resolutionSummary = firstChannel?.let { "${it.width}×${it.height} @${it.fps}fps" } ?: "",
            bitrateSummary = firstChannel?.let { String.format(Locale.US, "%.1f Mbps/路", it.bitrateBps / 1_000_000.0) } ?: "",
            ruleVersion = manifest?.events?.ruleVersion,
        )
    }

    private fun implLabel(implementation: String): String = when (implementation) {
        RecordSettings.IMPL_HARDWARE -> "硬编"
        RecordSettings.IMPL_SOFTWARE -> "软编"
        else -> "未知实现"
    }

    private fun parseManifest(file: File): SegmentManifest? = runCatching {
        SegmentManifest.fromJson(file.readText())
    }.getOrNull()

    /** 目录名 `yyyyMMdd-HHmmss-NNN` → 毫秒时间戳；解析不出来返回 null（不猜当前时间）。 */
    fun parseDirStamp(name: String): Long? = runCatching {
        val stamp = name.substringBeforeLast('-')
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
            isLenient = false
        }.parse(stamp)?.time
    }.getOrNull()
}

/**
 * 锁定与已看状态（可重建索引之外的**用户意图**，必须持久化）。
 *
 * 放在 `index/locks.json` 与 `index/played.json`：两者都是用户操作的结果，不能像索引那样
 * 随便重建丢失；索引损坏时它们仍可读，据此恢复角标。
 */
class SegmentMarks(private val indexDir: File) {
    fun locks(): Set<String> = readSet(LOCK_FILE)
    fun played(): Set<String> = readSet(PLAYED_FILE)

    /** 返回写入后的集合；失败返回 null（调用方必须报错，不能假装已锁）。 */
    fun setLocked(ids: Collection<String>, locked: Boolean): Set<String>? = synchronized(SegmentAccess.gate) {
        if (ids.any { SegmentAccess.deletingId(it) }) return@synchronized null
        val next = locks().toMutableSet()
        if (locked) next += ids else next -= ids.toSet()
        if (writeSet(LOCK_FILE, next)) next else null
    }

    fun forget(ids: Collection<String>): Boolean = synchronized(SegmentAccess.gate) {
        val removed=ids.toSet()
        writeSet(LOCK_FILE,locks()-removed) && writeSet(PLAYED_FILE,played()-removed)
    }

    fun markPlayed(id: String): Boolean = synchronized(SegmentAccess.gate) {
        !SegmentAccess.deletingId(id) && writeSet(PLAYED_FILE, played() + id)
    }

    private fun readSet(name: String): Set<String> {
        val file = File(indexDir, name)
        if (!file.exists()) return emptySet()
        return runCatching {
            val array = JSONArray(file.readText())
            buildSet { for (i in 0 until array.length()) add(array.optString(i)) }
        }.getOrElse {
            Log.w("AutoRecordMarks", "读取 $name 失败，按空集处理", it)
            emptySet()
        }
    }

    private fun writeSet(name: String, values: Set<String>): Boolean = runCatching {
        indexDir.mkdirs()
        val array = JSONArray().apply { values.sorted().forEach { put(it) } }
        AtomicFiles.writeAtomically(File(indexDir, name), array.toString().toByteArray(), fsyncDir = true)
    }.getOrElse {
        Log.w("AutoRecordMarks", "写入 $name 失败", it)
        false
    }

    private companion object {
        const val LOCK_FILE = "locks.json"
        const val PLAYED_FILE = "played.json"
    }
}
