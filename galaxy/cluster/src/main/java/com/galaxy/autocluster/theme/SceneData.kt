package com.galaxy.autocluster.theme

import android.os.Bundle
import kotlin.math.max

internal data class SceneSignal(val text: String, val number: Float?, val valid: Boolean, val receivedAt: Long, val maxAge: Long = 2000) {
    fun fresh(now: Long) = valid && now - receivedAt in 0..maxAge
}
internal data class SceneMedia(val title: String, val artist: String, val album: String, val track: String,
    val playing: Boolean, val position: Long, val receivedAt: Long, val duration: Long,
    val lyrics: List<String>, val times: LongArray?, val artworkVersion: Long) {
    fun positionAt(now: Long): Long = if (position < 0) -1 else {
        val elapsed = position + if (playing) max(0, now - receivedAt) else 0
        if (duration > 0) elapsed.coerceAtMost(duration) else elapsed
    }
    fun lyricIndex(now: Long): Int {
        val times = times ?: return -1
        val position = positionAt(now)
        var low = 0; var high = times.size
        while (low < high) { val middle = (low + high) ushr 1; if (times[middle] <= position) low = middle + 1 else high = middle }
        return low - 1
    }
}

/** Bundle在快照边界转换一次。未知/过期与有效0分开，不把演示状态发布到服务。 */
internal class SceneData(snapshot: Bundle = Bundle()) {
    private val vehicle = snapshot.getBundle("vehicle") ?: Bundle()
    val signals = vehicle.keySet().associateWith { key ->
        val item = vehicle.getBundle(key) ?: Bundle()
        val text = item.getString("value").orEmpty()
        SceneSignal(text, text.toFloatOrNull()?.takeIf { it.isFinite() }, item.getString("status") == "VALID", item.getLong("receivedAt"))
    }
    val navigation = snapshot.getBundle("navigation")?.let { value -> value.keySet().associateWith { value.get(it).toString() } }.orEmpty()
    val inputEpoch=snapshot.getString("inputEpoch").orEmpty()
    val menuSequence=snapshot.getLong("menuSequence")
    val menuReceivedAt=snapshot.getLong("menuReceivedAt")
    val media: SceneMedia
    init {
        val m = snapshot.getBundle("media") ?: Bundle()
        val lyrics = m.getStringArrayList("lyrics").orEmpty().toList()
        val times = m.getLongArray("lyricTimes")?.takeIf { it.size == lyrics.size && it.asSequence().zipWithNext().all { (a, b) -> a <= b } }?.clone()
        media = SceneMedia(m.getString("title").orEmpty(), m.getString("artist").orEmpty(), m.getString("album").orEmpty(), m.getString("track").orEmpty(),
            m.getBoolean("playing"), m.getLong("elapsed", -1), m.getLong("receivedAt"), m.getLong("duration", -1), lyrics, times, snapshot.getLong("artworkVersion"))
    }
    fun text(source: String, now: Long): String? = when {
        source.startsWith("vehicle.") -> signals[source.drop(8)]?.takeIf { it.fresh(now) }?.text
        source.startsWith("navigation.") -> navigation[source.drop(11)]
        source == "media.title" -> media.title
        source == "media.artist" -> media.artist
        source == "media.album" -> media.album
        source == "media.elapsed" -> media.positionAt(now).takeIf { it >= 0 }?.toString()
        source == "media.duration" -> media.duration.takeIf { it >= 0 }?.toString()
        else -> null
    }
    fun number(source: String, now: Long): Float? = if (source.startsWith("vehicle.")) signals[source.drop(8)]?.takeIf { it.fresh(now) }?.number
        else text(source, now)?.toFloatOrNull()?.takeIf { it.isFinite() }
    fun kilowatts(source: String, now: Long): Float? {
        if (!source.startsWith("vehicle.")) return null
        if (vehicle.getBundle(source.drop(8))?.getString("unit") != "kW") return null
        return number(source, now)
    }
    fun nextExpiry(sources: Set<String>, now: Long): Long? = sources.mapNotNull { source ->
        if (!source.startsWith("vehicle.")) null else signals[source.drop(8)]?.takeIf { it.fresh(now) }?.let { it.receivedAt + it.maxAge + 1 }
    }.minOrNull()
}
