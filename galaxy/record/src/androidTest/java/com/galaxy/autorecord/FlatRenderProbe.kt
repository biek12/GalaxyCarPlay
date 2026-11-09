package com.galaxy.autorecord

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.*
import com.galaxy.autorecord.preview.*
import java.util.concurrent.*

/** 隔离模拟器上的真实EGL/OES采样，不使用模拟的GLES调用或车辆图像。 */
internal object FlatRenderProbe {
    fun run(report:Bundle){
        val thread=HandlerThread("flat-render-probe").apply{start()}
        val handler=Handler(thread.looper)
        val failures=LinkedBlockingQueue<Throwable>()
        val renderer=PreviewDistributor(handler){failures.offer(it)}
        val reader=ImageReader.newInstance(320,200,PixelFormat.RGBA_8888,3)
        val frames=LinkedBlockingQueue<Int>()
        var surface:android.view.Surface?=null
        fun <T> gpu(action:()->T):T=FutureTask<T>{action()}.let{handler.post(it);it.get(10,TimeUnit.SECONDS)}
        fun pixels():IntArray{
            check(frames.poll(10,TimeUnit.SECONDS)!=null){"render_timeout:${failures.peek()}"}
            val deadline=SystemClock.elapsedRealtime()+2000
            var image:android.media.Image?=null
            while(image==null&&SystemClock.elapsedRealtime()<deadline){image=reader.acquireLatestImage();if(image==null)Thread.sleep(5)}
            return checkNotNull(image){"render_output_missing"}.use{img->
                val plane=img.planes[0];val buffer=plane.buffer
                val horizontal=intArrayOf(32,160,288).map{x->
                    val offset=100*plane.rowStride+x*plane.pixelStride
                    buffer.get(offset).toInt() and 255
                }
                (horizontal+listOf(10,190).map{y->buffer.get(y*plane.rowStride+160*plane.pixelStride+1).toInt() and 255}).toIntArray()
            }
        }
        try{
            // 只用于本测试的可解释合成标定；与车机资产隔离。
            val profile=LensProfile("test",320,200,110f,110f,160f,100f,floatArrayOf(0f,0f,0f,0f),"synthetic-EGL-test")
            surface=gpu{renderer.input("test",320,200,profile).also{renderer.bind("test","page",reader.surface,false){frames.offer(1)}}}
            val input=checkNotNull(surface)
            val canvas=input.lockCanvas(null)
            try{
                val paint=Paint()
                for(x in 0 until 320){paint.color=Color.rgb(x*255/319,0,0);canvas.drawRect(x.toFloat(),0f,x+1f,200f,paint)}
                paint.color=Color.rgb(0,40,0);canvas.drawRect(155f,0f,165f,20f,paint)
                paint.color=Color.rgb(0,210,0);canvas.drawRect(155f,180f,165f,200f,paint)
            }finally{input.unlockCanvasAndPost(canvas)}
            val raw=pixels()
            check(raw[3]<raw[4]-80){"raw_vertical_inverted:${raw.toList()}"}
            gpu{renderer.flat(true)}
            val flat=pixels()
            check(kotlin.math.abs(raw[1]-flat[1])<=3){"center_changed"}
            check(flat[0]-raw[0]>=1&&raw[2]-flat[2]>=1){"flat_mapping_not_applied:${raw.toList()}:${flat.toList()}"}
            // 暂停画面时切回原图必须立即重绘，不依赖下一帧。
            gpu{renderer.flat(false)}
            val restored=pixels()
            check(raw.indices.all{kotlin.math.abs(raw[it]-restored[it])<=2}){"raw_restore_failed"}
            check(failures.isEmpty()){"gpu_failure:${failures.peek()}"}
            report.putString("flatRender","PASS: real EGL/OES raw -> full-frame edge-preserving -> raw, paused redraw, center/edge numeric checks; synthetic input")
        }finally{
            gpu{renderer.release()};reader.close();thread.quitSafely();thread.join(2000)
        }
    }
}
