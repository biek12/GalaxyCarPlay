package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.LinkedHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Galaxy product bridge from the current CarPlay session to the independent cluster music layer. */
internal object GalaxyClusterMusicBinding {
    private data class TrackKey(val title: String, val artist: String)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "GalaxyPlay-cluster-art").apply { isDaemon = true } }
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = worker,
        main = Executor { task -> main.post(task) },
        decode = CarPlayMediaKeys::decodeArtwork,
        publish = ::onArtworkDecoded,
        discard = ::recycle,
    )
    private val lyricsLookup = GalaxyLyricsLookup()
    private val lyricsCache = object : LinkedHashMap<TrackKey, List<ClusterLyricLine>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TrackKey, List<ClusterLyricLine>>?) = size > MAX_CACHED_LYRICS
    }
    private var controller: CarPlayController? = null
    private var appContext: Context? = null
    private var stopSettings: (() -> Unit)? = null
    private var artworkSession: Any? = null
    private var mediaInfo = CarPlayNowPlaying()
    private var requestedArtworkId: Int? = null
    private var lyricsEnabled: Boolean? = null
    private var lookupTicket = 0L
    private var pendingLyrics: Runnable? = null
    private var lyricPhase = "idle"
    private val projectionOwners = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())

    @Synchronized fun projectionDemand(owner: Any, active: Boolean) {
        if (active) projectionOwners.add(owner) else projectionOwners.remove(owner)
        val app = appContext ?: return
        val enabled = lyricsWanted(app)
        if (lyricsEnabled == enabled) return
        lyricsEnabled = enabled
        cancelLyricsLocked()
        if (enabled) requestLyricsLocked(mediaInfo)
        else ClusterMusicState.publishLyrics(mediaInfo, emptyList())
    }

    @Synchronized fun phoneLyrics(expected: CarPlayController, info: CarPlayNowPlaying, sentence: String) {
        if (controller !== expected || appContext?.let(::lyricsWanted) != false) return
        ClusterMusicState.publishLyrics(info, if (sentence.isBlank()) emptyList() else listOf(ClusterLyricLine(null, sentence)))
    }

    @Synchronized fun diagnostics(): Map<String, Any?> = mapOf(
        "enabled" to lyricsEnabled, "projectionDemand" to projectionOwners.isNotEmpty(), "phase" to lyricPhase,
        "pending" to (pendingLyrics != null), "cacheEntries" to lyricsCache.size,
    )

    @Synchronized fun attach(context: Context, next: CarPlayController) {
        if (controller === next) return
        clearLocked()
        val app = context.applicationContext
        GalaxyLyricsNetwork.initialize(app)
        appContext = app
        controller = next
        artworkSession = artworkQueue.newSession()
        lyricsEnabled = lyricsWanted(app)
        val preferences = app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key != "cluster_music_enabled")
                return@OnSharedPreferenceChangeListener
            synchronized(this) {
                val enabled = lyricsWanted(app)
                if (lyricsEnabled != enabled) {
                    lyricsEnabled = enabled
                    if (enabled) {
                        cancelLyricsLocked()
                        requestLyricsLocked(mediaInfo)
                    } else cancelLyricsLocked()
                }
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        stopSettings = { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Synchronized fun update(expected: CarPlayController, info: CarPlayNowPlaying) {
        if (controller !== expected) return
        val changed = ClusterMusicState.trackChanged(mediaInfo, info)
        mediaInfo = info
        if (changed) {
            artworkSession = artworkQueue.newSession()
            requestedArtworkId = null
            cancelLyricsLocked()
        }
        ClusterMusicState.publishProductInfo(info)
        if (changed) requestLyricsLocked(info)
    }

    @Synchronized fun submitArtwork(expected: CarPlayController, id: Int, bytes: ByteArray) {
        if (controller !== expected || mediaInfo.artworkTransferId != id || requestedArtworkId == id) return
        val session = artworkSession ?: return
        requestedArtworkId = id
        artworkQueue.submit(session, id, bytes)
    }

    @Synchronized fun resetSession(expected: CarPlayController) {
        if (controller !== expected) return
        mediaInfo = CarPlayNowPlaying()
        artworkSession = artworkQueue.newSession()
        requestedArtworkId = null
        cancelLyricsLocked()
        ClusterMusicState.publishProductInfo(mediaInfo)
    }

    @Synchronized fun detach(expected: CarPlayController?) {
        if (expected != null && controller !== expected) return
        clearLocked()
        controller = null
    }

    private fun onArtworkDecoded(owner: Any, id: Int, image: Bitmap?) = synchronized(this) {
        if (image == null) return@synchronized
        if (controller == null || artworkSession !== owner || mediaInfo.artworkTransferId != id) {
            recycle(image)
            return@synchronized
        }
        ClusterMusicState.publishProductArtwork(mediaInfo, image)
    }

    /** 网络歌词只供实际请求投屏时的自定义仪表；原车当前行独立使用iPhone推送。 */
    private fun lyricsWanted(app: Context) =
        projectionOwners.isNotEmpty() && ClusterMusicSettings.enabled(app)

    private fun requestLyricsLocked(info: CarPlayNowPlaying, attempt: Int = 0) {
        val app = appContext ?: return
        if (!lyricsWanted(app)) { lyricPhase = "disabled"; return }
        val title = info.title?.trim().orEmpty()
        val artist = info.artist?.trim().orEmpty()
        if (title.isEmpty() || artist.isEmpty() || title.length > 300 || artist.length > 300) return
        val query = GalaxyLyricQuery(title, artist)
        val key = TrackKey(title, artist)
        lyricsCache[key]?.let { lyricPhase = "cache_hit"; ClusterMusicState.publishLyrics(info, it); return }
        val ticket = ++lookupTicket
        val runnable = Runnable {
            synchronized(this) {
                if (controller == null || lookupTicket != ticket || !sameQuery(mediaInfo, key)) return@Runnable
                pendingLyrics = null
                lyricPhase = "querying"
                lyricsLookup.lookup(query) { lines ->
                    synchronized(this) {
                        if (controller == null || lookupTicket != ticket || !sameQuery(mediaInfo, key)) return@lookup
                        if (lines == null) {
                            lyricPhase = "failed"
                            if (attempt < 3) {
                                val retry = Runnable { synchronized(this) {
                                    if (controller != null && lookupTicket == ticket && sameQuery(mediaInfo, key))
                                        requestLyricsLocked(mediaInfo, attempt + 1)
                                } }
                                pendingLyrics = retry
                                main.postDelayed(retry, longArrayOf(2000, 5000, 10000)[attempt])
                            }
                            return@lookup
                        }
                        lyricPhase = if (lines.isEmpty()) "not_found" else "resolved"
                        if (lines.isNotEmpty()) lyricsCache[key] = lines
                        // 查询身份已核验；用当前元数据发布，避免封面/专辑增量使早期快照被拒绝。
                        ClusterMusicState.publishLyrics(mediaInfo, lines)
                    }
                }
            }
        }
        pendingLyrics = runnable
        lyricPhase = "scheduled"
        main.postDelayed(runnable, LOOKUP_DEBOUNCE_MILLIS)
    }

    private fun sameQuery(info: CarPlayNowPlaying, key: TrackKey) =
        info.title?.trim() == key.title && info.artist?.trim() == key.artist

    private fun cancelLyricsLocked() {
        lyricPhase = "cancelled"
        lookupTicket++
        pendingLyrics?.let(main::removeCallbacks)
        pendingLyrics = null
        lyricsLookup.cancel()
    }

    private fun clearLocked() {
        stopSettings?.invoke()
        stopSettings = null
        lyricsEnabled = null
        appContext = null
        cancelLyricsLocked()
        artworkSession = artworkQueue.newSession()
        requestedArtworkId = null
        mediaInfo = CarPlayNowPlaying()
        ClusterMusicState.publishProductInfo(mediaInfo)
    }

    private fun recycle(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private const val LOOKUP_DEBOUNCE_MILLIS = 500L
    private const val MAX_CACHED_LYRICS = 8
}
