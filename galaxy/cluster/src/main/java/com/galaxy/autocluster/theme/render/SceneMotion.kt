package com.galaxy.autocluster.theme.render

import kotlin.math.min

/** 纯时间采样：新目标从当前视觉位置接管，不补播积压目标；所有轨道共用一个deadline。 */
internal class SceneMotion {
    private data class Track(var from: Float, var target: Float, var started: Long, var duration: Long, var easing: String)
    private val tracks = mutableMapOf<String, Track>()
    private val introductions = mutableMapOf<String, Long>()
    var now = 0L; private set
    var nextAt = Long.MAX_VALUE; private set
    var animating = false; private set
    fun begin(now: Long) { this.now = now; nextAt = Long.MAX_VALUE; animating = false }
    fun deadline(at: Long) { if (at > now) nextAt = min(nextAt, at) }
    fun animate() { animating = true }
    fun value(key: String, target: Float?, duration: Long, easing: String): Float? {
        if (target == null) { tracks.remove(key); return null }
        val track = tracks.getOrPut(key) { Track(target, target, now, duration, easing) }
        if (target != track.target) {
            track.from = sample(track); track.target = target; track.started = now; track.duration = duration; track.easing = easing
        }
        if (track.from != track.target && now - track.started < track.duration) animate()
        return sample(track)
    }
    fun intro(key: String, enabled: Boolean, duration: Long, driving: Boolean): Float? {
        val start = introductions.getOrPut(key) { if (enabled && !driving) now else Long.MIN_VALUE }
        if (driving) introductions[key] = Long.MIN_VALUE
        if (start == Long.MIN_VALUE || driving || now - start >= duration) return null
        animate()
        val progress = (now - start).toFloat() / duration.coerceAtLeast(1)
        return if (progress < .5f) ease(progress * 2, "smooth") else 1 - ease((progress - .5f) * 2, "smooth")
    }
    private fun sample(t: Track): Float = t.from + (t.target - t.from) * ease(if (t.duration <= 0) 1f else ((now - t.started).toFloat() / t.duration).coerceIn(0f, 1f), t.easing)
    fun clear() { tracks.clear(); introductions.clear() }
    fun retain(ids: Set<String>){tracks.keys.retainAll(ids);introductions.keys.retainAll(ids)}
    companion object {
        fun ease(t: Float, type: String) = when (type) { "ease-out" -> 1 - (1 - t) * (1 - t) * (1 - t); "smooth" -> t * t * (3 - 2 * t); else -> t }
    }
}
