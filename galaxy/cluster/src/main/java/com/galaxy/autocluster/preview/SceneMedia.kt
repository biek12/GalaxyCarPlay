package com.galaxy.autocluster.preview

import kotlin.math.max

/**
 * 音乐卡片消费的一条媒体快照。
 *
 * [position]、[receivedAt]、[duration] 和歌词时间轴使用同一毫秒单位。[position] 小于 0 表示进度未知。
 * 播放中才把接收后经过的时间加到进度上，并用正的 [duration] 截断。无歌词时索引为 -1；没有时间轴但有歌词时固定第 0 行。
 * [artworkVersion] 只记录封面代次，解码与回收由预览 View 持有。本类型不持有线程。
 */
internal data class SceneMedia(
    val title: String,
    val artist: String,
    val album: String,
    val track: String,
    val playing: Boolean,
    val position: Long,
    val receivedAt: Long,
    val duration: Long,
    val lyrics: List<String>,
    val times: LongArray?,
    val artworkVersion: Long,
) {
    /**
     * 计算 [now] 时刻的播放进度。
     *
     * 进度未知返回 -1。暂停时保持 [position]；播放时经过时间小于 0 按 0 计。时长为正时不超过 [duration]。
     */
    fun positionAt(now: Long): Long =
        if (position < 0) {
            -1
        } else {
            val elapsed = position + if (playing) max(0, now - receivedAt) else 0
            if (duration > 0) elapsed.coerceAtMost(duration) else elapsed
        }

    /**
     * 用二分查找定位 [now] 对应的歌词行。
     *
     * 无歌词返回 -1。没有时间轴时，只要有歌词就返回 0。
     * 时间轴存在时返回最后一个不晚于当前进度的下标；进度早于全部时间点则返回 -1。
     */
    fun lyricIndex(now: Long): Int {
        val times = times ?: return if (lyrics.isEmpty()) -1 else 0
        val position = positionAt(now)
        var low = 0
        var high = times.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (times[middle] <= position) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low - 1
    }
}
