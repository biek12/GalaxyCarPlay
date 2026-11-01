package com.galaxy.autocluster.theme.render

import android.graphics.*
import com.galaxy.autocluster.theme.*

/** 背景区域独立组件；地图毛玻璃由AutoService同一纹理采样，封面模糊在换图IO预处理。 */
internal class MusicBackgroundPainter(private val tools: DrawTools,private val media: MediaPainter) {
    private val path=Path()
    private val rect=RectF()
    private data class FadeKey(val width: Float,val height: Float,val inset: Float,val fade: Float)
    private val fades=android.util.LruCache<FadeKey,Shader>(16)
    fun clear(){fades.evictAll()}
    fun draw(canvas: Canvas,item: SceneRenderNode,artwork: ArtworkFrame){
        val p=item.properties;val style=p.string("backgroundStyle")
        if(style=="none")return
        val w=item.node.rect.width;val h=item.node.rect.height
        val inset=minOf(p.number("backgroundInset"),w/2-1,h/2-1).coerceAtLeast(0f)
        rect.set(inset,inset,w-inset,h-inset)
        val radius=if(p.bool("backgroundRounded"))p.number("backgroundRadius")else 0f
        path.reset();path.addRoundRect(rect,radius,radius,Path.Direction.CW)
        val save=canvas.save();canvas.clipPath(path)
        val feather=p.bool("backgroundEdgeFade")
        val layer=if(feather)canvas.saveLayer(rect,null)else -1
        val opacity=p.number("backgroundOpacity")
        if(style=="artwork"){
            val image=if(p.bool("backgroundBlur"))artwork.blurred[p.number("backgroundBlurRadius").toInt()]?:artwork.current else artwork.current
            image?.let { media.drawImage(canvas,it,w,h,"cover",(opacity*255).toInt()) }
        }
        val paint=tools.fill(p.color("backgroundColor"));paint.alpha=(Color.alpha(p.color("backgroundColor"))*opacity).toInt()
        if(style=="gradient")paint.shader=LinearGradient(0f,0f,0f,h,p.color("backgroundColor"),p.color("backgroundEndColor"),Shader.TileMode.CLAMP)
        canvas.drawRect(rect,paint);paint.shader=null
        if(feather){
            val width=rect.width();val height=rect.height();val fade=minOf(p.number("backgroundFadeWidth"),width/2,height/2)
            val key=FadeKey(w,h,inset,fade)
            val mask=fades.get(key)?:ComposeShader(
                LinearGradient(rect.left,0f,rect.right,0f,intArrayOf(Color.TRANSPARENT,Color.WHITE,Color.WHITE,Color.TRANSPARENT),floatArrayOf(0f,fade/width,1-fade/width,1f),Shader.TileMode.CLAMP),
                LinearGradient(0f,rect.top,0f,rect.bottom,intArrayOf(Color.TRANSPARENT,Color.WHITE,Color.WHITE,Color.TRANSPARENT),floatArrayOf(0f,fade/height,1-fade/height,1f),Shader.TileMode.CLAMP),PorterDuff.Mode.DST_IN).also { fades.put(key,it) }
            val maskPaint=tools.fill(Color.WHITE);maskPaint.shader=mask;maskPaint.xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            canvas.drawRect(rect,maskPaint);maskPaint.shader=null;maskPaint.xfermode=null;canvas.restoreToCount(layer)
        }
        canvas.restoreToCount(save)
    }
}
