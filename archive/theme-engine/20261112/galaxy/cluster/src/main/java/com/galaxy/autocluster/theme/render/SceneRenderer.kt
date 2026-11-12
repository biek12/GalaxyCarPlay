package com.galaxy.autocluster.theme.render

import android.graphics.*
import com.galaxy.autocluster.theme.*
import kotlin.math.*

internal data class RenderDemand(val nextAt: Long?, val animation: Boolean)

/** 唯一场景绘制器：编辑器、组件样式预览与独立仪表共用。
 * 硬件Canvas每次完整清屏/重放；静态Picture缓存不等于依赖前一个Surface buffer。
 */
internal class SceneRenderer(private val resources: ThemeResources) {
    private val tools = DrawTools()
    private val motion = SceneMotion()
    private val gauges = GaugePainter(tools)
    private val media = MediaPainter(tools)
    private val lyrics = LyricsPainter(tools)
    private val mapPreview=MapPreviewPainter(tools)
    private val primitives = PrimitivePainter(tools, media)
    private val pages=PageMotion()
    private val regeneration=RegenerationPainter(tools)
    private val ranges=RangePainter(tools)
    private val consumption=ConsumptionPainter(tools)
    private val indicators=IndicatorPainter(tools)
    private val backgrounds=MusicBackgroundPainter(tools,media)
    private val navigation=NavigationPainter(tools)
    private val vehicle = VehiclePainter(tools)
    private val path = Path()
    private val rect = RectF()
    private var compiled: CompiledScene? = null
    private var background: Shader? = null

    fun render(canvas: Canvas, scene: CompiledScene, data: SceneData, now: Long,
               artwork: ArtworkFrame = ArtworkFrame(null, null, 0), wallTime: Long = System.currentTimeMillis(), demoMap: Boolean = false,
               viewBox: SceneRect = FULL,targetWidth: Int=canvas.width,targetHeight: Int=canvas.height): RenderDemand {
        if (compiled !== scene) {
            // 模式/场景revision原子替换；旧缓存和轨道不跨场景操作新节点。
            val old=compiled
            if(old==null||old.background!=scene.background||old.backgroundColor!=scene.backgroundColor||old.secondColor!=scene.secondColor)background=null
            compiled = scene
            val ids=SceneTree.flatten(scene.scene.document.nodes).map { it.id }.toSet()
            gauges.retain(ids);lyrics.retain(ids);media.retain(ids);primitives.retain(ids);motion.retain(ids);pages.retain(ids);consumption.retain(ids)
        }
        motion.begin(now)
        wallClock = wallTime
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val saved = canvas.save()
        val scale = min(targetWidth / viewBox.width, targetHeight / viewBox.height)
        canvas.translate((targetWidth - viewBox.width * scale) / 2, (targetHeight - viewBox.height * scale) / 2)
        canvas.scale(scale, scale)
        canvas.translate(-viewBox.x,-viewBox.y)
        when (scene.background.kind) {
            "gradient" -> {
                val paint = tools.fill(scene.backgroundColor)
                if (background == null) {
                    val angle = Math.toRadians(scene.background.angle.toDouble())
                    val x = cos(angle).toFloat() * 960; val y = sin(angle).toFloat() * 266
                    background = LinearGradient(960-x,266-y,960+x,266+y,scene.backgroundColor,scene.secondColor,Shader.TileMode.CLAMP)
                }
                paint.shader = background; canvas.drawRect(0f,0f,1920f,532f,paint); paint.shader = null
            }
            "image" -> {
                canvas.drawColor(scene.backgroundColor)
                resources.image(scene.background.image)?.let { media.drawImage(canvas,it,1920f,532f,"cover") }
            }
            else -> canvas.drawRect(0f,0f,1920f,532f,tools.fill(scene.backgroundColor))
        }
        drawNodes(canvas,scene.nodes,data,artwork,demoMap)
        canvas.restoreToCount(saved)
        data.nextExpiry(scene.sources,now)?.let(motion::deadline)
        return RenderDemand(motion.nextAt.takeIf { it != Long.MAX_VALUE }, motion.animating)
    }

