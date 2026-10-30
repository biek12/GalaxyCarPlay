package com.galaxy.autocluster.theme.render

import android.graphics.Canvas
import android.graphics.Paint
import com.galaxy.autocluster.theme.*

/** 续航km和剩余百分比分开绑定；这是可编辑条状组件，非尚未采集的原厂视觉认证。 */
internal class RangePainter(private val tools: DrawTools) {
    private val symbols=PrimitivePainter(tools,MediaPainter(tools))
    fun draw(canvas: Canvas,item: SceneRenderNode,data: SceneData,motion: SceneMotion){
        val p=item.properties;tools.prepare(p)
        val w=item.node.rect.width;val h=item.node.rect.height;val size=p.number("fontSize")
        val distance=data.number(p.string("source"),motion.now)?.takeIf { it>=0 }
        val level=data.number(p.string("levelSource"),motion.now)?.takeIf { it in 0f..100f }
        symbols.symbol(canvas,p.string("rangeIcon"),0f,0f,size,p.color("muted"))
        tools.label(canvas,p.string("label"),size+8,size,size,p.color("muted"),w*.4f,Paint.Align.LEFT)
        tools.label(canvas,"${tools.format(distance)} km",w,size,size,p.color("foreground"),w*.6f,Paint.Align.RIGHT)
        val thickness=minOf(p.number("trackHeight"),h*.2f);val top=h-thickness
        canvas.drawRoundRect(0f,top,w,h,thickness/2,thickness/2,tools.fill(p.color("trackColor")))
        if(level!=null)canvas.drawRoundRect(0f,top,w*level/100,h,thickness/2,thickness/2,tools.fill(p.color("accent")))
    }
}
