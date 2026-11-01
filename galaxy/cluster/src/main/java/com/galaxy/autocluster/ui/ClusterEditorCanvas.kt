package com.galaxy.autocluster.ui

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.FrameLayout
import com.galaxy.auto.contract.FramePacer
import com.galaxy.autocluster.runtime.*
import com.galaxy.autocluster.theme.*
import com.galaxy.autocluster.theme.render.*
import kotlin.math.*

/** 实时合成预览在TextureView，触摸命中/选择框在独立View；不会截图地图再贴Bitmap。 */
internal class ClusterEditorCanvas(
    context: Context,private val output: ClusterSceneOutput,
    private val edited: (Boolean)->Unit,private val selectionChanged: ()->Unit,
): FrameLayout(context) {
    private val ui=ClusterUi(context)
    private val texture=TextureView(context)
    private val overlay=Overlay(context)
    var session: EditorSession?=null
    var multiSelect=false
    var showGuides=false
        set(value){field=value;overlay.invalidate()}
    var demo=false
        set(value){field=value;updateSource();overlay.invalidate()}
    private var prepared: PreparedScene?=null
    private var painter: SceneRenderer?=null
    private var live=SceneData()
    private var demoStarted=0L
    private var demoUntil=0L
    private val pacer=FramePacer(30)
    private val tick=Runnable { overlay.invalidate() }

    init {
        setBackgroundColor(Color.BLACK)
        addView(texture,LayoutParams(-1,-1));addView(overlay,LayoutParams(-1,-1))
        texture.surfaceTextureListener=object: TextureView.SurfaceTextureListener{
            override fun onSurfaceTextureAvailable(value: SurfaceTexture,width: Int,height: Int){connectPreview(value)}
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture,width: Int,height: Int){connectPreview(value)}
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {output.previewSurface(null);return true}
            override fun onSurfaceTextureUpdated(value: SurfaceTexture)=Unit
        }
    }
    private fun connectPreview(value: SurfaceTexture){
        if(demo||!isAttachedToWindow)return
        val surface=Surface(value)
        try { output.previewSurface(surface) } finally { surface.release() }
    }
    private fun updateSource(){
        texture.visibility=if(demo)INVISIBLE else VISIBLE
        output.setProduction(!demo&&isAttachedToWindow)
        if(demo)output.previewSurface(null)else texture.surfaceTexture?.let(::connectPreview)
    }
    fun prepared(value: PreparedScene){
        val old=prepared
        if(old==null||old.lease.resources!==value.lease.resources){painter?.close();painter=SceneRenderer(value.lease.resources)}
        prepared=value;old?.close();overlay.invalidate()
    }
    fun snapshot(value: Bundle){live=SceneData(value);if(demo)overlay.invalidate()}
    fun nextPage(){
        val state=session?:return
        val node=SceneTree.flatten(state.content.scene.document.nodes).firstOrNull {it.type=="pages"}?:return
        val order=(node.properties["pageOrder"]?:"music,tires,fuel,electric").split(',').map {it.trim()}
        val current=node.properties["initialPage"]?:"music"
        state.updateNode(node.id){it.copy(properties=it.properties+("initialPage" to order[(order.indexOf(current).coerceAtLeast(0)+1)%order.size]))}
        edited(true);overlay.invalidate()
    }
    fun refresh(){overlay.invalidate()}
    fun replay(){
        demo=true;demoStarted=SystemClock.elapsedRealtime();demoUntil=demoStarted+7000
        painter?.close();painter=prepared?.let { SceneRenderer(it.lease.resources) };pacer.reset();overlay.invalidate()
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int){
        val width=MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec((width*532f/1920).roundToInt().coerceAtLeast(1),MeasureSpec.EXACTLY))
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();updateSource()}
    override fun onDetachedFromWindow(){removeCallbacks(tick);output.previewSurface(null);output.setProduction(false);super.onDetachedFromWindow()}
    fun close(){removeCallbacks(tick);painter?.close();painter=null;prepared?.close();prepared=null}

    private inner class Overlay(context: Context): View(context){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.SANS_SERIF }
        private var dragBase: EditorContent?=null
        private var downX=0f;private var downY=0f
        private var resizeId: String?=null
        private var resizeWidth=0f;private var resizeHeight=0f
        private var dragging=false
        private val frame=RectF()
        override fun onDraw(canvas: Canvas){
            super.onDraw(canvas)
            val now=SystemClock.elapsedRealtime()
            val state=session
            if(demo){
                val source=prepared
                if(source!=null){
                    val running=now<demoUntil
                    val data=DemoContent.data(now,if(running&&now-demoStarted<1500)0 else 68,running,if(running)1750+now-demoStarted else 1750)
                    val scene=if(state?.previewNight==false)source.day else source.night
                    val demand=painter?.render(canvas,scene,data,now,ArtworkFrame(DemoContent.artwork,null,0),demoMap=true,targetWidth=width,targetHeight=height)
                    if(running){
                        pacer.started(SystemClock.elapsedRealtimeNanos())
                        val delay=if(demand?.animation==true)pacer.delayMillis(SystemClock.elapsedRealtimeNanos())else demand?.nextAt?.let { (it-now).coerceAtLeast(1) }
                        if(delay!=null){removeCallbacks(tick);postDelayed(tick,delay)}
                    }
                }
            }
            if(state==null)return
            val sx=width/1920f;val sy=height/532f
            if(showGuides){
                paint.color=0x441368FB;paint.strokeWidth=ui.dp(1).toFloat()
                for(x in 0..1920 step 160)canvas.drawLine(x*sx,0f,x*sx,height.toFloat(),paint)
                for(y in 0..532 step 133)canvas.drawLine(0f,y*sy,width.toFloat(),y*sy,paint)
            }
            val positions=SceneTree.worldRects(state.content.scene.document.nodes)
            for(id in state.selection){
                val r=positions[id]?:continue
                frame.set(r.x*sx,r.y*sy,(r.x+r.width)*sx,(r.y+r.height)*sy)
                paint.style=Paint.Style.STROKE;paint.strokeWidth=ui.dp(2).toFloat();paint.color=ui.accent
                canvas.drawRect(frame,paint)
                if(state.selection.size==1&&state.node(id)?.locked!=true){
                    paint.style=Paint.Style.FILL
                    val size=ui.dp(7).toFloat();canvas.drawRect(frame.right-size,frame.bottom-size,frame.right+size,frame.bottom+size,paint)
                }
            }
            paint.style=Paint.Style.FILL
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val state=session?:return false
            val x=event.x*1920/width;val y=event.y*532/height
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{
                    val positions=SceneTree.worldRects(state.content.scene.document.nodes)
                    val only=state.selection.singleOrNull()
                    val r=positions[only]
                    val handle=ui.dp(20)*1920f/width
                    val resize=only!=null&&r!=null&&state.node(only)?.locked!=true&&abs(x-r.x-r.width)<handle&&abs(y-r.y-r.height)<handle
                    val hit=if(resize)only else hit(state.content.scene.document.nodes,x,y)
                    if(!resize&&!(hit in state.selection&&!multiSelect))state.choose(hit,multiSelect)
                    selectionChanged();invalidate()
                    if(hit==null||state.node(hit)?.locked==true)return true
                    downX=x;downY=y;dragBase=state.content;dragging=true
                    resizeId=if(resize)only else null;resizeWidth=r?.width?:0f;resizeHeight=r?.height?:0f
                    state.beginGesture();parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE->{
                    val base=dragBase?:return true
                    if(!dragging)return true
                    val dx=x-downX;val dy=y-downY
                    if(resizeId!=null)state.resize(resizeId!!,resizeWidth+dx,resizeHeight+dy,base)
                    else state.moveSelection(dx,dy,base,showGuides)
                    edited(false);invalidate()
                }
                MotionEvent.ACTION_UP->{
                    if(dragging){state.finishGesture();dragging=false;dragBase=null;edited(true)}
                    parent.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_CANCEL->{
                    if(dragging){state.cancelGesture();dragging=false;dragBase=null;edited(true)}
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }
        private fun hit(nodes: List<SceneNode>,x: Float,y: Float): String? {
            for(node in nodes.asReversed()){
                if(!node.visible||!node.rect.contains(x,y))continue
                if(node.locked)return node.id
                hit(if(node.type=="pages")node.children.filter { it.properties["pageId"]==(node.properties["initialPage"]?:"music") }else node.children,x-node.rect.x,y-node.rect.y)?.let { return it }
                return node.id
            }
            return null
        }
    }
}
