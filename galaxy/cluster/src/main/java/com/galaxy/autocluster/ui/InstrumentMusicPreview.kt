package com.galaxy.autocluster.ui

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import com.galaxy.autocluster.theme.SceneData
import com.galaxy.auto.contract.LyricScroll

/** 320×316仪表卡片的真实数据预览；不是播放控制器，不发送媒体指令。 */
internal class InstrumentMusicPreview(context: Context): View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var snapshot=Bundle()
    private var data=SceneData()
    private var image: Bitmap?=null
    private var imageVersion=Long.MIN_VALUE
    private var generation=0L
    private val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
    private var lineKey=""
    private var lineStart=0L
    private var previousPosition=0L
    fun snapshot(value: Bundle){
        snapshot=Bundle(value);data=SceneData(snapshot)
        val version=value.getLong("artworkVersion")
        if(version!=imageVersion){
            imageVersion=version;val ticket=++generation;image?.recycle();image=null
            val source=value.getString("artworkUri")
            if(source!=null)worker.execute{
                val decoded=runCatching{context.contentResolver.openInputStream(android.net.Uri.parse(source))?.use{BitmapFactory.decodeStream(it)}}.getOrNull()
                post{if(generation==ticket){image=decoded;invalidate()}else decoded?.recycle()}
            }
        }
        invalidate()
    }
    override fun onDraw(canvas: Canvas){
        super.onDraw(canvas)
        val scale=minOf(width/320f,height/316f);if(scale<=0)return
        val save=canvas.save();canvas.translate((width-320*scale)/2,(height-316*scale)/2);canvas.scale(scale,scale)
        val fg=context.getColor(com.galaxy.auto.ui.R.color.galaxy_ui_text)
        val muted=context.getColor(com.galaxy.auto.ui.R.color.galaxy_ui_muted)
        val control=context.getColor(com.galaxy.auto.ui.R.color.galaxy_ui_control)
        val accent=context.getColor(com.galaxy.auto.ui.R.color.galaxy_ui_accent)
        fun label(text: String,x: Float,y: Float,size: Float,color: Int,maxWidth: Float){
            val state=canvas.save();canvas.clipRect(x,0f,x+maxWidth,316f)
            paint.color=color;paint.textSize=size;paint.typeface=Typeface.DEFAULT;canvas.drawText(text,x,y,paint);canvas.restoreToCount(state)
        }
        paint.color=control;canvas.drawRoundRect(0f,0f,80f,80f,4f,4f,paint)
        image?.takeUnless{it.isRecycled}?.let{canvas.drawBitmap(it,null,RectF(0f,0f,80f,80f),paint)}
            ?:label("暂无封面",8f,45f,14f,muted,72f)
        val media=data.media;val now=SystemClock.elapsedRealtime();val position=media.positionAt(now)
        label(media.title.ifBlank{"暂无播放内容"},100f,28f,26f,fg,220f)
        label(media.artist,100f,65f,18f,muted,220f)
        val index=if(media.times==null)0 else media.lyricIndex(now)
        val current=media.lyrics.getOrNull(index).orEmpty().ifBlank{if(media.title.isBlank())""else"暂无歌词"}
        val key=media.track+"\u0000"+index+"\u0000"+current
        if(key!=lineKey||position<previousPosition-200||position-previousPosition>1500){lineKey=key;lineStart=position.coerceAtLeast(0)}
        previousPosition=position
        label(media.lyrics.getOrNull(index-1).orEmpty(),0f,133f,18f,muted,320f)
        paint.textSize=32f
        val distance=(paint.measureText(current)-320).coerceAtLeast(0f)
        val offset=LyricScroll.offset(distance,(position-lineStart).coerceAtLeast(0))
        val lineSave=canvas.save();canvas.clipRect(0f,148f,320f,192f)
        label(current,-offset,182f,32f,fg,320f+offset);canvas.restoreToCount(lineSave)
        label(media.lyrics.getOrNull(index+1).orEmpty(),0f,215f,20f,muted,320f)
        label(media.lyrics.getOrNull(index+2).orEmpty(),0f,250f,18f,muted,320f)
        paint.color=control;canvas.drawRect(0f,282f,220f,285f,paint)
        if(media.duration>0&&position>=0){paint.color=accent;canvas.drawRect(0f,282f,220f*(position.toFloat()/media.duration).coerceIn(0f,1f),285f,paint)}
        fun time(ms: Long)=if(ms<0)"--:--"else"%02d:%02d".format(ms/60000,ms/1000%60)
        label(time(position),0f,311f,15f,muted,100f);label(time(media.duration),160f,311f,15f,muted,60f)
        canvas.restoreToCount(save)
        if(media.playing){if(distance>offset)postInvalidateOnAnimation()else postInvalidateDelayed(1000)}
    }
    override fun onDetachedFromWindow(){++generation;worker.shutdownNow();image?.recycle();image=null;super.onDetachedFromWindow()}
}