    /** 透明地图孔在该节点的z位置清除之前的主题内容；之后的组件正常叠加。
     * 地图视频始终由AutoService独占，编辑器不接收CPU Bitmap地图帧。
     */
    private fun drawNodes(canvas: Canvas,nodes: List<SceneRenderNode>,data: SceneData,artwork: ArtworkFrame,demoMap: Boolean,parentOpacity: Float = 1f) {
        for(item in nodes){
            val node=item.node;if(!node.visible)continue
            val p=item.properties;val r=node.rect
            val saved=canvas.save();canvas.translate(r.x,r.y);canvas.clipRect(0f,0f,r.width,r.height)
            val opacity=p.number("opacity",1f).coerceIn(0f,1f)*parentOpacity
            val alphaLayer=if(opacity<1f&&node.type!="map")canvas.saveLayerAlpha(0f,0f,r.width,r.height,(opacity*255).toInt())else -1
            when(node.type){
                "map"->{
                    rect.set(0f,0f,r.width,r.height)
                    val paint=tools.fill(Color.WHITE);paint.alpha=(opacity*255).toInt();paint.xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    canvas.drawRoundRect(rect,if(p.bool("rounded"))p.number("cornerRadius")else 0f,if(p.bool("rounded"))p.number("cornerRadius")else 0f,paint);paint.xfermode=null
                    if(demoMap){
                        val demoClip=canvas.save()
                        path.reset();path.addRoundRect(rect,p.number("cornerRadius"),p.number("cornerRadius"),Path.Direction.CW)
                        canvas.clipPath(path);mapPreview.draw(canvas,r.width,r.height,p);canvas.restoreToCount(demoClip)
                    }
                    if(p.bool("edgeFade"))fadeEdges(canvas,r.width,r.height,p.number("edgeFadeWidth"),compiled!!.backgroundColor)
                }
                "music-background","pages"->backgrounds.draw(canvas,item,artwork)
                "regen"->regeneration.draw(canvas,item,data,motion)
                "fuel-range","ev-range"->ranges.draw(canvas,item,data,motion)
                "indicators"->indicators.draw(canvas,item,data,motion)
                "consumption"->consumption.draw(canvas,item,data,motion)
                "gauge","ring"->gauges.draw(canvas,item,data,motion)
                "value"->primitives.value(canvas,item,data,motion)
                "bar"->primitives.bar(canvas,item,data,motion)
                "card"->primitives.card(canvas,item)
                "image"->primitives.image(canvas,item,resources)
                "text"->primitives.text(canvas,item)
                "clock"->primitives.clock(canvas,item,motion,wallClock)
                "tires"->vehicle.tires(canvas,item,data,motion)
                "pedals"->vehicle.pedals(canvas,item,data,motion)
                "status"->vehicle.status(canvas,item,data,motion)
                "navigation"->navigation.draw(canvas,item,data)
                "artwork"->media.artwork(canvas,item,artwork,motion)
                "track"->media.track(canvas,item,data.media,motion)
                "progress"->media.progress(canvas,item,data.media,motion)
                "lyrics"->lyrics.draw(canvas,item,data.media,motion)
            }
            // 分组透明度逐级继承给自身与子节点；不让整组离屏层隔断地图孔的清除语义。
            if(alphaLayer>=0)canvas.restoreToCount(alphaLayer)
            if(node.children.isNotEmpty()){
                val clip=canvas.save()
                if(p.bool("clip")){rect.set(0f,0f,r.width,r.height);path.reset();path.addRoundRect(rect,p.number("cornerRadius"),p.number("cornerRadius"),Path.Direction.CW);canvas.clipPath(path)}
                if(node.type=="pages")drawPages(canvas,item,data,artwork,demoMap,opacity)
                else drawNodes(canvas,item.children,data,artwork,demoMap,opacity)
                canvas.restoreToCount(clip)
            }
            canvas.restoreToCount(saved)
        }
    }
    private fun drawPages(canvas: Canvas,item: SceneRenderNode,data: SceneData,artwork: ArtworkFrame,demo: Boolean,opacity: Float){
        val p=item.properties
        val order=p.string("pageOrder").split(',').map { it.trim() }
        if(item.children.isEmpty())return
        val style=p.string("pageTransition")
        val visual=pages.sample(item.node.id,order,p.string("initialPage"),data.inputEpoch,data.menuSequence,data.menuReceivedAt,motion.now,
            if(style=="none")0 else p.number("pageDuration").toLong())
        fun drawPage(id: String,alpha: Float,offset: Float){
            val children=item.children.filter { it.node.properties["pageId"]==id }
            val saved=canvas.save();canvas.translate(offset,0f)
            if(alpha>0)drawNodes(canvas,children,data,artwork,demo,opacity*alpha)
            canvas.restoreToCount(saved)
        }
        val distance=if(style=="slide-fade")p.number("pageDistance")else 0f
        if(visual.fraction<1){drawPage(visual.previous,1-visual.fraction,-distance*visual.fraction);motion.animate()}
        drawPage(visual.current,visual.fraction,distance*(1-visual.fraction))
        if(p.bool("pageDots")){
            val size=order.size;val y=item.node.rect.height/2-(size-1)*15f
            for(index in order.indices){val paint=tools.fill(p.color("pageDotColor"));paint.alpha=if(order[index]==visual.current)255 else 80
                canvas.drawCircle(item.node.rect.width-10,y+index*30,4f,paint)}
        }
    }
    private fun fadeEdges(canvas: Canvas,w: Float,h: Float,width: Float,color: Int){
        val fade=minOf(width,w/2,h/2);if(fade<=0)return
        val transparent=color and 0x00FFFFFF
        val paint=tools.fill(color)
        paint.shader=LinearGradient(0f,0f,fade,0f,color,transparent,Shader.TileMode.CLAMP);canvas.drawRect(0f,0f,fade,h,paint)
        paint.shader=LinearGradient(w-fade,0f,w,0f,transparent,color,Shader.TileMode.CLAMP);canvas.drawRect(w-fade,0f,w,h,paint)
        paint.shader=LinearGradient(0f,0f,0f,fade,color,transparent,Shader.TileMode.CLAMP);canvas.drawRect(0f,0f,w,fade,paint)
        paint.shader=LinearGradient(0f,h-fade,0f,h,transparent,color,Shader.TileMode.CLAMP);canvas.drawRect(0f,h-fade,w,h,paint);paint.shader=null
    }
    private var wallClock=0L

    fun close(){backgrounds.clear();motion.clear();pages.clear();consumption.clear();mapPreview.clear();gauges.clear();lyrics.clear();media.clear();primitives.clear();tools.clearCache();compiled=null}
    companion object { private val FULL=SceneRect(0f,0f,1920f,532f) }
}
