package com.galaxy.autocluster.theme.render

import android.graphics.Canvas
import android.graphics.RectF
import com.galaxy.autocluster.theme.*
import kotlin.math.min

/** 显示采用驱动+、回收−；原始信号正负必须另行核实，不能把显示约定写成车辆协议。 */
internal class RegenerationPainter(private val tools: DrawTools) {
    private val rect=RectF()
    fun draw(canvas: Canvas,item: SceneRenderNode,data: SceneData,motion: SceneMotion,integrated: Boolean=false){
        val p=item.properties
        if(integrated&&!p.bool("regenVisible"))return
        val level=when(data.text(p.string("regenLevelSource"),motion.now)){"low"->"低";"medium"->"中";"high"->"高";"auto"->"自动";else->"--"}
        val flow=PowerFlow.read(p,data,motion.now)
        val recovery=flow.regeneration;val drive=flow.drive
        val bipolar=p.string("powerDisplay")=="bipolar"
        tools.prepare(p)
        val w=item.node.rect.width;val h=item.node.rect.height;val scale=if(integrated)min(w,h)/420f else 1f
        val green=p.color("regenColor");val blue=p.color("driveColor")
        val driving=bipolar&&drive!=null&&drive>0
        val color=if(driving)blue else green
        val label=when {
            driving&&recovery!=null&&recovery>0 -> "驱动 +${tools.format(drive,0)} / 回收 −${tools.format(recovery,0)} kW"
            driving -> "驱动 +${tools.format(drive,1)} kW · 回收$level"
            recovery!=null&&recovery>0 -> "回收 $level · −${tools.format(recovery,1)} kW"
            recovery==0f -> "回收 $level · 0.0 kW"
            else -> "回收 $level · -- kW"
        }
        if(integrated){
            val r=200f*scale*p.number("regenRadius");val cx=w/2;val cy=h/2
            rect.set(cx-r,cy-r,cx+r,cy+r)
            canvas.drawArc(rect,52f,76f,false,tools.stroke(0x553F786C,p.number("regenWidth")*scale,true))
            if(bipolar){
                if(recovery!=null&&recovery>0)canvas.drawArc(rect,90f,38f*(recovery/p.number("regenMaximum")).coerceIn(0f,1f),false,tools.stroke(green,p.number("regenWidth")*scale,true))
                if(drive!=null&&drive>0)canvas.drawArc(rect,90f,-38f*(drive/p.number("driveMaximum")).coerceIn(0f,1f),false,tools.stroke(blue,p.number("regenWidth")*scale,true))
                canvas.drawLine(cx,cy+r-4*scale,cx,cy+r+4*scale,tools.stroke(p.color("muted"),scale))
            }else if(recovery!=null&&recovery>0)canvas.drawArc(rect,128f,-76f*(recovery/p.number("regenMaximum")).coerceIn(0f,1f),false,tools.stroke(green,p.number("regenWidth")*scale,true))
            tools.label(canvas,label,cx,cy+p.number("regenY")*scale,p.number("regenSize")*scale,color,w*.83f)
        }else{
            tools.label(canvas,"动能回收 · $level",w/2,h*.24f,p.number("regenSize"),p.color("muted"),w)
            val value=if(driving)"+${tools.format(drive,1)}" else if(recovery!=null&&recovery>0)"−${tools.format(recovery,1)}" else tools.format(recovery,1)
            tools.label(canvas,"$value kW",w/2,h*.6f,p.number("regenSize")*1.6f,color,w)
            val bar=h*.82f;val mid=if(bipolar)w/2 else 8f
            canvas.drawRoundRect(8f,bar,w-8,bar+5,2.5f,2.5f,tools.fill(0x553F786C))
            if(bipolar){
                if(recovery!=null&&recovery>0)canvas.drawRect(mid-(mid-8)*(recovery/p.number("regenMaximum")).coerceIn(0f,1f),bar,mid,bar+5,tools.fill(green))
                if(drive!=null&&drive>0)canvas.drawRect(mid,bar,mid+(mid-8)*(drive/p.number("driveMaximum")).coerceIn(0f,1f),bar+5,tools.fill(blue))
                canvas.drawLine(mid,bar-3,mid,bar+8,tools.stroke(p.color("muted"),1f))
            }else if(recovery!=null&&recovery>0)canvas.drawRoundRect(8f,bar,8f+(w-16)*(recovery/p.number("regenMaximum")).coerceIn(0f,1f),bar+5,2.5f,2.5f,tools.fill(green))
        }
    }
}
