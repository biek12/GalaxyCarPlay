package com.galaxy.autocluster.theme.render

import android.graphics.*
import com.galaxy.autocluster.theme.*
import kotlin.math.*

internal class VehiclePainter(private val tools: DrawTools) {
    private val rect=RectF()
    fun tires(canvas: Canvas,item: SceneRenderNode,data: SceneData,motion: SceneMotion) {
        val p=item.properties;val w=item.node.rect.width;val h=item.node.rect.height
        tools.prepare(p)
        rect.set(w*.39f,h*.15f,w*.61f,h*.85f)
        canvas.drawRoundRect(rect,w*.06f,w*.06f,tools.stroke(p.color("carColor"),3f))
        canvas.drawLine(w*.41f,h*.38f,w*.59f,h*.38f,tools.stroke(p.color("carColor"),2f))
        val keys=listOf("frontLeft","frontRight","rearLeft","rearRight")
        val names=listOf("左前","右前","左后","右后")
        for(i in keys.indices){
            val value=data.number("vehicle.tire.${keys[i]}",motion.now)
            val converted=value?.let { when(p.string("unit")){"bar"->it/100;"psi"->it*.145038f;else->it} }
            val color=if(value!=null&&(value<p.number("low")||value>p.number("high")))p.color("warningColor") else p.color("foreground")
            val x=w*(if(i%2==0).18f else .82f);val y=h*(if(i<2).28f else .74f)
            canvas.drawRoundRect(w*(if(i%2==0).33f else .62f),y-20,w*(if(i%2==0).38f else .67f),y+20,5f,5f,tools.fill(p.color("carColor")))
            tools.label(canvas,tools.format(converted,if(p.string("unit")=="kPa")0 else 1),x,y,p.number("fontSize"),color,w*.3f)
            tools.label(canvas,if(p.bool("wheelLabels"))"${names[i]} · ${p.string("unit")}" else p.string("unit"),x,y+p.number("fontSize")*.8f,p.number("fontSize")*.5f,p.color("muted"),w*.31f)
        }
    }
    fun pedals(canvas: Canvas,item: SceneRenderNode,data: SceneData,motion: SceneMotion) {
        val p=item.properties;val w=item.node.rect.width;val h=item.node.rect.height
        tools.prepare(p)
        for((index,key) in listOf("accelerator","brake").withIndex()){
            val value=data.number("vehicle.$key",motion.now)
            val x=w*(if(index==0).24f else .64f);val bottom=h*.70f;val top=h*.12f
            val width=p.number("barWidth");rect.set(x-width/2,top,x+width/2,bottom)
            canvas.drawRoundRect(rect,width/2,width/2,tools.fill(p.color("muted") and 0x00FFFFFF or 0x44000000))
            if(value!=null){rect.top=bottom-(bottom-top)*(value/100).coerceIn(0f,1f);canvas.drawRoundRect(rect,width/2,width/2,tools.fill(p.color(if(index==0)"acceleratorColor" else "brakeColor")))}
            tools.label(canvas,"${if(index==0)"油门" else "刹车"} ${tools.format(value)}%",x,bottom+p.number("fontSize"),p.number("fontSize")*.7f,p.color("foreground"),w*.45f)
        }
        if(p.bool("showSteering"))tools.label(canvas,"方向盘 ${tools.format(data.number("vehicle.steeringAngle",motion.now),1)}°",w/2,h,p.number("fontSize")*.8f,p.color("muted"),w)
    }
    fun status(canvas: Canvas,item: SceneRenderNode,data: SceneData,motion: SceneMotion) {
        val p=item.properties;val w=item.node.rect.width;val h=item.node.rect.height
        tools.prepare(p)
        val fields=p.string("fields").split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(12)
        if(fields.isEmpty())return
        val cell=w/fields.size;val size=p.number("fontSize")
        for((index,key) in fields.withIndex()){
            val x=cell*(index+.5f);val width=max(1f,cell-p.number("spacing"))
            if(p.bool("showNames"))tools.label(canvas,labels[key]?:key,x,h*.28f,size*.52f,p.color("muted"),width)
            tools.label(canvas,data.text("vehicle.$key",motion.now)?:"--",x,if(p.bool("showNames"))h*.88f else h/2+size*.3f,size,p.color("foreground"),width)
        }
    }
    companion object {
        val labels=mapOf("cruiseState" to "巡航状态","cruiseSetSpeed" to "巡航目标速度","regenLevel" to "回收档位","regenPowerKw" to "独立回收功率（kW）","fuelConsumption" to "平均油耗","electricConsumption" to "平均电耗","electricPowerRaw" to "原始电功率（单位待核）","electricPowerStateRaw" to "原始电功率状态","displaySpeed" to "车速","rpm" to "转速","gear" to "档位","soc" to "电量","fuel" to "油量","evRange" to "纯电续航","fuelRange" to "燃油续航","outsideTemperature" to "外温","accelerator" to "油门","brake" to "刹车","steeringAngle" to "转向")
    }
}
