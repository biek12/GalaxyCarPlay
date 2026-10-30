package com.galaxy.autocluster.runtime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import android.view.Surface
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.*
import com.galaxy.autocluster.theme.*
import com.galaxy.autocluster.theme.render.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 一个场景Surface生产者；正式仪表/编辑预览采用相同准备、绘制和时钟，租约相互独立。 */
internal class ClusterSceneOutput(
    context: Context,private val role: String,
    private val onStatus: (String)->Unit = {},private val onSnapshot: (Bundle)->Unit = {},
    private val onPrepared: ((PreparedScene)->Unit)? = null,
) : Closeable {
    private val app=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val thread=HandlerThread("Cluster-scene-$role").apply { start() }
    private val worker=Handler(thread.looper)
    private val pacer=FramePacer()
    private val latestData=AtomicReference<Bundle?>()
    private val dataQueued=AtomicBoolean()
    private var data=SceneData()
    private var policy=ProjectionPolicy()
    private var active: PreparedScene?=null
    private var pending: PreparedScene?=null
    private var token=0L
    private var activeToken=0L
    private var target: Surface?=null
    private var renderer: SceneRenderer?=null
    private var drawQueued=false
    private var queuedAt=Long.MAX_VALUE
    private var forceNight: Boolean?=null
    private var production=true
    private var artwork=ArtworkFrame(null,null,0)
    private var artworkVersion=-1L
    private var artworkRequest=""
    @Volatile private var closed=false
    @Volatile private var frames=0L
    @Volatile private var drawMicros=0L
    @Volatile private var maxDrawMicros=0L
    private var lastFailure: String?=null
    private val preferences=app.getSharedPreferences("cluster-display",0)
    private var appearance=preferences.getString("appearance","auto") ?: "auto"
    private val preferenceListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key=="appearance")refreshAppearance()
    }
    private val preparation: ScenePreparation=ScenePreparation(app,ready={ prepared -> worker.post {
        if(closed||prepared.serial!=preparation.latest()){prepared.close();return@post}
        stage(prepared)
        if(onPrepared!=null){
            val previewCopy=prepared.copy(lease=prepared.lease.fork())
            main.post { if(closed||previewCopy.serial!=preparation.latest())previewCopy.close()else onPrepared.invoke(previewCopy) }
        }
    } },failed={ _,error -> status("主题准备失败：${error.javaClass.simpleName}") })
    private val client=AutoClient(app,role,onSnapshot={ snapshot ->
        onSnapshot(snapshot)
        latestData.set(Bundle(snapshot))
        if(dataQueued.compareAndSet(false,true))worker.post(ingest)
    },onState={ status(it) },onConfiguredSurface={ surface,nonce ->
        val copy=surface?.let(::duplicateSurface)
        worker.post {
            if(closed){copy?.release();return@post}
            if(copy==null){target?.release();target=null;worker.removeCallbacks(draw);drawQueued=false;return@post}
            val next=pending
            if(nonce==token&&next!=null&&next.serial==preparation.latest()){
                val old=active
                if(renderer==null||old==null||old.lease.resources!==next.lease.resources||old.id!=next.id){renderer?.close();renderer=SceneRenderer(next.lease.resources)}
                target?.release();target=copy;active=next;pending=null;activeToken=nonce
                old?.close();requestArtwork();requestDraw()
            }else if(nonce==activeToken&&active!=null){target?.release();target=copy;requestDraw()}
            else copy.release()
        }
    })

    init {
        require(role==AutoContract.THEME||role==AutoContract.PREVIEW)
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        client.connect()
    }
    fun setActive(enabled: Boolean){check(role==AutoContract.THEME);client.setActive(enabled)}
    fun submit(input: SceneInput)=preparation.submit(input)
    fun submit(theme: ThemePackage)=loadTheme { theme }
    fun loadTheme(load: ()->ThemePackage){
        preparation.postLatestIo("package") {
            try {
                val theme=load()
                val scene=SceneXml.read(theme)
                val assets=theme.copyEntries().filterKeys { it !in setOf("manifest.xml","scene.xml","styles.xml") }
                submit(SceneInput(theme.metadata.id,EditorContent(scene,assets)))
            }catch(error: Exception){status("主题加载失败：${error.javaClass.simpleName}")}
        }
    }
    fun previewSurface(surface: Surface?){
        check(role==AutoContract.PREVIEW);client.setPreviewSurface(surface)
        if(surface!=null)worker.post { rebind() }
    }
    fun setProduction(enabled: Boolean){
        check(role==AutoContract.PREVIEW)
        worker.post {
            if(production==enabled)return@post
            production=enabled
            if(enabled)rebind()else{target?.release();target=null;worker.removeCallbacks(draw);drawQueued=false}
        }
    }
    fun setPolicy(value: ProjectionPolicy)=client.setProjectionPolicy(value)
    fun forceNight(value: Boolean?){worker.post { if(forceNight!=value){forceNight=value;rebind()} }}
    fun refreshAppearance(){worker.post { appearance=preferences.getString("appearance","auto") ?: "auto";rebind() }}

    private fun stage(prepared: PreparedScene){
        pending?.close();pending=prepared
        if(!production){active?.close();active=prepared;pending=null;renderer?.close();renderer=null;return}
        token=maxOf(SystemClock.elapsedRealtimeNanos(),token+1)
        val compiled=compiled(prepared)
        val geometry=SceneConfiguration(token,prepared.id,compiled.map?.let { MapViewport(it.x,it.y,it.width,it.height) },compiled.mapAppearance,compiled.backdrops)
        main.post { if(!closed)client.configureScene(geometry) }
    }
    private fun rebind(){if(closed)return;active?.let { stage(it.copy(lease=it.lease.fork())) }}
    private fun compiled(prepared: PreparedScene): CompiledScene {
        val override=appearance
        val requested=if(override=="auto")prepared.content.scene.document.appearance else override
        val night=forceNight ?: when(requested){"day"->false;"night"->true;else->app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES}
        return if(night)prepared.night else prepared.day
    }

    private val ingest=Runnable {
        dataQueued.set(false)
        val snapshot=latestData.getAndSet(null)?:return@Runnable
        if(closed)return@Runnable
        val now=SystemClock.elapsedRealtime()
        val previous=data
        data=SceneData(snapshot)
        snapshot.getBundle("projectionPolicy")?.let { value ->
            val next=ProjectionPolicy.from(value)
            if(next!=policy){policy=next;pacer.setRate(next.maximumFps,SystemClock.elapsedRealtimeNanos());requestDraw()}
        }
        val current=active
        val relevant=current?.let { changed(previous,data,compiled(it),now) } ?: true
        if(relevant||previous.menuSequence!=data.menuSequence||previous.inputEpoch!=data.inputEpoch)requestDraw()
        requestArtwork(snapshot.getString("artworkUri"))
    }
    private var artworkUri: String?=null
    private fun requestArtwork(uri: String?=artworkUri){
        if(uri!=null)artworkUri=uri
        val current=active?:return
        if(SceneTree.flatten(current.content.scene.document.nodes).none { it.visible&&(it.type=="artwork"||it.type=="music-background"||it.type=="pages") })return
        val version=data.media.artworkVersion
        val radii=compiled(current).visibleNodes.filter {it.node.type in setOf("music-background","pages")}.mapNotNull {
            val props=it.properties
            if(props.string("backgroundStyle")=="artwork"&&props.bool("backgroundBlur"))props.number("backgroundBlurRadius").toInt()else null
        }.filter {it>0}.distinct().sorted().take(8)
        val request="$version:$radii"
        if(artworkRequest==request)return
        artworkRequest=request
        artworkVersion=version
        val source=artworkUri
        preparation.postLatestIo("artwork") {
            var image: Bitmap?=null
            if(source!=null)runCatching {
                app.contentResolver.openInputStream(Uri.parse(source))?.use { input ->
                    image=BitmapFactory.decodeStream(input,null,BitmapFactory.Options().apply { inSampleSize=4 })
                }
            }
            val blurred=image?.let { bitmap->radii.associateWith { radius->ArtworkBlur.create(bitmap,radius) } }.orEmpty()
            worker.post {
                if(!closed&&artworkRequest==request){artwork=ArtworkFrame(image,artwork.current,SystemClock.elapsedRealtime(),blurred);requestDraw()}
            }
        }
    }

    private fun changed(old: SceneData,next: SceneData,scene: CompiledScene,now: Long): Boolean {
        if(scene.sources.any { old.text(it,now)!=next.text(it,now) })return true
        val mediaVisible=SceneTree.flatten(scene.scene.document.nodes).any { it.visible&&it.type in setOf("track","progress","lyrics","artwork") }
        if(!mediaVisible)return false
        val a=old.media;val b=next.media
        return a.track!=b.track||a.title!=b.title||a.artist!=b.artist||a.album!=b.album||a.playing!=b.playing||a.duration!=b.duration||a.lyrics!=b.lyrics||
            !java.util.Arrays.equals(a.times,b.times)||a.artworkVersion!=b.artworkVersion||kotlin.math.abs(a.positionAt(now)-b.positionAt(now))>80
    }

    private fun requestDraw(atNanos: Long=SystemClock.elapsedRealtimeNanos()){
        if(closed||target?.isValid!=true)return
        val next=maxOf(atNanos,pacer.nextAtNanos)
        if(drawQueued&&queuedAt<=next)return
        if(drawQueued)worker.removeCallbacks(draw)
        drawQueued=true;queuedAt=next
        worker.postDelayed(draw,((next-SystemClock.elapsedRealtimeNanos()).coerceAtLeast(0)+999999)/1000000)
    }
    private val draw=Runnable {
        drawQueued=false;queuedAt=Long.MAX_VALUE
        if(closed)return@Runnable
        val surface=target?.takeIf { it.isValid }?:return@Runnable
        val scene=active?:return@Runnable
        val painter=renderer?:return@Runnable
        val started=SystemClock.elapsedRealtimeNanos()
        pacer.started(started)
        try {
            val canvas=surface.lockHardwareCanvas()
            val demand=try { painter.render(canvas,compiled(scene),data,SystemClock.elapsedRealtime(),artwork) }
                finally { surface.unlockCanvasAndPost(canvas) }
            val micros=(SystemClock.elapsedRealtimeNanos()-started)/1000
            ++frames;drawMicros+=micros;maxDrawMicros=maxOf(maxDrawMicros,micros);lastFailure=null
            val continuous=demand.animation||(policy.mode=="fixed-cap"&&data.media.playing&&SceneTree.flatten(scene.content.scene.document.nodes).any { it.type in setOf("lyrics","progress","track") })
            pacer.setRate(if(continuous)minOf(policy.animationFps,policy.maximumFps)else policy.maximumFps,started)
            if(continuous)requestDraw(maxOf(pacer.nextAtNanos,started+1_000_000_000L/pacer.rate))
            else demand.nextAt?.let { requestDraw(it*1_000_000) }
        }catch(error: Exception){
            val failure=error.javaClass.simpleName
            if(lastFailure!=failure){lastFailure=failure;status("主题绘制失败：$failure")}
        }
    }
    fun metrics()="role=$role drawn=$frames drawMicrosMean=${drawMicros/frames.coerceAtLeast(1)} drawMicrosMax=$maxDrawMicros"
    private fun status(value: String){main.post { if(!closed)onStatus(value) }}
    override fun close(){
        check(Looper.myLooper()==Looper.getMainLooper())
        if(closed)return
        closed=true
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        client.close();preparation.close();latestData.set(null);worker.removeCallbacks(draw);worker.removeCallbacks(ingest)
        worker.post {
            target?.release();target=null;renderer?.close();renderer=null
            active?.close();pending?.close();active=null;pending=null;artwork=ArtworkFrame(null,null,0);thread.quitSafely()
        }
    }
    private fun duplicateSurface(value: Surface): Surface {
        val parcel=Parcel.obtain()
        return try{value.writeToParcel(parcel,0);parcel.setDataPosition(0);Surface.CREATOR.createFromParcel(parcel)}finally{parcel.recycle()}
    }
}
