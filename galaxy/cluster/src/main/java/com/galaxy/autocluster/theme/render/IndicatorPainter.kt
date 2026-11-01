package com.galaxy.autocluster.theme.render

import android.graphics.*
import com.galaxy.autocluster.theme.*

/** 自绘辅助状态行；每个图标独立取有效状态，未知用--，不覆盖/替代原车安全告警层。 */
internal class IndicatorPainter(private val tools: DrawTools) {
    private val rect=RectF()
    private val path=Path()
    fun draw(canvas: Canvas,item: SceneRenderNode,data: SceneData,motion: SceneMotion){
        val p=item.properties;val fields=p.string("fields").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if(fields.isEmpty())return
        val width=item.node.rect.width/fields.size;val size=minOf(p.number("iconSize"),width*.75f,item.node.rect.height*.9f)
        for((index,field)in fields.withIndex()){
            val raw=data.text("vehicle.$field",motion.now)
            val state=if(field=="cruiseState")when(raw){"active"->true;"off","standby"->false;else->null}else raw?.toFloatOrNull()?.let { when(it){0f->false;1f->true;else->null} }
            if(field=="cruiseState"&&raw=="off")continue
            val x=index*width+width/2;val y=item.node.rect.height/2
            if(state==null){tools.label(canvas,"--",x,y+size*.2f,size*.45f,p.color("offColor"));continue}
            val color=if(state&&field in setOf("parkingBrake","driverSeatbeltWarning","brakeLight"))Color.rgb(235,82,78)else if(field=="cruiseState"&&raw=="standby")Color.WHITE else if(state&&field=="highBeam")Color.rgb(70,155,245)else if(state)p.color("onColor")else p.color("offColor")
            val saved=canvas.save();canvas.translate(x-size/2,y-size/2);canvas.scale(size/32,size/32)
            when(field){
                "leftTurn","rightTurn"->{val reverse=field=="rightTurn";if(reverse){canvas.translate(32f,0f);canvas.scale(-1f,1f)}
                    path.reset();path.moveTo(3f,16f);path.lineTo(16f,4f);path.lineTo(16f,11f);path.lineTo(29f,11f);path.lineTo(29f,21f);path.lineTo(16f,21f);path.lineTo(16f,28f);path.close();canvas.drawPath(path,tools.fill(color))}
                "lowBeam","highBeam"->{rect.set(15f,4f,30f,28f);canvas.drawArc(rect,-90f,180f,false,tools.stroke(color,2f));canvas.drawLine(22f,4f,22f,28f,tools.stroke(color,2f));for(i in 0..3){val yy=6f+i*6f;canvas.drawLine(2f,yy+if(field=="lowBeam")4 else 0,12f,yy,tools.stroke(color,2f))}}
                "driverSeatbeltWarning"->{canvas.drawCircle(16f,6f,3.5f,tools.fill(color));path.reset();path.moveTo(12f,12f);path.lineTo(10f,22f);path.lineTo(22f,22f);path.moveTo(20f,12f);path.lineTo(22f,30f);path.moveTo(9f,12f);path.lineTo(25f,24f);canvas.drawPath(path,tools.stroke(color,2.5f,true))}
                "parkingBrake"->{canvas.drawCircle(16f,16f,12f,tools.stroke(color,2f));tools.label(canvas,"P",16f,23f,20f,color)}
                "positionLights"->{canvas.drawArc(10f,7f,22f,25f,-90f,360f,false,tools.stroke(color,2f));for(i in 0..2){canvas.drawLine(2f,9f+i*7,7f,9f+i*7,tools.stroke(color,2f));canvas.drawLine(25f,9f+i*7,30f,9f+i*7,tools.stroke(color,2f))}}
                "cruiseState"->{rect.set(3f,4f,29f,30f);canvas.drawArc(rect,150f,240f,false,tools.stroke(color,2.5f,true));canvas.drawLine(16f,18f,24f,8f,tools.stroke(color,2.5f,true));canvas.drawCircle(16f,18f,2f,tools.fill(color))}
                else->{canvas.drawCircle(16f,16f,12f,tools.stroke(color,2f));canvas.drawLine(16f,8f,16f,18f,tools.stroke(color,2.5f,true));canvas.drawCircle(16f,24f,1.5f,tools.fill(color))}
            }
            canvas.restoreToCount(saved)
        }
    }
}
