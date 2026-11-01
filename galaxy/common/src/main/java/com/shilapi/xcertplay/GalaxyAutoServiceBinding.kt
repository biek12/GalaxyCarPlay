package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.os.*
import android.view.Surface
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicReference

/** CarPlay是地图/媒体生产者；没有HAB设备、QNX或最终合成画布。 */
internal class GalaxyAutoServiceBinding(context: Context, surface: (Surface?) -> Unit,
    report: (String) -> Unit, command: (Int) -> Unit) : AutoCloseable {
    private val thread = HandlerThread("CarPlay-platform-art").apply { start() }
    private val worker = Handler(thread.looper)
    private val pending = AtomicReference<Bitmap?>()
    private var lastArt: Bitmap? = null
    private val client = AutoClient(context, AutoContract.MAP, onSurface = surface,
        onState = { report("AutoService: $it") }, onCommand = { value ->
            val index = when (value) { 0 -> CarPlayMediaButton.PLAY_PAUSE; 1 -> CarPlayMediaButton.PREVIOUS; else -> CarPlayMediaButton.NEXT }
            command(index)
        })
    private val sendArt = Runnable {
        val bitmap = pending.getAndSet(null) ?: return@Runnable
        try {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
            client.publishArtwork(output.toByteArray())
        } finally { bitmap.recycle() }
    }
    private val stopMusic = ClusterMusicState.observe { state ->
        val info = state.info
        client.publishMedia(Bundle().apply {
            putString("title", info.title); putString("artist", info.artist); putString("album", info.album)
            putString("track", "${info.title}\u0001${info.artist}\u0001${info.artworkTransferId}")
            putBoolean("playing", info.playing); putLong("sampledAt", SystemClock.elapsedRealtime())
            putLong("elapsed", state.elapsedAt(SystemClock.elapsedRealtime()) ?: -1)
            putLong("duration", info.durationMillis ?: -1)
            putStringArrayList("lyrics", ArrayList(state.lyrics.take(AutoContract.MAX_LYRICS).map { it.text }))
            if (state.lyrics.all { it.timeMillis != null }) putLongArray("lyricTimes", state.lyrics.take(AutoContract.MAX_LYRICS).map { it.timeMillis!! }.toLongArray())
        })
        val art = state.artwork?.takeUnless { it.isRecycled }
        if (art !== lastArt) {
            lastArt = art
            if (art != null) {
                val scale = minOf(1f, 512f / maxOf(art.width, art.height))
                val scaled = Bitmap.createScaledBitmap(art, (art.width * scale).toInt().coerceAtLeast(1), (art.height * scale).toInt().coerceAtLeast(1), true)
                val owned = if (scaled === art) scaled.copy(Bitmap.Config.ARGB_8888, false) else scaled
                pending.getAndSet(owned)?.recycle()
                worker.removeCallbacks(sendArt); worker.post(sendArt)
            }
        }
    }
    init { client.connect() }
    fun active(active: Boolean) = client.setActive(active)
    fun guidance(value: ClusterTurnGuidance?) = client.publishNavigation(Bundle().apply {
        value ?: return@apply
        putString("road", value.road); putString("distance", "${value.distanceMeters} m"); putInt("maneuver", value.icon)
        putInt("roundaboutExit",value.roundaboutExit)
        value.remainingMeters?.let { putString("remainingDistance",if(it<1000)"$it m"else String.format(Locale.ROOT,"%.1f km",it/1000.0)) }
        value.remainingSeconds?.let { putString("remaining", "${it / 60} 分钟") }
        value.arrivalEpochSeconds?.let { putString("arrival", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it * 1000))) }
    })
    override fun close() {
        stopMusic(); client.close()
        worker.removeCallbacksAndMessages(null)
        pending.getAndSet(null)?.recycle()
        thread.quitSafely()
    }
}
