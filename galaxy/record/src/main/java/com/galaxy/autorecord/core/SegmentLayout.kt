package com.galaxy.autorecord.core

import java.io.File

/**
 * 录像段目录与文件布局（docs/AutoRecord编码与存储技术口径-20261103.md §6）。
 *
 * 纪律：四路各自 MP4 + 段目录 sidecar 为真相，index.db 只是可重建索引；
 * 写入中用 `.writing` 后缀，收尾成功后原子改名为最终名，再写索引。
 */
object SegmentLayout {
    const val SCHEMA_VERSION = 1
    const val ROOT_DIR = "AutoRecord"
    const val SEGMENTS_DIR = "segments"
    const val INDEX_DIR = "index"
    const val TMP_DIR = "tmp"

    const val FILE_MANIFEST = "segment.json"
    const val FILE_VEHICLES = "vehicles.jsonl"
    const val FILE_EVENTS = "events.json"
    const val FILE_THUMB = "thumb.jpg"

    const val SUFFIX_WRITING = ".writing"
    const val SUFFIX_PARTIAL = ".partial"

    /** 方向顺序固定为前/后/左/右，界面与契约共用。 */
    val DIRECTIONS = listOf("front", "rear", "left", "right")

    fun root(baseDir: File): File = File(baseDir, ROOT_DIR)
    fun segmentsRoot(baseDir: File): File = File(root(baseDir), SEGMENTS_DIR)
    fun indexDir(baseDir: File): File = File(root(baseDir), INDEX_DIR)
    fun tmpDir(baseDir: File): File = File(root(baseDir), TMP_DIR)

    /** 段目录名：日期分组 / 会话时间-段号。 */
    fun segmentDir(baseDir: File, startedAtMillis: Long, segmentIndex: Int): File {
        val dayDir = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }.format(java.util.Date(startedAtMillis))
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }.format(java.util.Date(startedAtMillis))
        return File(File(segmentsRoot(baseDir), dayDir), "$stamp-%03d".format(segmentIndex))
    }

    fun videoFile(segmentDir: File, direction: String): File = File(segmentDir, "$direction.mp4")

    /** 写入中文件名：最终名 + `.writing`，收尾成功后改回最终名。 */
    fun writingFile(video: File): File = File(video.parentFile, video.name + SUFFIX_WRITING)

    fun partialFile(video: File): File = File(video.parentFile, video.name + SUFFIX_PARTIAL)

    fun isValidDirection(direction: String): Boolean = direction in DIRECTIONS
}