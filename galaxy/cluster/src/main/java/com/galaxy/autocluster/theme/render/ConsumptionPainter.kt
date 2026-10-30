package com.galaxy.autocluster.theme.render

import android.graphics.*
import com.galaxy.autocluster.theme.*
import java.util.ArrayDeque
import kotlin.math.max

/** 图表仅记录真实收到的同一字段样本，横轴是本机采样顺序，不捏造距离/历史里程。 */
internal class ConsumptionPainter(private val tools: DrawTools) {
    private data class Point(val at: Long,val value: Float)
    private data class History(val source: String,val points: ArrayDeque<Point> = ArrayDeque())
    private val history=mutableMapOf<String,History>()
    private val path=Path()
    fun draw(canvas: Canvas,item: SceneRenderNode,data: SceneData,motion: SceneMotion){
        val p=item.properties;val w=item.node.rect.width;val h=item.node.rect.height;val source=p.string("source")
        val value=data.number(source,motion.now)
        val row=history[item.node.id]?.takeIf {it.source==source}?:History(source).also {history[item.node.id]=it}
        val signal=if(source.startsWith("vehicle."))data.signals[source.drop(8)]else null
        if(value!=null&&signal!=null&&(row.points.peekLast()?.at?:Long.MIN_VALUE).let { it==Long.MIN_VALUE||signal.receivedAt-it>=1000 }){
            row.points.add(Point(signal.receivedAt,value));while(row.points.size>60)row.points.removeFirst()
        }
        tools.prepare(p)
        tools.label(canvas,p.string("label"),w/2,35f,24f,p.color("muted"),w)
        tools.label(canvas,"${tools.format(value,1)} ${p.string("unit")}",w/2,h*.4f,p.number("valueSize"),p.color("foreground"),w)
        if(!p.bool("showChart"))return
        val left=28f;val right=w-20;val top=h*.57f;val bottom=h-28
        canvas.drawLine(left,bottom,right,bottom,tools.stroke(p.color("muted"),1f))
        val points=row.points.toList()
        if(points.size<2){tools.label(canvas,"等待更多实际样本",w/2,(top+bottom)/2,18f,p.color("muted"),w);return}
        val low=minOf(0f,points.minOf {it.value});val high=max(low+1,points.maxOf {it.value}*1.15f)
        path.reset()
        points.forEachIndexed {index,point->val x=left+(right-left)*index/(points.size-1);val y=bottom-(bottom-top)*(point.value-low)/(high-low)
            if(index==0)path.moveTo(x,y)else path.lineTo(x,y)}
        canvas.drawPath(path,tools.stroke(p.color("foreground"),2.5f,true))
        tools.label(canvas,"最近收到的 ${points.size} 个样本",w/2,h-3,15f,p.color("muted"),w)
    }
    fun retain(ids: Set<String>){history.keys.retainAll(ids)}
    fun clear(){history.clear()}
}
