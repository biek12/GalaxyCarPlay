package com.ecarx.autoservice.projection

import android.app.Instrumentation
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.*
import android.view.Surface
import com.galaxy.auto.contract.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 独立测试APK：真实Android驱动的Canvas→OES→EGL→ImageReader检查。
 * 不启动AutoService/Root/HAB，不访问车辆、QNX或媒体；适用于开发Android设备/模拟器。
 */
class ProjectionSmokeInstrumentation : Instrumentation() {
    private data class Frame(val points: List<Int>)
    private fun reader()=ImageReader.newInstance(1920,532,PixelFormat.RGBA_8888,3,
        HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
    override fun onCreate(arguments: Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){
        val result=Bundle()
        val thread=HandlerThread("projection-smoke-read").apply { start() }
        val handler=Handler(thread.looper)
        var composition: GalaxyGlComposition?=null
        val output=reader();val preview=reader()
        val surfaces=ConcurrentHashMap<String,Surface>()
        val failure=AtomicReference<String?>()
        val observed=AtomicReference<Frame?>();val previewObserved=AtomicReference<Frame?>()
        fun attach(reader: ImageReader,latest: AtomicReference<Frame?>){
            reader.setOnImageAvailableListener({ source->
                try { source.acquireLatestImage()?.use { image->
                    val plane=image.planes.single();val buffer=plane.buffer
                    fun color(x: Int,y: Int): Int {val offset=buffer.position()+y*plane.rowStride+x*plane.pixelStride
                        return Color.rgb(buffer.get(offset).toInt()and 255,buffer.get(offset+1).toInt()and 255,buffer.get(offset+2).toInt()and 255)}
                    latest.set(Frame(listOf(color(20,20),color(400,190),color(400,310),color(1200,190),color(1200,310),color(1002,140),color(1400,248),color(1400,180))))
                } }catch(error: Exception){failure.set(error.javaClass.simpleName)}
            },handler)
        }
        fun await(label: String,condition: ()->Boolean){
            val end=SystemClock.elapsedRealtime()+6000
            while(!condition()){
                check(failure.get()==null){"$label:${failure.get()}"}
                check(SystemClock.elapsedRealtime()<end){"$label:timeout"}
                SystemClock.sleep(10)
            }
        }
        fun paint(surface: Surface,theme: Boolean){
            val canvas=surface.lockHardwareCanvas()
            try {
                canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
                val paint=android.graphics.Paint()
                if(theme){paint.color=Color.MAGENTA;canvas.drawRect(0f,0f,64f,64f,paint)}
                else {paint.color=Color.RED;canvas.drawRect(0f,0f,1920f,268f,paint);paint.color=Color.BLUE;canvas.drawRect(0f,268f,1920f,536f,paint)}
            }finally{surface.unlockCanvasAndPost(canvas)}
        }
        fun near(actual: Int,expected: Int)=kotlin.math.abs(Color.red(actual)-Color.red(expected))<12&&kotlin.math.abs(Color.green(actual)-Color.green(expected))<12&&kotlin.math.abs(Color.blue(actual)-Color.blue(expected))<12
        try {
            attach(output,observed);attach(preview,previewObserved)
            runOnMainSync {
                composition=GalaxyGlComposition(output.surface,{ kind,surface->if(surface!=null)surfaces[kind]=surface },{message->if(message.contains("failed"))failure.set(message)})
                composition!!.setPolicy(ProjectionPolicy("content-aware",30,30))
                composition!!.configure(AutoContract.THEME,SceneConfiguration(1,"smoke",MapViewport(200f,100f,800f,300f)))
                composition!!.configure(AutoContract.PREVIEW,SceneConfiguration(2,"smoke-draft",MapViewport(1000f,100f,800f,300f)))
                composition!!.setPreviewSurface(preview.surface);composition!!.setMapActive(true);composition!!.setOutputActive(true)
            }
            await("input-surfaces"){surfaces.size==3}
            paint(surfaces.getValue(AutoContract.MAP),false)
            paint(surfaces.getValue(AutoContract.surfaceKind(AutoContract.THEME,1)),true)
            paint(surfaces.getValue(AutoContract.surfaceKind(AutoContract.PREVIEW,2)),true)
            await("output-orientation-alpha"){observed.get()?.points?.let {near(it[0],Color.MAGENTA)&&near(it[1],Color.RED)&&near(it[2],Color.BLUE)}==true}
            await("independent-draft-map"){previewObserved.get()?.points?.let {near(it[0],Color.MAGENTA)&&near(it[3],Color.RED)&&near(it[4],Color.BLUE)}==true}
            composition!!.configure(AutoContract.THEME,SceneConfiguration(3,"smoke-next",MapViewport(1000f,100f,800f,300f)))
            await("new-theme-surface"){surfaces.containsKey(AutoContract.surfaceKind(AutoContract.THEME,3))}
            // 未提交新主题时，旧活动场景继续显示；新几何随第一张新主题纹理一起采用。
            paint(surfaces.getValue(AutoContract.MAP),false)
            await("old-theme-retained"){observed.get()?.points?.let {near(it[1],Color.RED)}==true}
            paint(surfaces.getValue(AutoContract.surfaceKind(AutoContract.THEME,3)),true)
            await("atomic-scene-geometry"){observed.get()?.points?.let {near(it[1],Color.BLACK)&&near(it[3],Color.RED)&&near(it[4],Color.BLUE)}==true}
            composition!!.setPreviewSurface(null)
            paint(surfaces.getValue(AutoContract.MAP),false)
            await("output-after-preview-close"){observed.get()?.points?.let {near(it[3],Color.RED)}==true}
            // API30驱动实测圆角裁切与模糊开关；采样位于完整适配地图的角落、红蓝交界和内部。
            composition!!.configure(AutoContract.THEME,SceneConfiguration(4,"smoke-effects",MapViewport(1000f,100f,800f,300f),MapAppearance(80f,32f,"uniform")))
            await("effects-theme-surface"){surfaces.containsKey(AutoContract.surfaceKind(AutoContract.THEME,4))}
            paint(surfaces.getValue(AutoContract.surfaceKind(AutoContract.THEME,4)),true)
            await("rounded-map-and-uniform-blur"){observed.get()?.points?.let {near(it[5],Color.BLACK)&&Color.red(it[6])>10&&Color.blue(it[6])>10&&near(it[7],Color.RED)}==true}
            composition!!.configure(AutoContract.THEME,SceneConfiguration(5,"smoke-edge-effects",MapViewport(1000f,100f,800f,300f),MapAppearance(80f,32f,"edges",40f)))
            await("edge-effects-theme-surface"){surfaces.containsKey(AutoContract.surfaceKind(AutoContract.THEME,5))}
            paint(surfaces.getValue(AutoContract.surfaceKind(AutoContract.THEME,5)),true)
            await("edge-blur-keeps-map-center-sharp"){observed.get()?.points?.let {near(it[5],Color.BLACK)&&near(it[6],Color.RED)}==true}
            result.putString("checks","orientation,alpha,full-map-fit,independent-preview,atomic-theme-swap,preview-close,rounded-map,uniform-blur,edge-blur-center")
            result.putString("scope","real-Android-GPU-only; no HAB/QNX/vehicle")
            result.putString("status","PASS")
        }catch(error: Exception){result.putString("status","FAIL");result.putString("stage",error.message?.take(160)?:error.javaClass.simpleName)}
        finally {
            composition?.close()
            val drained=CountDownLatch(1)
            handler.post { output.setOnImageAvailableListener(null,null);preview.setOnImageAvailableListener(null,null);output.close();preview.close();drained.countDown() }
            drained.await(2,TimeUnit.SECONDS);thread.quitSafely()
        }
        finish(if(result.getString("status")=="PASS")0 else -1,result)
    }
}
