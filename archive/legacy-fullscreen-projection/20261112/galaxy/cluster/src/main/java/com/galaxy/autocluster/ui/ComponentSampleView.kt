package com.galaxy.autocluster.ui

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import com.galaxy.auto.contract.FramePacer
import com.galaxy.autocluster.theme.*
import com.galaxy.autocluster.theme.render.*
import java.io.ByteArrayOutputStream

/** 组件库用真正的组件绘制器预览；静态缩略图不挂持续动画，详情由用户显式重播。 */
internal class ComponentSampleView(context: Context): View(context) {
    private var preset: ComponentPreset?=null
    private var resources: ThemeResources?=null
    private var renderer: SceneRenderer?=null
    private var day: CompiledScene?=null
    private var night: CompiledScene?=null
    var showNight=true
        set(value){field=value;invalidate()}
    private var viewBox=SceneRect(0f,0f,500f,500f)
    private var base=0L
    private var animateUntil=0L
    private var sample=SceneData()
    private var artwork=ArtworkFrame(null,null,0)
    private val pacer=FramePacer(30)
    private val tick=Runnable { invalidate() }
    fun bind(value: ComponentPreset){
        if(preset===value&&renderer!=null)return
        release();preset=value
        prepare(value,false)
    }
    private fun prepare(value: ComponentPreset,intro: Boolean){
        var node=ComponentPresets.sample(value)
        if(intro&&node.type in setOf("gauge","ring"))node=node.copy(properties=node.properties+("intro" to "true"))
        if(node.type=="image")node=node.copy(properties=node.properties+("image" to "assets/demo.png"))
        if(node.type=="card")node=node.copy(children=listOf(
            SceneNode("inside-value","value","数字",SceneRect(24f,40f,270f,290f),mapOf("label" to "速度","valueSize" to "95","followMs" to "0")),
            SceneNode("inside-ring","ring","电量",SceneRect(330f,70f,230f,230f),mapOf("intro" to "false","numerals" to "false","secondarySource" to ""))
        ))
        val scene=ThemeScene(SceneDocument(listOf(node)),SceneStyles())
        val images=if(node.type=="image")mapOf("assets/demo.png" to demoImageBytes)else emptyMap()
        val resource=ThemeResources(scene,context.cacheDir) { images.getValue(it) }
        val radii=SceneTree.flatten(scene.document.nodes).mapNotNull { it.properties["backgroundBlurRadius"]?.toFloatOrNull()?.toInt() }.filter {it>0}.distinct()
        artwork=ArtworkFrame(DemoContent.artwork,null,0,radii.associateWith { ArtworkBlur.create(DemoContent.artwork,it) })
        resources=resource;renderer=SceneRenderer(resource)
        day=CompiledScene(scene,false,resource);night=CompiledScene(scene,true,resource)
        viewBox=node.rect.let { SceneRect(it.x-8,it.y-8,it.width+16,it.height+16) }
        base=SystemClock.elapsedRealtime();sample=DemoContent.data(base)
        invalidate()
    }
    fun replay(){
        val value=preset?:return
        release();prepare(value,true);animateUntil=SystemClock.elapsedRealtime()+7000;pacer.reset();invalidate()
    }
    override fun onDraw(canvas: Canvas){
        super.onDraw(canvas)
        if(renderer==null)preset?.let { prepare(it,false) }
        val painter=renderer?:return;val scene=(if(showNight)night else day)?:return
        val actual=SystemClock.elapsedRealtime();val running=actual<animateUntil
        val now=if(running)actual else base
        val data=if(running)DemoContent.data(now,if(now-base<1500)0 else 68,true,1750+now-base)else sample
        val demand=painter.render(canvas,scene,data,now,artwork,demoMap=true,viewBox=viewBox,targetWidth=width,targetHeight=height)
        if(running){
            pacer.started(SystemClock.elapsedRealtimeNanos())
            val next=if(demand.animation)pacer.delayMillis(SystemClock.elapsedRealtimeNanos())else demand.nextAt?.let { (it-now).coerceAtLeast(1) }
            if(next!=null){removeCallbacks(tick);postDelayed(tick,next)}
        }
    }
    override fun onDetachedFromWindow(){release();super.onDetachedFromWindow()}
    fun release(){removeCallbacks(tick);renderer?.close();renderer=null;resources?.close();resources=null;day=null;night=null;animateUntil=0}
    companion object {
        private val demoImageBytes by lazy { ByteArrayOutputStream().also { DemoContent.artwork.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }.toByteArray() }
    }
}
