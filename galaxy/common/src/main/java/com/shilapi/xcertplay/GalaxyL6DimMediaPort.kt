package com.shilapi.xcertplay

import android.content.Context
import android.net.Uri
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** L6原厂DimService按包名丢弃本App，且DIM Binder遗漏歌词；按同固件Proto沿FDBus完整媒体出口发送。 */
internal class GalaxyL6DimMediaPort(context: Context, private val eas: L7MediaCenterPort,
    private val onOutputReady: () -> Unit = {}) : L7MediaCenterPort by eas {
    private val app = context.applicationContext
    @Volatile private var valid = true
    @Volatile private var ownFocus = false
    private var latest = CarPlayNowPlaying()
    private var artwork: Uri? = null
    private var lyric = GalaxyOemLyrics()
    private var failures = 0
    private var connection: GalaxyL6OemMediaConnection? = null
    private var images: GalaxyL6OemArtwork? = null
    private val artworkServer = GalaxyOemArtworkServer(app)
    private val kanziMusic = if (GalaxyKanziMusicPublisher.bundled(app)) GalaxyKanziMusicPublisher(app) else null
    private var publishedTitle: String? = null
    private var publishedSentence: String? = null
    private var sentMessages = 0L
    private val session = java.util.UUID.randomUUID().toString()
    override fun initialize(ready: (Boolean) -> Unit, command: (Int) -> Boolean,
                            focus: (String?) -> Unit, selected: (Int) -> Boolean) {
        try { connection = GalaxyL6OemMediaConnection(app); images = GalaxyL6OemArtwork(app, onOutputReady) }
        catch (error: Throwable) { report("initialize", error) }
        eas.initialize(ready, command, { name ->
            if (!name.isNullOrBlank()) {
                val wasOwn = ownFocus
                ownFocus = name == app.packageName
                publishKanzi()
                if (ownFocus && !wasOwn && valid) onOutputReady()
            }
            focus(name)
        }, selected)
    }
    override fun focusClient(): String? = eas.focusClient().also {
        if (!it.isNullOrBlank()) { ownFocus = it == app.packageName; publishKanzi() }
    }
    override fun update(value: CarPlayNowPlaying, artwork: Uri?): Boolean {
        latest = value; this.artwork = artwork; failures = 0
        images?.select(artwork)
        val result = eas.update(value, artworkServer.url(artwork))
        publish()
        publishKanzi()
        return result
    }
    override fun prepare(value: CarPlayNowPlaying, artwork: Uri?) {
        eas.prepare(value, artworkServer.url(artwork))
    }
    // Session先更新歌词缓存再prepare曲目；到update/progress才统一发送，避免新曲歌词套入旧曲。
    override fun lyrics(value: GalaxyOemLyrics) { eas.lyrics(value); lyric = value }
    override fun progress(milliseconds: Long) {
        eas.progress(milliseconds); latest = latest.copy(elapsedMillis = milliseconds); publish(); publishKanzi()
    }
    private fun publishKanzi() = kanziMusic?.update(latest, lyric, valid && ownFocus)
    private fun publish() {
        if (!valid || !ownFocus || !latest.playbackKnown || latest.title.isNullOrBlank() || failures >= 3) return
        try {
            val sent = connection?.send(latest, lyric, images?.path(artwork) ?: "-999", session) == true
            if (sent) {
                sentMessages++
                diagnostic = org.json.JSONObject().apply {
                    put("sent", sentMessages); put("phoneLyricPresent", lyric.sentence.isNotEmpty())
                    put("artwork", images?.path(artwork)?.let { it != "-999" } ?: false)
                    put("artworkHttp", org.json.JSONObject(artworkServer.diagnostics()))
                }.toString()
            }
            if (sent && (publishedTitle != latest.title || publishedSentence != lyric.sentence)) {
                publishedTitle = latest.title; publishedSentence = lyric.sentence
                L7DebugLog.record("OemDimMedia: send accepted=true source=6 titlePresent=true artwork=${images?.path(artwork) != "-999"} lyrics=${lyric.content.isNotEmpty()} sentence=${lyric.sentence.isNotEmpty()}")
            }
        } catch (error: Throwable) { failures++; report("send", error) }
    }
    private fun report(stage: String, error: Throwable) {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        L7DebugLog.record("OemDimMedia: $stage exceptionType=${cause.javaClass.simpleName} attempt=$failures " +
            cause.stackTrace.take(4).joinToString(" | "))
    }
    override fun invalidate() { valid = false; ownFocus = false; kanziMusic?.close(); eas.invalidate() }
    companion object { @Volatile var diagnostic = "{}"; private set }
    override fun close() { diagnostic = "{}"; invalidate(); connection?.close(); connection = null; images?.close(); images = null; artworkServer.close(); eas.close() }
}
