package com.galaxy.autocluster.theme.render

import android.graphics.*
import com.galaxy.autocluster.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

internal class PrimitivePainter(private val tools: DrawTools, private val mediaPainter: MediaPainter) {
    private val rect = RectF()
    private val path = Path()
    private val dates = mutableMapOf<String, SimpleDateFormat>()
    private data class Digits(var text: String, var previous: String, var changed: Long)
    private val digits = mutableMapOf<String, Digits>()
    fun value(canvas: Canvas, item: SceneRenderNode, data: SceneData, motion: SceneMotion) {
        val p = item.properties; val w = item.node.rect.width; val h = item.node.rect.height
        tools.prepare(p)
        val raw = data.number(p.string("source"), motion.now)
        val visual = motion.value(item.node.id, raw, p.number("followMs").toLong(), p.string("easing"))
        val content = if (raw == null) data.text(p.string("source"), motion.now) ?: "--" else tools.format(if (p.bool("rolling")) raw else visual, p.number("decimals").toInt())
        val size = p.number("valueSize"); val baseline = h / 2 + size * .2f
        val color = GaugePainter.stateColor(p, raw, p.color("foreground"))
        val inline=p.string("unitPosition")=="inline"
        tools.text.textSize=size
        val valueWidth=tools.text.measureText(content)
        tools.text.textSize=p.number("unitSize")
        val totalWidth=valueWidth+8+tools.text.measureText(p.string("unit"))
        val start=when(p.string("align")){"left"->0f;"right"->w-totalWidth;else->(w-totalWidth)/2}
        val x = if(inline)start else tools.alignedX(p,w)
        val align=if(inline)Paint.Align.LEFT else tools.alignment(p)
        if (p.string("label").isNotEmpty()) tools.label(canvas,p.string("label"),x,max(p.number("fontSize"),baseline-size*.85f-(p.number("lineHeight")-1)*p.number("fontSize")),p.number("fontSize"),p.color("muted"),w,align)
        val state = digits.getOrPut(item.node.id) { Digits(content, content, motion.now) }
        if (state.text != content) { state.previous = state.text; state.text = content; state.changed = motion.now }
        val duration = p.number("followMs").toLong()
        val progress = if (duration <= 0) 1f else ((motion.now-state.changed).toFloat()/duration).coerceIn(0f,1f)
        if (p.bool("rolling") && raw != null && progress < 1 && state.previous != "--") {
            val fraction = SceneMotion.ease(progress,p.string("easing"))
            val saved = canvas.save(); canvas.clipRect(0f,baseline-size,w,baseline+size*.2f)
            tools.label(canvas,state.previous,x,baseline-fraction*size,size,color,w,align,((1-fraction)*255).toInt())
            tools.label(canvas,content,x,baseline+(1-fraction)*size,size,color,w,align,(fraction*255).toInt())
            canvas.restoreToCount(saved); motion.animate()
        } else tools.label(canvas,content,x,baseline,size,color,w,align)
        if(inline)tools.label(canvas,p.string("unit"),start+valueWidth+8,baseline,p.number("unitSize"),p.color("muted"),w,Paint.Align.LEFT)
        else tools.label(canvas,p.string("unit"),x,min(h,baseline+p.number("unitSize")*p.number("lineHeight")+8),p.number("unitSize"),p.color("muted"),w,align)
    }

    fun bar(canvas: Canvas,item: SceneRenderNode,data: SceneData,motion: SceneMotion) {
        val p=item.properties;val w=item.node.rect.width;val h=item.node.rect.height
        val raw=data.number(p.string("source"),motion.now)
        val value=motion.value(item.node.id,raw,p.number("followMs").toLong(),p.string("easing"))
        val fraction=value?.let { ((it-p.number("minimum"))/(p.number("maximum")-p.number("minimum"))).coerceIn(0f,1f) }
        val vertical=p.string("direction")=="vertical"
        val usableHeight=if(p.bool("showValue"))max(4f,h-p.number("fontSize")-12)else h
        val segments=p.number("segments").toInt();val count=if(segments>0)segments else 1
        val length=if(vertical)usableHeight else w;val gap=if(count>1)min(p.number("gap"),length/(count-1)*.9f)else 0f;val cell=(length-gap*(count-1))/count
        for(i in 0 until count){
            val begin=i*(cell+gap);val end=begin+cell
            rect.set(if(vertical)0f else begin,if(vertical)usableHeight-end else 0f,if(vertical)w else end,if(vertical)usableHeight-begin else usableHeight)
            val radius=p.number("cornerRadius");canvas.drawRoundRect(rect,radius,radius,tools.fill(p.color("trackColor")))
            if(fraction!=null){val amount=(fraction*count-i).coerceIn(0f,1f);if(amount>0){if(vertical)rect.top=rect.bottom-rect.height()*amount else rect.right=rect.left+rect.width()*amount;canvas.drawRoundRect(rect,radius,radius,tools.fill(GaugePainter.stateColor(p,raw,p.color("accent"))))}}
        }
        if(p.bool("showValue")){tools.prepare(p);tools.label(canvas,"${tools.format(raw,p.number("decimals").toInt())} ${p.string("unit")}",w/2,h,p.number("fontSize"),p.color("foreground"),w)}
    }

