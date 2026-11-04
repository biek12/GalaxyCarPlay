package com.ecarx.autoservice.projection

import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.*
import android.view.Surface
import com.galaxy.auto.contract.*
import java.io.Closeable

/** 一个GL上下文复用地图纹理，分别合成仪表和编辑草稿预览；不复制地图Bitmap或重建解码器。
 * 所有EGL/纹理只在owner线程访问；外部方法只发布配置，最多一个待执行绘制。
 */
internal class GalaxyGlComposition(
    private val output: Surface,
    private val surfaceChanged: (String, Surface?) -> Unit,
    private val report: (String) -> Unit,
) : Closeable {
    private val thread=HandlerThread("AutoService-compose").apply { start() }
    private val worker=Handler(thread.looper)
    private val main=Handler(Looper.getMainLooper())
    private var egl: GalaxyEglContext?=null
    private var program: GalaxyTextureProgram?=null
    private var destination: EGLSurface?=null
    private var previewDestination: EGLSurface?=null
    private var previewSurface: Surface?=null
    private var map: GalaxyTextureInput?=null
    private var mapSize=MapStreamSize.DEFAULT
    private data class Slot(var scene: SceneConfiguration,val input: GalaxyTextureInput)
    private var activeTheme: Slot?=null
    private var pendingTheme: Slot?=null
    private var preview: Slot?=null
    private var pendingPreviewConfig: SceneConfiguration?=null
    private var outputActive=false
    private var mapActive=false
    private var outputDirty=false
    private var previewDirty=false
    private var drawPending=false
    @Volatile private var closed=false
    private val pacer=FramePacer()
    private val timings=GalaxyFrameTimings()
    @Volatile private var outputFrames=0L
    @Volatile private var previewFrames=0L
    @Volatile private var mapFrames=0L
    @Volatile private var themeFrames=0L
    @Volatile private var renderMicrosTotal=0L
    @Volatile private var renderMicrosMax=0L
    @Volatile private var configuredFps=30

    init { worker.post {
        if(closed)return@post
        try {
            val context=GalaxyEglContext().also { egl=it }
            destination=context.window(output)
            program=GalaxyTextureProgram()
            map=GalaxyTextureInput(AutoContract.WIDTH,AutoContract.MAP_HEIGHT,worker) {
                if(mapActive){outputDirty=outputActive&&(activeTheme?.scene?.map!=null||activeTheme==null);previewDirty=true}
                requestDraw()
            }
            notifySurface(AutoContract.MAP,checkNotNull(map).surface)
        } catch(error: Exception) { failure(error) }
    } }

    fun configure(role: String,config: SceneConfiguration) { worker.post {
        if(closed||egl==null)return@post
        try {
            egl!!.current()
            if(role==AutoContract.THEME){
                pendingTheme?.input?.close()
                val input=GalaxyTextureInput(AutoContract.WIDTH,AutoContract.HEIGHT,worker) { outputDirty=true;requestDraw() }
                pendingTheme=Slot(config,input)
                notifySurface(AutoContract.surfaceKind(role,config.token),input.surface)
            }else{
                check(role==AutoContract.PREVIEW)
                val old=preview
                if(old==null){
                    val input=GalaxyTextureInput(AutoContract.WIDTH,AutoContract.HEIGHT,worker) { previewDirty=true;requestDraw() }
                    preview=Slot(config,input)
                }
                // 拖拽预览复用同一Surface；新几何随下一张草稿纹理一起采用，活动仪表不受影响。
                pendingPreviewConfig=config
                notifySurface(AutoContract.surfaceKind(role,config.token),checkNotNull(preview).input.surface)
            }
        }catch(error: Exception){failure(error)}
    } }

    fun clearScene(role: String) { worker.post {
        if(closed)return@post
        egl?.current()
        if(role==AutoContract.THEME){activeTheme?.input?.close();pendingTheme?.input?.close();activeTheme=null;pendingTheme=null;outputDirty=true}
        if(role==AutoContract.PREVIEW){preview?.input?.close();preview=null;pendingPreviewConfig=null;previewDirty=true}
        requestDraw()
    } }

    fun setPolicy(policy: ProjectionPolicy) { worker.post {
        if(closed)return@post
        configuredFps=policy.maximumFps
        pacer.setRate(policy.maximumFps,SystemClock.elapsedRealtimeNanos())
        if(drawPending){worker.removeCallbacks(draw);drawPending=false;requestDraw()}
    } }
    fun setMapStreamSize(size: MapStreamSize) { worker.post {
        if(closed || size==mapSize)return@post
        mapSize=size
        map?.surfaceTexture?.setDefaultBufferSize(size.width,size.height)
        outputDirty=true;previewDirty=true;requestDraw()
    } }
    fun setOutputActive(active: Boolean) { worker.post { if(!closed){outputActive=active;outputDirty=active;requestDraw()} } }
    fun setMapActive(active: Boolean) { worker.post { if(!closed&&mapActive!=active){mapActive=active;outputDirty=true;previewDirty=true;requestDraw()} } }
    fun setPreviewSurface(value: Surface?) {
        if(closed)return
        val copy=value?.let(::copySurface)
        worker.post {
            if(closed){copy?.release();return@post}
            try {
                val context=egl
                if(context==null){copy?.release();return@post}
                context.current();context.destroy(previewDestination);previewDestination=null
                previewSurface?.release();previewSurface=copy
                if(copy!=null&&copy.isValid)previewDestination=context.window(copy)
                previewDirty=true;requestDraw()
            }catch(error: Exception){copy?.release();previewSurface=null;reportOnMain("HAB: preview failed type=${error.javaClass.simpleName}")}
        }
    }
    fun sendSurfaces(){worker.post {
        if(closed)return@post
        map?.let { notifySurface(AutoContract.MAP,it.surface) }
        (pendingTheme?:activeTheme)?.let { notifySurface(AutoContract.surfaceKind(AutoContract.THEME,it.scene.token),it.input.surface) }
        preview?.let { notifySurface(AutoContract.surfaceKind(AutoContract.PREVIEW,it.scene.token),it.input.surface) }
    } }

    private val draw=Runnable {
        drawPending=false
        if(closed)return@Runnable
        try {
            val context=egl?:return@Runnable
            context.current()
            val started=SystemClock.elapsedRealtimeNanos()
            pacer.started(started)
            if(map?.latch()==true)++mapFrames
            activeTheme?.input?.let { if(it.latch())++themeFrames }
            val pending=pendingTheme
            if(pending!=null&&pending.input.latch()){
                activeTheme?.input?.close();activeTheme=pending;pendingTheme=null;++themeFrames;outputDirty=true
            }
            if(preview?.input?.latch()==true){pendingPreviewConfig?.let { preview?.scene=it;pendingPreviewConfig=null };previewDirty=true}
            if(outputActive&&outputDirty){destination?.let { render(it,activeTheme);++outputFrames };outputDirty=false}
            if(previewDirty){
                previewDestination?.let {
                    try { render(it,preview);++previewFrames }
                    catch(error: Exception){
                        context.current();context.destroy(previewDestination);previewDestination=null
                        previewSurface?.release();previewSurface=null
                        reportOnMain("HAB: preview output closed type=${error.javaClass.simpleName}")
                    }
                }
                previewDirty=false
            }
            val micros=(SystemClock.elapsedRealtimeNanos()-started)/1000
            timings.recordMicros(micros)
            renderMicrosTotal+=micros;renderMicrosMax=maxOf(renderMicrosMax,micros)
        }catch(error: Exception){failure(error)}
    }
    private fun requestDraw(){
        if(closed||egl==null||drawPending)return
        drawPending=true
        worker.postDelayed(draw,pacer.delayMillis(SystemClock.elapsedRealtimeNanos()))
    }
    private val full=MapViewport(0f,0f,AutoContract.WIDTH.toFloat(),AutoContract.HEIGHT.toFloat())
    private fun render(destination: EGLSurface,slot: Slot?){
        val context=checkNotNull(egl);val program=checkNotNull(program)
        context.current(destination)
        val (width,height)=context.dimensions(destination)
        GLES20.glViewport(0,0,width,height);GLES20.glClearColor(0f,0f,0f,1f);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val scale=minOf(width.toFloat()/AutoContract.WIDTH,height.toFloat()/AutoContract.HEIGHT)
        val w=(AutoContract.WIDTH*scale).toInt();val h=(AutoContract.HEIGHT*scale).toInt()
        GLES20.glViewport((width-w)/2,(height-h)/2,w,h)
        val viewport=slot?.scene?.map ?: if(slot==null)full else null
        if(mapActive&&viewport!=null)map?.let { program.draw(it,viewport.fitted(mapSize.width,mapSize.height),false,slot?.scene?.appearance?:MapAppearance())
            slot?.scene?.backdrops?.forEach { region->program.draw(it,viewport.fitted(mapSize.width,mapSize.height),true,backdrop=region) } }
        slot?.let { program.draw(it.input,full,true) }
        context.swap(destination)
    }
    private fun notifySurface(kind: String,surface: Surface){main.post { if(!closed&&surface.isValid)surfaceChanged(kind,surface) }}
    private fun reportOnMain(message: String){main.post { if(!closed)report(message) }}
    private fun failure(error: Exception){
        main.post { report("HAB: GL composition failed type=${error.javaClass.simpleName}") }
        // 失败释放自有GL资源；由已有投屏生命周期报告，绝不改原车窗口或替换OEM服务。
        close()
    }
    fun metrics()="requestedFps=$configuredFps mapLatched=$mapFrames themeLatched=$themeFrames outputPosted=$outputFrames previewPosted=$previewFrames"+
        " composeMicrosMean=${renderMicrosTotal/(outputFrames+previewFrames).coerceAtLeast(1)} composeMicrosMax=$renderMicrosMax ${timings.metrics("composeUs")}"
    @Synchronized override fun close(){
        if(closed)return
        closed=true
        // 保留已排队的Surface配置任务，让它们在closed分支释放自有引用。
        worker.removeCallbacks(draw)
        worker.post {
            runCatching { egl?.current() }
            runCatching { map?.close() };runCatching { activeTheme?.input?.close() };runCatching { pendingTheme?.input?.close() };runCatching { preview?.input?.close() }
            map=null;activeTheme=null;pendingTheme=null;preview=null
            runCatching { program?.close() };program=null
            runCatching { egl?.destroy(previewDestination) };runCatching { egl?.destroy(destination) }
            previewSurface?.release();previewSurface=null
            egl?.close();egl=null;thread.quitSafely()
        }
    }
    private fun copySurface(value: Surface): Surface {
        val parcel=Parcel.obtain()
        return try{value.writeToParcel(parcel,0);parcel.setDataPosition(0);Surface.CREATOR.createFromParcel(parcel)}finally{parcel.recycle()}
    }
}
