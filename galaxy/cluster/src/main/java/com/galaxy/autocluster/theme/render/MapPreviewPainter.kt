package com.galaxy.autocluster.theme.render

import android.graphics.*
import com.galaxy.autocluster.theme.*

/** 离线/演示路网预览，自写原图只生成一次；缓存柔焦变体，不在真实输出绘地图Bitmap。 */
internal class MapPreviewPainter(private val tools: DrawTools) {
    private val path=Path()
    private val rect=RectF()
    private val variants=object: LinkedHashMap<Int,Bitmap>(4,.75f,true){
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int,Bitmap>?)=size>4
    }
    private val source by lazy {
        Bitmap.createBitmap(960,268,Bitmap.Config.ARGB_8888).also { image ->
            val c=Canvas(image);c.drawColor(Color.rgb(27,43,51))
            for(i in 1..12){val x=960f*i/13;c.drawLine(x,0f,x-140,268f,tools.stroke(Color.rgb(52,72,79),2f))}
            for(i in 1..5){val y=268f*i/6;c.drawLine(0f,y,960f,y-40,tools.stroke(Color.rgb(52,72,79),2f))}
            path.reset();path.moveTo(430f,268f);path.lineTo(430f,160f);path.quadTo(430f,120f,550f,120f);path.lineTo(840f,80f)
            c.drawPath(path,tools.stroke(Color.rgb(50,151,246),9f,true))
        }
    }
    fun draw(canvas: Canvas,w: Float,h: Float,p: VisualProperties){
        val fit=minOf(w/source.width,h/source.height);val iw=source.width*fit;val ih=source.height*fit
        rect.set((w-iw)/2,(h-ih)/2,(w+iw)/2,(h+ih)/2)
        canvas.drawRect(0f,0f,w,h,tools.fill(Color.BLACK))
        val opacity=(p.number("videoOpacity",1f)*255).toInt()
        canvas.drawBitmap(source,null,rect,tools.fill(Color.WHITE).apply {alpha=opacity})
        if(p.bool("blurEnabled")&&p.number("blurRadius")>0){
            val radius=p.number("blurRadius").toInt().coerceAtLeast(1)
            val soft=variants.getOrPut(radius){ArtworkBlur.create(source,radius)}
            val paint=tools.fill(Color.WHITE).apply {alpha=opacity}
            if(p.string("blurStyle")=="uniform")canvas.drawBitmap(soft,null,rect,paint)
            else{
                val layer=canvas.saveLayer(0f,0f,w,h,null)
                canvas.drawBitmap(soft,null,rect,paint)
                val edge=minOf(p.number("edgeWidth"),iw/2,ih/2)
                paint.xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                val horizontal=LinearGradient(rect.left,0f,rect.right,0f,intArrayOf(Color.WHITE,Color.TRANSPARENT,Color.TRANSPARENT,Color.WHITE),floatArrayOf(0f,edge/iw,1-edge/iw,1f),Shader.TileMode.CLAMP)
                val vertical=LinearGradient(0f,rect.top,0f,rect.bottom,intArrayOf(Color.WHITE,Color.TRANSPARENT,Color.TRANSPARENT,Color.WHITE),floatArrayOf(0f,edge/ih,1-edge/ih,1f),Shader.TileMode.CLAMP)
                paint.shader=ComposeShader(horizontal,vertical,PorterDuff.Mode.SRC_OVER)
                canvas.drawRect(rect,paint)
                paint.xfermode=null;paint.shader=null;canvas.restoreToCount(layer)
            }
        }
        tools.label(canvas,"地图演示",w*.08f,h*.96f,minOf(16f,h*.06f),Color.GRAY,w*.3f,Paint.Align.LEFT)
    }
    fun clear(){variants.clear()}
}
