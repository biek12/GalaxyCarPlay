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
    report: (String) -> Unit, command: (Int) -> Unit,
    mapSizeChanged: (com.galaxy.auto.contract.MapStreamSize) -> Unit = {}) : AutoCloseable {
    private val thread = HandlerThread("CarPlay-platform-art").apply { start() }
    private val worker = Handler(thread.looper)
    private val pending = AtomicReference<Pair<String,Bitmap>?>()
    private var lastArt: Bitmap? = null
    private var lastMediaTrack=""
    private var artworkResend=""
    private var lastNavigation=Bundle()
    private var laneAngles=""
    private var laneRecommended=""
    fun lanes(values: List<com.shilapi.xcertplay.iap2.message.LaneGuidanceLane>){
        laneAngles=values.take(8).joinToString(","){it.angle.toString()}
        laneRecommended=values.take(8).joinToString(","){if(it.recommended)"1"else"0"}
        publishNavigation()
    }
    private fun publishNavigation(){client.publishNavigation(Bundle(lastNavigation).apply{
        putString("laneAngles",laneAngles);putString("laneRecommended",laneRecommended)
    })}
    private val client = AutoClient(context, AutoContract.MAP, onSurface = surface,
        onSnapshot = { snapshot ->
            GalaxyClusterMusicBinding.projectionDemand(this,false)
            val current=snapshot.getBundle("media")
            val identity=current?.getString("track").orEmpty()
            val request="$identity:${snapshot.getLong("artworkVersion")}"
            if(identity==lastMediaTrack&&current?.getString("sourceKind")=="carplay"&&!snapshot.getBoolean("artworkReady")&&artworkResend!=request){
                artworkResend=request;lastArt?.takeUnless{it.isRecycled}?.let{queueArtwork(it,identity)}
            }
            snapshot.getBundle("mapStreamSize")?.let { mapSizeChanged(com.galaxy.auto.contract.MapStreamSize.from(it)) }
        },
        onState = {
            if (it in setOf("service_disconnected", "binding_died", "binding_rejected", "service_unavailable") || it.startsWith("connect:"))
                GalaxyClusterMusicBinding.projectionDemand(this, false)
            report("AutoService: $it")
        }, onCommand = { value ->
            val index = when (value) { 0 -> CarPlayMediaButton.PLAY_PAUSE; 1 -> CarPlayMediaButton.PREVIOUS; else -> CarPlayMediaButton.NEXT }
            command(index)
        })
    private val sendArt = Runnable {
        val (track,bitmap) = pending.getAndSet(null) ?: return@Runnable
        try {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
            client.publishArtwork(output.toByteArray(),track)
        } finally { bitmap.recycle() }
    }
    private val stopMusic = ClusterMusicState.observe { state ->
        val info = state.info
        lastMediaTrack=com.galaxy.auto.contract.MediaIdentity.key("carplay",info.sourceApp.orEmpty(),info.title.orEmpty(),info.artist.orEmpty(),info.album.orEmpty())
        client.publishMedia(Bundle().apply {
            putString("title", info.title); putString("artist", info.artist); putString("album", info.album)
            putString("sourceKind","carplay");putString("sourceApp",info.sourceApp)
            putString("track",com.galaxy.auto.contract.MediaIdentity.key("carplay",info.sourceApp.orEmpty(),info.title.orEmpty(),info.artist.orEmpty(),info.album.orEmpty()))
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
                queueArtwork(art,lastMediaTrack)
            }
        }
    }
    init { client.connect() }
    fun active(active: Boolean) = client.setActive(active)
    fun guidance(value: ClusterTurnGuidance?) {
        if(value==null){laneAngles="";laneRecommended=""}
        lastNavigation=Bundle().apply {
        value ?: return@apply
        putString("road", value.road); putString("distance", "${value.distanceMeters} m"); putInt("maneuver", value.icon)
        putInt("roundaboutExit",value.roundaboutExit)
        value.remainingMeters?.let { putString("remainingDistance",if(it<1000)"$it m"else String.format(Locale.ROOT,"%.1f km",it/1000.0)) }
        value.remainingSeconds?.let { putString("remaining", "${it / 60} 分钟") }
        value.arrivalEpochSeconds?.let { putString("arrival", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it * 1000))) }
        }
        publishNavigation()
    }
    private fun queueArtwork(bitmap: Bitmap,track: String) {
        val scale=minOf(1f,512f/maxOf(bitmap.width,bitmap.height))
        val scaled=Bitmap.createScaledBitmap(bitmap,(bitmap.width*scale).toInt().coerceAtLeast(1),(bitmap.height*scale).toInt().coerceAtLeast(1),true)
        val owned=if(scaled===bitmap)scaled.copy(Bitmap.Config.ARGB_8888,false)else scaled
        pending.getAndSet(track to owned)?.second?.recycle()
        worker.removeCallbacks(sendArt);worker.post(sendArt)
    }
    override fun close() {
        GalaxyClusterMusicBinding.projectionDemand(this, false)
        stopMusic(); client.close()
        worker.removeCallbacksAndMessages(null)
        pending.getAndSet(null)?.second?.recycle()
        thread.quitSafely()
    }
}
