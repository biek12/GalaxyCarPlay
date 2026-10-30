package com.galaxy.autocluster.theme.render

import android.graphics.*
import com.galaxy.autocluster.theme.*
import kotlin.math.*

/** 静态Picture缓存盘面/刻度/数字；动态指针、阈值、辅助弧与真实读数单独绘制。 */
internal class GaugePainter(private val tools: DrawTools) {
    private data class StaticLayer(val width: Float,val height: Float,val properties: VisualProperties,val picture: Picture)
    private val static = mutableMapOf<String, StaticLayer>()
    private val rect = RectF()
    private val path = Path()
    private val regeneration=RegenerationPainter(tools)
    fun draw(canvas: Canvas, item: SceneRenderNode, data: SceneData, motion: SceneMotion) {
        val node = item.node; val p = item.properties
        val scale = min(node.rect.width, node.rect.height) / 420f
        val cx = node.rect.width / 2; val cy = node.rect.height / 2; val radius = 200 * scale
        var cached=static[node.id]
        if(cached==null||cached.width!=node.rect.width||cached.height!=node.rect.height||cached.properties!==p){
            val picture=Picture()
            val recording=picture.beginRecording(ceil(node.rect.width).toInt(),ceil(node.rect.height).toInt())
            drawStatic(recording,p,cx,cy,radius,scale);picture.endRecording()
            cached=StaticLayer(node.rect.width,node.rect.height,p,picture);static[node.id]=cached;gradients.remove(node.id)
        }
        canvas.drawPicture(cached.picture); tools.prepare(p)
        val raw = data.number(p.string("source"), motion.now)
        val value = motion.value(node.id, raw, p.number("followMs").toLong(), p.string("easing"))
        val min = p.number("minimum"); val max = p.number("maximum")
        val intro = motion.intro(node.id, p.bool("intro"), p.number("introMs", 1200f).toLong(), (data.number("vehicle.displaySpeed", motion.now) ?: 0f) > 0f)
        val fraction = intro ?: value?.let { ((it - min) / (max - min)).coerceIn(0f, 1f) }
        val color = stateColor(p, raw, p.color("accent"))
        val start = p.number("startAngle"); val sweep = p.number("sweepAngle")
        if (p.bool("litTicks")) drawTicks(canvas, p, cx, cy, radius, scale, value)
        if (fraction != null && p.string("indicator") in setOf("arc", "both")) {
            val r = radius * p.number("radius"); rect.set(cx-r, cy-r, cx+r, cy+r)
            val paint = tools.stroke(color, p.number("arcWidth") * scale, p.bool("roundEnds"))
            if (color == p.color("accent") && p.color("accentEnd") != color) {
                // 仅当确有渐变时创建；对应Shader缓存随静态场景一起保留。
                paint.shader = gradients.getOrPut(node.id) { SweepGradient(cx, cy, intArrayOf(color, p.color("accentEnd")), floatArrayOf(0f, 1f)).apply { setLocalMatrix(Matrix().apply { setRotate(start, cx, cy) }) } }
            }
            canvas.drawArc(rect, start, sweep * fraction, false, paint); paint.shader = null
        }
        if (fraction != null && p.string("indicator") in setOf("needle", "both")) {
            val saved = canvas.save(); canvas.rotate(start + sweep * fraction, cx, cy)
            val length = radius * p.number("needleLength", .85f); val tail = p.number("needleTail") * scale; val width = p.number("needleWidth", 4f) * scale
            val needleColor = stateColor(p, raw, p.color("needleColor"))
            when (p.string("needleShape")) {
                "wedge", "outline" -> { path.reset(); path.moveTo(cx-tail, cy-width); path.lineTo(cx+length, cy); path.lineTo(cx-tail, cy+width); path.close(); canvas.drawPath(path, if (p.string("needleShape") == "outline") tools.stroke(needleColor, max(1f, scale)) else tools.fill(needleColor)) }
                "bars" -> { canvas.drawLine(cx-tail, cy-width, cx+length, cy-width, tools.stroke(needleColor, max(1f, width / 3))); canvas.drawLine(cx-tail, cy+width, cx+length, cy+width, tools.paint) }
                else -> canvas.drawLine(cx-tail, cy, cx+length, cy, tools.stroke(needleColor, width, true))
            }
            canvas.restoreToCount(saved)
            val hub = p.number("hubRadius") * scale
            if (hub > 0) canvas.drawCircle(cx, cy, hub, if (p.bool("hubRing")) tools.stroke(p.color("hubColor"), 2*scale) else tools.fill(p.color("hubColor")))
        }
        if (p.bool("valueVisible")) tools.label(canvas, tools.format(raw, p.number("decimals").toInt()), cx, cy + p.number("valueY") * scale,
            p.number("valueSize") * scale, stateColor(p, raw, p.color("foreground")), node.rect.width)
        val unitY = cy + (p.number("valueY") + p.number("unitSize") + 8) * scale
        tools.label(canvas, p.string("unit"), cx, unitY, p.number("unitSize") * scale, p.color("muted"))
        if (p.string("secondarySource").isNotEmpty()) tools.label(canvas, "${data.text(p.string("secondarySource"), motion.now) ?: "--"} ${p.string("secondaryUnit")}", cx, unitY + (p.number("secondarySize") + 9)*scale, p.number("secondarySize")*scale, p.color("muted"), node.rect.width)
        satellite(canvas, p, data, motion.now, "leftSatellite", cx, cy, radius, scale, 150f)
        satellite(canvas, p, data, motion.now, "rightSatellite", cx, cy, radius, scale, -30f)
        regeneration.draw(canvas,item,data,motion,true)
        cruise(canvas,p,data,motion.now,cx,cy,radius,scale,min,max,start,sweep,if(intro==null)value else null)
        data.number(p.string("limiterSource"), motion.now)?.let { target ->
            marker(canvas,start+sweep*((target-min)/(max-min)).coerceIn(0f,1f),cx,cy,radius,scale,p.color("limiterColor"),false)
        }
        for (target in p.values("extraMarks")) marker(canvas, start+sweep*((target-min)/(max-min)).coerceIn(0f,1f),cx,cy,radius,scale,p.color("accent"),false)
        data.number(p.string("roadLimitSource"), motion.now)?.let { limit ->
            canvas.drawCircle(cx + 100*scale, cy + 85*scale, 26*scale, tools.fill(Color.WHITE))
            canvas.drawCircle(cx + 100*scale, cy + 85*scale, 26*scale, tools.stroke(Color.rgb(220,55,62),4*scale))
            tools.label(canvas,tools.format(limit),cx+100*scale,cy+92*scale,20*scale,Color.BLACK)
        }
    }
    private fun cruise(canvas: Canvas,p: VisualProperties,data: SceneData,now: Long,
                       cx: Float,cy: Float,radius: Float,scale: Float,minimum: Float,maximum: Float,start: Float,sweep: Float,actual: Float?) {
        if(!p.bool("cruiseVisible"))return
        val state=CruiseVisualState.from(data.text(p.string("cruiseStateSource"),now))
        if(state==CruiseVisualState.OFF||state==CruiseVisualState.UNKNOWN)return
        val color=if(state==CruiseVisualState.ACTIVE)p.color("cruiseColor")else p.color("cruiseStandbyColor")
        val target=data.number(p.string("cruiseSource"),now)?.takeIf { it in minimum..maximum }
        // 未发布/失效的目标不能钳位成0或画到量程端点；真实指针仍独立取实际车速。
        if(target!=null){
            val angle=start+sweep*(target-minimum)/(maximum-minimum)
            val radians=Math.toRadians(angle.toDouble())
            canvas.drawCircle(cx+cos(radians).toFloat()*radius*p.number("cruiseDotPosition"),
                cy+sin(radians).toFloat()*radius*p.number("cruiseDotPosition"),p.number("cruiseDotRadius")*scale,tools.fill(color))
            val outside=radius*1.035f;rect.set(cx-outside,cy-outside,cx+outside,cy+outside)
            val arc=p.number("cruiseMarkerSweep")
            when(p.string("cruiseMarkerMode")) {
                "difference" -> actual?.let {
                    val end=start+sweep*((it-minimum)/(maximum-minimum)).coerceIn(0f,1f)
                    // 与屏上的平滑指针取同一个value；有正负方向，不取跨零点的最短弧。
                    if(kotlin.math.abs(end-angle)>.01f)canvas.drawArc(rect,angle,end-angle,false,tools.stroke(color,3f*scale,true))
                }
                "target" -> if(arc>0)canvas.drawArc(rect,angle-arc/2,arc,false,tools.stroke(color,3f*scale,true))
            }
        }
        val size=p.number("cruiseSize")*scale;val text=tools.format(target)
        tools.text.textSize=size;val width=tools.text.measureText(text)
        val total=width+size*1.6f;val x=cx-total/2;val y=cy+p.number("cruiseY")*scale
        cruiseIcon(canvas,x,y-size*.84f,size,color)
        tools.label(canvas,text,x+size*1.6f,y,size,color,align=Paint.Align.LEFT)
    }
    private fun cruiseIcon(canvas: Canvas,x: Float,y: Float,size: Float,color: Int) {
        rect.set(x,y,x+size,y+size)
        canvas.drawArc(rect,150f,240f,false,tools.stroke(color,max(1f,size*.11f),true))
        canvas.drawLine(x+size*.5f,y+size*.52f,x+size*.77f,y+size*.22f,tools.stroke(color,max(1f,size*.1f),true))
        canvas.drawCircle(x+size*.5f,y+size*.52f,size*.1f,tools.fill(color))
    }
    private val gradients = mutableMapOf<String, Shader>()
    private fun drawStatic(canvas: Canvas, p: VisualProperties, cx: Float, cy: Float, r: Float, s: Float) {
        tools.prepare(p)
        if (p.bool("faceVisible")) canvas.drawCircle(cx,cy,r*p.number("faceRadius"),tools.fill(p.color("faceColor")))
        if (p.number("bezelWidth") > 0) canvas.drawCircle(cx,cy,r*p.number("faceRadius"),tools.stroke(p.color("bezelColor"),p.number("bezelWidth")*s))
        if (p.bool("gloss")) { val paint = tools.fill(Color.WHITE); paint.shader=RadialGradient(cx-r*.35f,cy-r*.7f,r*1.6f,intArrayOf(0x22FFFFFF,0x00FFFFFF),null,Shader.TileMode.CLAMP);canvas.drawCircle(cx,cy,r*p.number("faceRadius"),paint);paint.shader=null }
        if (p.bool("trackVisible")) { val a=r*p.number("radius");rect.set(cx-a,cy-a,cx+a,cy+a);canvas.drawArc(rect,p.number("startAngle"),p.number("sweepAngle"),false,tools.stroke(p.color("trackColor"),p.number("arcWidth")*s,p.bool("roundEnds"))) }
        if (!p.bool("litTicks")) drawTicks(canvas,p,cx,cy,r,s,null)
        val step=p.number("secondTickStep")
        if(step>0){val count=((p.number("maximum")-p.number("minimum"))/step).toInt().coerceAtMost(400);for(i in 0..count){val angle=p.number("startAngle")+p.number("sweepAngle")*i*step/(p.number("maximum")-p.number("minimum"));tick(canvas,angle,cx,cy,r*.99f,p.number("secondTickLength")*s,s,p.color("secondTickColor"),false)}}
    }
    private fun drawTicks(canvas: Canvas,p: VisualProperties,cx: Float,cy: Float,r: Float,s: Float,value: Float?) {
        val minimum=p.number("minimum");val maximum=p.number("maximum");val step=p.number("majorStep");val count=((maximum-minimum)/step).toInt().coerceAtMost(200)
        val minor=p.number("minorCount").toInt();val skip=p.values("numeralSkip")
        for(i in 0..count){
            val number=minimum+i*step;val angle=p.number("startAngle")+p.number("sweepAngle")*(number-minimum)/(maximum-minimum)
            val color=if(value!=null&&number<=value)p.color("litColor") else p.color("tickColor")
            if(p.string("tickStyle")!="none")tick(canvas,angle,cx,cy,r*p.number("tickRadius"),p.number("majorLength")*s,p.number("tickWidth")*s,color,p.string("tickStyle")=="dot")
            if(p.bool("numerals")&&skip.none { abs(it-number)<.001f }){
                val radians=Math.toRadians(angle.toDouble());val x=cx+cos(radians).toFloat()*r*p.number("numeralRadius");val y=cy+sin(radians).toFloat()*r*p.number("numeralRadius")
                val saved=canvas.save();if(p.bool("numeralRotate"))canvas.rotate(angle+90,x,y)
                tools.label(canvas,tools.format(number/p.number("numeralDivisor")),x,y+p.number("numeralSize")*s*.33f,p.number("numeralSize")*s,p.color("numeralColor"));canvas.restoreToCount(saved)
            }
            if(i<count&&minor>0&&p.string("tickStyle")!="none")for(j in 1..minor){val a=angle+p.number("sweepAngle")*step/(maximum-minimum)*j/(minor+1);tick(canvas,a,cx,cy,r*p.number("tickRadius"),p.number("minorLength")*s,p.number("minorWidth")*s,color,p.string("tickStyle")=="dot")}
        }
    }
    private fun tick(canvas: Canvas,angle: Float,cx: Float,cy: Float,r: Float,length: Float,width: Float,color: Int,dot: Boolean){val a=Math.toRadians(angle.toDouble());val c=cos(a).toFloat();val s=sin(a).toFloat();if(dot)canvas.drawCircle(cx+r*c,cy+r*s,max(width,1f),tools.fill(color))else canvas.drawLine(cx+r*c,cy+r*s,cx+(r-length)*c,cy+(r-length)*s,tools.stroke(color,width))}
    private fun marker(canvas: Canvas,angle: Float,cx: Float,cy: Float,r: Float,s: Float,color: Int,dot: Boolean){tick(canvas,angle,cx,cy,r*.95f,20*s,if(dot)5*s else 3*s,color,dot)}
    private fun satellite(canvas: Canvas,p: VisualProperties,data: SceneData,now: Long,key: String,cx: Float,cy: Float,r: Float,s: Float,start: Float){val source=p.string(key);if(source.isEmpty())return;val value=data.number(source,now);val radius=r*.99f;rect.set(cx-radius,cy-radius,cx+radius,cy+radius);canvas.drawArc(rect,start,60f,false,tools.stroke(p.color("trackColor"),p.number("satelliteWidth")*s,true));if(value!=null)canvas.drawArc(rect,start,60f*(value/p.number("satelliteMaximum",100f)).coerceIn(0f,1f),false,tools.stroke(p.color("satelliteColor"),p.number("satelliteWidth")*s,true))}
    fun clear(){static.clear();gradients.clear()}
    fun retain(ids: Set<String>){static.keys.retainAll(ids);gradients.keys.retainAll(ids)}
    companion object {
        fun stateColor(p: VisualProperties,value: Float?,normal: Int): Int {
            if(value==null)return normal
            fun below(key: String)=p.optionalNumber(key)?.let { value<it }==true
            fun above(key: String)=p.optionalNumber(key)?.let { value>it }==true
            return when {below("criticalBelow")||above("criticalAbove")->p.color("criticalColor");below("warningBelow")||above("warningAbove")->p.color("warningColor");else->normal}
        }
    }
}
