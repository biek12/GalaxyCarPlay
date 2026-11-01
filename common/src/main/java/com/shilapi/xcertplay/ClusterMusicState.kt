package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** One optional synchronized/plain lyric line. Null timestamp means an unsynchronized lyric. */
internal data class ClusterLyricLine(val timeMillis: Long?, val text: String)

/** Main-thread display snapshot. Only product-decoded artwork is owned here; core-owned art is borrowed. */
internal object ClusterMusicState {
    data class Snapshot(
        val info: CarPlayNowPlaying = CarPlayNowPlaying(),
        val artwork: Bitmap? = null,
        val changedAt: Long = 0L,
        val revision: Long = 0L,
        val lyrics: List<ClusterLyricLine> = emptyList(),
        val lyricsResolved: Boolean = false,
        val elapsedUpdatedAt: Long = 0L,
        internal val ownsArtwork: Boolean = false,
    ) {
        fun elapsedAt(nowMillis: Long): Long? {
            val elapsed = info.elapsedMillis ?: return null
            val duration = info.durationMillis?.takeIf { it > 0L }
            val advance = if (info.playing && elapsedUpdatedAt > 0L) (nowMillis - elapsedUpdatedAt).coerceAtLeast(0L) else 0L
            return (elapsed + advance).let { if (duration == null) it else it.coerceIn(0L, duration) }
        }

        fun lyricAt(nowMillis: Long): ClusterLyricLine? {
            val timed = lyrics.filter { it.timeMillis != null }
            if (timed.isEmpty()) {
                if (lyrics.isEmpty()) return null
                val clock = elapsedAt(nowMillis) ?: (nowMillis - changedAt).coerceAtLeast(0L)
                val line = (clock / PLAIN_LYRIC_LINE_MILLIS).toInt() % lyrics.size
                return lyrics[line]
            }
            val elapsed = elapsedAt(nowMillis) ?: return null
            return timed.lastOrNull { it.timeMillis!! <= elapsed }
        }
    }

    internal const val PLAIN_LYRIC_LINE_MILLIS = 3_500L

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(Snapshot) -> Unit>()
    private var state = Snapshot()

    /** Core media owner publishes a borrowed bitmap; do not recycle it here. */
    fun publish(info: CarPlayNowPlaying, artwork: Bitmap?) = apply(info, artwork, ownsArtwork = false, preserveProductState = false)

    /** Product publisher keeps same-track artwork/lyrics while reporting elapsed and playback changes. */
    fun publishProductInfo(info: CarPlayNowPlaying) = apply(info, null, ownsArtwork = false, preserveProductState = true)

    /** Product decoder transfers ownership of this bitmap to the cluster snapshot. */
    fun publishProductArtwork(info: CarPlayNowPlaying, artwork: Bitmap?) =
        apply(info, artwork, ownsArtwork = artwork != null, preserveProductState = true)

    /** Reject stale network results by matching the track snapshot captured when lookup started. */
    fun publishLyrics(expected: CarPlayNowPlaying, lines: List<ClusterLyricLine>) {
        main.post {
            if (!sameTrack(state.info, expected)) return@post
            state = state.copy(lyrics = lines, lyricsResolved = true)
            notifyListeners()
        }
    }

    private fun apply(info: CarPlayNowPlaying, suppliedArtwork: Bitmap?, ownsArtwork: Boolean, preserveProductState: Boolean) {
        main.post {
            val previous = state
            val changed = trackChanged(previous.info, info)
            val keep = preserveProductState && !changed &&
                previous.info.artworkTransferId == info.artworkTransferId
            val hasTrack = !info.title.isNullOrBlank() || !info.artist.isNullOrBlank()
            // 上游借用封面可跨元数据更新保留；产品拥有的图像仅在同曲中保留，切歌仍释放。
            val retain = suppliedArtwork == null && (keep ||
                (!preserveProductState && hasTrack && !previous.ownsArtwork))
            val artwork = if (retain) previous.artwork?.takeUnless { it.isRecycled } else suppliedArtwork
            val owner = if (retain) previous.ownsArtwork else ownsArtwork
            val lyrics = if (keep) previous.lyrics else emptyList()
            val lyricsResolved = keep && previous.lyricsResolved
            val now = SystemClock.elapsedRealtime()
            val progressChanged = previous.info.elapsedMillis != info.elapsedMillis || previous.info.playing != info.playing
            state = Snapshot(
                info = info,
                artwork = artwork,
                changedAt = if (changed) now else previous.changedAt,
                revision = previous.revision + if (changed) 1 else 0,
                lyrics = lyrics,
                lyricsResolved = lyricsResolved,
                elapsedUpdatedAt = if (changed || progressChanged) now else previous.elapsedUpdatedAt,
                ownsArtwork = owner,
            )
            notifyListeners()
            if (previous.ownsArtwork && previous.artwork !== artwork) recycle(previous.artwork)
        }
    }

    fun observe(listener: (Snapshot) -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper())
        listeners.add(listener)
        listener(state)
        return { listeners.remove(listener) }
    }

    private fun notifyListeners() = listeners.toList().forEach { it(state) }

    private fun recycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    }

    private fun sameTrack(a: CarPlayNowPlaying, b: CarPlayNowPlaying): Boolean =
        a.title == b.title && a.artist == b.artist && a.album == b.album &&
            a.sourceApp == b.sourceApp && a.artworkTransferId == b.artworkTransferId &&
            (a.durationMillis == null || b.durationMillis == null || a.durationMillis == b.durationMillis)

    fun trackChanged(old: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        !sameTrack(old, next)
}
