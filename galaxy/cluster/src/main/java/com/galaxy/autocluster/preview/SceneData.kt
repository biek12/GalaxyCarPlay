package com.galaxy.autocluster.preview

import android.os.Bundle

/**
 * 一次服务快照在预览边界上的只读转换。
 *
 * 构造时读取 Bundle：车辆键进入 [signals]，导航键进入 [navigation]，菜单序号与纪元原样保留。
 * 媒体优先取 `visualMedia`，缺失时用 `media`。歌词时间数组仅在与歌词等长且非降序时保留，并复制数组，避免调用方事后改写快照。
 * 未知、过期与有效 0 分开：构造不把缺省数值当成 0，车辆读数要等读取方法用调用方的当前时间做时效判断。
 * 本类不发布 IPC，也不拥有采集或绘制线程。时间单位与快照里的接收时间一致，预览侧传入的是毫秒。
 */
internal class SceneData(
    snapshot: Bundle = Bundle(),
) {
    private val vehicle = snapshot.getBundle("vehicle") ?: Bundle()

    /** 按车辆键保存的信号。构造时不裁剪时效，读取时才用调用方时钟判断。 */
    val signals =
        vehicle.keySet().associateWith { key ->
            val item = vehicle.getBundle(key) ?: Bundle()
            val text = item.getString("value").orEmpty()
            SceneSignal(
                text,
                text.toFloatOrNull()?.takeIf { it.isFinite() },
                item.getString("status") == "VALID",
                item.getLong("receivedAt"),
            )
        }

    /** 导航快照的字符串视图。键集合来自 Bundle，值使用条目自身的文本。 */
    val navigation =
        snapshot
            .getBundle("navigation")
            ?.let { value ->
                value.keySet().associateWith { value.get(it).toString() }
            }.orEmpty()

    /** 输入代次原文。快照没有该键时为空字符串。 */
    val inputEpoch = snapshot.getString("inputEpoch").orEmpty()

    /** 方控菜单序号。快照没有该键时为 0。 */
    val menuSequence = snapshot.getLong("menuSequence")

    /** 菜单序号的接收时间，单位与快照一致。 */
    val menuReceivedAt = snapshot.getLong("menuReceivedAt")

    /** 已优先选取可视媒体后的卡片媒体快照。 */
    val media: SceneMedia

    init {
        val m = snapshot.getBundle("visualMedia") ?: snapshot.getBundle("media") ?: Bundle()
        val lyrics = m.getStringArrayList("lyrics").orEmpty().toList()
        val times =
            m
                .getLongArray("lyricTimes")
                ?.takeIf {
                    it.size == lyrics.size && it.asSequence().zipWithNext().all { (a, b) -> a <= b }
                }?.clone()
        media =
            SceneMedia(
                m.getString("title").orEmpty(),
                m.getString("artist").orEmpty(),
                m.getString("album").orEmpty(),
                m.getString("track").orEmpty(),
                m.getBoolean("playing"),
                m.getLong("elapsed", -1),
                m.getLong("receivedAt"),
                m.getLong("duration", -1),
                lyrics,
                times,
                snapshot.getLong("artworkVersion"),
            )
    }

    /**
     * 按来源键读取文本。
     *
     * `vehicle.` 只返回仍在有效期内的信号文本。`navigation.` 返回快照原文，不做时效裁剪。
     * 媒体标题、艺人、专辑，以及非负的进度和时长，按键名直接取出。无法识别的键返回 null。
     */
    fun text(source: String, now: Long): String? =
        when {
            source.startsWith("vehicle.") -> signals[source.drop(8)]?.takeIf { it.fresh(now) }?.text
            source.startsWith("navigation.") -> navigation[source.drop(11)]
            source == "media.title" -> media.title
            source == "media.artist" -> media.artist
            source == "media.album" -> media.album
            source == "media.elapsed" -> media.positionAt(now).takeIf { it >= 0 }?.toString()
            source == "media.duration" -> media.duration.takeIf { it >= 0 }?.toString()
            else -> null
        }

    /**
     * 按来源键读取有限数值。
     *
     * 车辆键使用信号里已经解析的数字，有效 0 保持为 0。其他键复用 [text] 再解析，非有限值返回 null。
     */
    fun number(source: String, now: Long): Float? =
        if (source.startsWith("vehicle.")) {
            signals[source.drop(8)]?.takeIf { it.fresh(now) }?.number
        } else {
            text(source, now)?.toFloatOrNull()?.takeIf { it.isFinite() }
        }

    /**
     * 读取单位为 kW 的车辆功率。
     *
     * 非车辆键，或该键快照单位不是 `kW` 时返回 null。单位来自原始 Bundle，不从文本猜测。
     */
    fun kilowatts(source: String, now: Long): Float? {
        if (!source.startsWith("vehicle.")) return null
        if (vehicle.getBundle(source.drop(8))?.getString("unit") != "kW") return null
        return number(source, now)
    }

    /**
     * 计算给定车辆来源里当前仍有效的最早失效时刻。
     *
     * 返回 `receivedAt + maxAge + 1` 的最小值，供预览安排下一次刷新。没有有效车辆来源时返回 null。
     * 非车辆键不参与。时间单位与信号接收时间相同。
     */
    fun nextExpiry(sources: Set<String>, now: Long): Long? =
        sources
            .mapNotNull { source ->
                if (!source.startsWith("vehicle.")) {
                    null
                } else {
                    signals[source.drop(8)]?.takeIf { it.fresh(now) }?.let { it.receivedAt + it.maxAge + 1 }
                }
            }.minOrNull()
}
