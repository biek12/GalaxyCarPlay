package com.galaxy.autocluster.theme.render

import android.graphics.*
import com.galaxy.autocluster.theme.*

/** AMap NEW_ICON来自CarPlay已存在的ClusterTurnGuidance转换；未知不画直行箭头。 */
internal class NavigationPainter(private val tools: DrawTools) {
    private val path=Path()
    private val rect=RectF()
    fun draw(canvas: Canvas,item: SceneRenderNode,data: SceneData){
        val p=item.properties;val w=item.node.rect.width;val h=item.node.rect.height
        tools.prepare(p)
        if(p.string("navigationLayout")=="trip"){
            val values=listOf(data.navigation["remaining"],data.navigation["arrival"],data.navigation["remainingDistance"])
            val labels=listOf("剩余时间","预计到达","剩余距离")
            for(index in values.indices){val x=(index+.5f)*w/3
                tools.label(canvas,values[index]?.takeIf {it.isNotBlank()}?:"--",x,h*.45f,p.number("fontSize"),p.color("foreground"),w/3-10)
                tools.label(canvas,labels[index],x,h*.86f,p.number("fontSize")*.6f,p.color("muted"),w/3-10)}
            return
        }
        val icon=data.navigation["maneuver"]?.toIntOrNull()?:0
        val size=p.number("arrowSize");val show=p.bool("showArrow")&&icon in known
        if(show)arrow(canvas,icon,data.navigation["roundaboutExit"]?.toIntOrNull(),size,p.color("foreground"))
        val left=if(show)size+22 else 0f;val available=(w-left).coerceAtLeast(1f)
        var y=p.number("fontSize")
        val x=left+tools.alignedX(p,available)
        fun line(value: String?,color: Int){if(y>h)return;tools.label(canvas,value?.takeIf {it.isNotBlank()}?:"--",x,y,p.number("fontSize"),color,available,tools.alignment(p));y+=p.number("fontSize")*p.number("lineHeight")}
        if(p.bool("showDistance"))line(data.navigation["distance"],p.color("accent"))
        if(p.bool("showRoad")){
            val instruction=data.navigation["instruction"]?.takeIf {it.isNotBlank()}?:names[icon]
            if(instruction!=null)line(instruction,p.color("muted"))
            line(data.navigation["road"],p.color("foreground"))
        }
        if(p.bool("showArrival"))line(listOfNotNull(data.navigation["remaining"],data.navigation["arrival"],data.navigation["remainingDistance"]).joinToString(" · "),p.color("muted"))
        val lanes=data.navigation["laneAngles"].orEmpty().split(',').mapNotNull{it.toIntOrNull()}.take(8)
        val preferred=data.navigation["laneRecommended"].orEmpty().split(',').mapNotNull{it.toIntOrNull()}
        if(lanes.isNotEmpty()&&lanes.size==preferred.size){
            val laneSize=minOf(44f,w/lanes.size);val start=(w-laneSize*lanes.size)/2
            for((index,angle)in lanes.withIndex()){
                val save=canvas.save();canvas.translate(start+index*laneSize,h-laneSize)
                canvas.rotate(angle.toFloat(),laneSize/2,laneSize/2)
                val color=p.color(if(preferred[index]==1)"accent"else"muted")
                val stroke=tools.stroke(color,3f,true)
                canvas.drawLine(laneSize/2,laneSize*.85f,laneSize/2,laneSize*.15f,stroke)
                canvas.drawLine(laneSize/2,laneSize*.15f,laneSize*.3f,laneSize*.35f,stroke)
                canvas.drawLine(laneSize/2,laneSize*.15f,laneSize*.7f,laneSize*.35f,stroke)
                canvas.restoreToCount(save)
            }
        }
    }
    private fun arrow(canvas: Canvas,icon: Int,exit: Int?,size: Float,color: Int){
        val saved=canvas.save();canvas.scale(size/64,size/64)
        if(icon in setOf(2,4,6,8,17,18)){canvas.translate(64f,0f);canvas.scale(-1f,1f)}
        path.reset()
        when(icon){
            9->{path.moveTo(32f,60f);path.lineTo(32f,8f);path.moveTo(14f,26f);path.lineTo(32f,8f);path.lineTo(50f,26f)}
            4,5->{path.moveTo(12f,60f);path.lineTo(12f,43f);path.lineTo(50f,8f);path.moveTo(29f,8f);path.lineTo(50f,8f);path.lineTo(50f,29f)}
            6,7->{path.moveTo(12f,60f);path.lineTo(12f,10f);path.lineTo(51f,48f);path.moveTo(51f,28f);path.lineTo(51f,48f);path.lineTo(31f,48f)}
            8,19->{path.moveTo(12f,60f);path.lineTo(12f,26f);path.cubicTo(12f,0f,50f,0f,50f,26f);path.lineTo(50f,44f);path.moveTo(39f,33f);path.lineTo(50f,44f);path.lineTo(61f,33f)}
            11,12,17,18->{rect.set(12f,10f,52f,50f);canvas.drawArc(rect,70f,300f,false,tools.stroke(color,6f,true));path.moveTo(32f,60f);path.lineTo(32f,48f)}
            15->{path.moveTo(15f,60f);path.lineTo(15f,5f);path.moveTo(15f,5f);path.lineTo(53f,5f);path.lineTo(53f,29f);path.lineTo(15f,29f)}
            else->{path.moveTo(14f,60f);path.lineTo(14f,30f);path.quadTo(14f,18f,28f,18f);path.lineTo(54f,18f);path.moveTo(42f,6f);path.lineTo(54f,18f);path.lineTo(42f,30f)}
        }
        canvas.drawPath(path,tools.stroke(color,6f,true));canvas.restoreToCount(saved)
        if(icon in setOf(11,12,17,18)&&exit!=null&&exit in 1..99)tools.label(canvas,exit.toString(),size/2,size*.58f,size*.25f,color)
    }
    companion object {
        private val names=mapOf(2 to "左转",3 to "右转",4 to "向左前方",5 to "向右前方",6 to "向左急转",7 to "向右急转",8 to "掉头",9 to "直行",11 to "进入环岛",12 to "驶离环岛",15 to "到达目的地",17 to "进入环岛",18 to "驶离环岛",19 to "掉头")
        private val known=names.keys
    }
}