    fun card(canvas: Canvas,item: SceneRenderNode) {
        val p=item.properties;rect.set(0f,0f,item.node.rect.width,item.node.rect.height)
        val radius=p.number("cornerRadius");canvas.drawRoundRect(rect,radius,radius,tools.fill(p.color("background")))
        if(p.number("borderWidth")>0)canvas.drawRoundRect(rect,radius,radius,tools.stroke(p.color("borderColor"),p.number("borderWidth")))
    }
    fun image(canvas: Canvas,item: SceneRenderNode,resources: ThemeResources) {
        val p=item.properties;val image=resources.image(p.string("image"))?:return
        val w=item.node.rect.width;val h=item.node.rect.height;rect.set(0f,0f,w,h);path.reset();path.addRoundRect(rect,p.number("cornerRadius"),p.number("cornerRadius"),Path.Direction.CW)
        val saved=canvas.save();canvas.clipPath(path);mediaPainter.drawImage(canvas,image,w,h,p.string("fit"));canvas.restoreToCount(saved)
    }
    fun text(canvas: Canvas,item: SceneRenderNode) {
        val p=item.properties;val w=item.node.rect.width;val h=item.node.rect.height
        tools.prepare(p)
        val size=p.number("fontSize");val iconSize=if(p.string("icon")=="none")0f else p.number("iconSize")
        if(iconSize>0)symbol(canvas,p.string("icon"),0f,(h-iconSize)/2,iconSize,p.color("foreground"))
        val available=max(1f,w-iconSize-if(iconSize>0)12f else 0f)
        val saved=canvas.save();canvas.translate(w-available,0f)
        tools.label(canvas,p.string("text"),tools.alignedX(p,available),h/2+size*.33f,size,p.color("foreground"),available,tools.alignment(p));canvas.restoreToCount(saved)
    }
    fun clock(canvas: Canvas,item: SceneRenderNode,motion: SceneMotion,wallTime: Long) {
        val p=item.properties;val w=item.node.rect.width;val h=item.node.rect.height
        tools.prepare(p)
        fun formatted(pattern: String)=dates.getOrPut(pattern){SimpleDateFormat(pattern,Locale.getDefault())}.format(Date(wallTime))
        val size=p.number("fontSize");val showDate=p.string("dateFormat")!="none"
        val baseline=if(showDate)h*.48f+size*.25f else h/2+size*.33f
        tools.label(canvas,formatted(p.string("timeFormat")),tools.alignedX(p,w),baseline,size,p.color("foreground"),w,tools.alignment(p))
        if(showDate)tools.label(canvas,formatted(p.string("dateFormat")),tools.alignedX(p,w),min(h,baseline+p.number("dateSize")*p.number("lineHeight")+6),p.number("dateSize"),p.color("muted"),w,tools.alignment(p))
        val period=if(p.string("timeFormat").contains("ss"))1000 else 60000
        motion.deadline(motion.now+period-wallTime%period)
    }

    /** 自写通用线性图形，不装载OEM图标或平台私有控件。 */
    fun symbol(canvas: Canvas,kind: String,x: Float,y: Float,size: Float,color: Int) {
        val saved=canvas.save();canvas.translate(x,y);canvas.scale(size/32,size/32)
        path.reset()
        when(kind){
            "bolt"->{path.moveTo(18f,2f);path.lineTo(7f,18f);path.lineTo(15f,18f);path.lineTo(13f,30f);path.lineTo(26f,12f);path.lineTo(18f,12f);path.close();canvas.drawPath(path,tools.fill(color))}
            "fuel"->{canvas.drawRoundRect(5f,6f,20f,28f,2f,2f,tools.stroke(color,2f));canvas.drawRect(9f,10f,16f,17f,tools.stroke(color,1.5f));path.moveTo(20f,13f);path.lineTo(25f,16f);path.lineTo(25f,24f);path.lineTo(29f,24f);path.lineTo(29f,12f);path.lineTo(25f,8f);canvas.drawPath(path,tools.stroke(color,2f))}
            "music"->{path.moveTo(11f,25f);path.lineTo(11f,8f);path.lineTo(26f,5f);path.lineTo(26f,22f);canvas.drawPath(path,tools.stroke(color,2.5f));canvas.drawOval(4f,22f,12f,28f,tools.fill(color));canvas.drawOval(19f,19f,27f,25f,tools.fill(color))}
            "temperature"->{canvas.drawRoundRect(13f,3f,20f,23f,3f,3f,tools.stroke(color,2f));canvas.drawCircle(16.5f,25f,6f,tools.stroke(color,2f));canvas.drawLine(16.5f,12f,16.5f,25f,tools.stroke(color,3f))}
            else->{path.moveTo(5f,25f);path.lineTo(25f,5f);path.moveTo(12f,5f);path.lineTo(25f,5f);path.lineTo(25f,18f);canvas.drawPath(path,tools.stroke(color,2.5f,true))}
        }
        canvas.restoreToCount(saved)
    }
    fun clear(){dates.clear();digits.clear()}
    fun retain(ids: Set<String>){digits.keys.retainAll(ids)}
}
