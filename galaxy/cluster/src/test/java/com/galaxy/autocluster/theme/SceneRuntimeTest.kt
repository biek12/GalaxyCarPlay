package com.galaxy.autocluster.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import com.galaxy.autocluster.theme.render.ArtworkFrame
import com.galaxy.autocluster.theme.render.SceneMotion
import com.galaxy.autocluster.theme.render.SceneRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SceneRuntimeTest {
    private fun snapshot(now: Long = 5000) = Bundle().apply {
        putBundle("vehicle", Bundle().apply {
            for ((key,value) in mapOf("displaySpeed" to "68","rpm" to "2300","soc" to "78","fuel" to "62","evRange" to "397","gear" to "D","accelerator" to "18","brake" to "0","steeringAngle" to "12","tire.frontLeft" to "238","tire.frontRight" to "240","tire.rearLeft" to "239","tire.rearRight" to "241"))
                putBundle(key,Bundle().apply { putString("value",value);putString("status","VALID");putLong("receivedAt",now) })
        })
        putBundle("media",Bundle().apply {
            putString("track","demo-local-only");putString("title","向着远方");putString("artist","组件样式演示")
            putBoolean("playing",true);putLong("elapsed",1750);putLong("receivedAt",now);putLong("duration",236000)
            putStringArrayList("lyrics",arrayListOf("沿途的风景","在此刻慢慢展开","下一站，新的期待","和你一起向前"));putLongArray("lyricTimes",longArrayOf(0,1000,2000,3000))
        })
    }
    @Test fun builtinPackagesRoundTripAndKeepRealMapHoleInBothModes() {
        val folder=File("build/screenshots/themes").apply { mkdirs() }
        for(theme in BuiltinThemes.packages()){
            val scene=SceneXml.read(theme)
            ThemeResources(theme,scene,RuntimeEnvironment.getApplication().cacheDir).use { resources ->
                for(night in listOf(false,true)){
                    val compiled=CompiledScene(scene,night,resources)
                    val bitmap=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888)
                    val renderer=SceneRenderer(resources)
                    val demo=com.galaxy.autocluster.ui.DemoContent.data(5000,109)
                    renderer.render(Canvas(bitmap),compiled,demo,5000,artwork=ArtworkFrame(com.galaxy.autocluster.ui.DemoContent.artwork,null,0,mapOf(23 to ArtworkBlur.create(com.galaxy.autocluster.ui.DemoContent.artwork,23))),wallTime=1_700_000_000_000)
                    val map=compiled.map!!
                    assertEquals("${theme.metadata.id}: 地图不能被主题背景盖住",0,Color.alpha(bitmap.getPixel((map.x+map.width/2).toInt(),(map.y+map.height/2).toInt())))
                    assertTrue(Color.alpha(bitmap.getPixel(4,4))>0)
                    renderer.render(Canvas(bitmap),compiled,demo,5000,artwork=ArtworkFrame(com.galaxy.autocluster.ui.DemoContent.artwork,null,0,mapOf(23 to ArtworkBlur.create(com.galaxy.autocluster.ui.DemoContent.artwork,23))),wallTime=1_700_000_000_000,demoMap=true)
                    File(folder,"${theme.metadata.id}-${if(night)"night" else "day"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    renderer.close();bitmap.recycle()
                }
            }
        }
    }
    @Test fun allSeventeenComponentsUseSameRendererAndUnknownValuesRemainUnavailable() {
        val folder=File("build/screenshots/components").apply { mkdirs() }
        for(spec in ComponentProperties.all){
            val r=SceneRect(30f,20f,minOf(900f,spec.width),minOf(490f,spec.height))
            val node=if(spec.type=="pages")CompositeComponents.pages("sample",r)else SceneNode("sample",spec.type,spec.title,r)
            val scene=ThemeScene(SceneDocument(listOf(node)),SceneStyles())
            val theme=SceneXml.write(ThemeMetadata("sample-${spec.type}",spec.title,1),scene)
            ThemeResources(theme,scene,RuntimeEnvironment.getApplication().cacheDir).use { resources ->
                val bitmap=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888)
                val renderer=SceneRenderer(resources)
                renderer.render(Canvas(bitmap),CompiledScene(scene,true,resources),SceneData(),5000)
                renderer.render(Canvas(bitmap),CompiledScene(scene,true,resources),SceneData(snapshot()),5000,demoMap=true)
                File(folder,"${spec.type}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                renderer.close();bitmap.recycle()
            }
        }
        assertNull(SceneData().number("vehicle.displaySpeed",5000))
        assertNull(SceneData(snapshot()).number("vehicle.displaySpeed",7001))
        assertEquals(0f,SceneData(snapshot()).number("vehicle.brake",5000)!!,0f)
    }
    @Test fun perModeOverridePreservesTransparentZeroAndCannotChangeBindings() {
        val node=SceneNode("speed","value","速度",SceneRect(0f,0f,400f,250f),day=mapOf("foreground" to "#00000000"),night=mapOf("foreground" to "#FF0000"))
        val scene=ThemeScene(SceneDocument(listOf(node)),SceneStyles())
        val theme=SceneXml.write(ThemeMetadata("styles","样式",1),scene)
        ThemeResources(theme,scene,RuntimeEnvironment.getApplication().cacheDir).use { resources ->
            assertEquals(0,CompiledScene(scene,false,resources).nodes[0].properties.color("foreground"))
            assertEquals(Color.RED,CompiledScene(scene,true,resources).nodes[0].properties.color("foreground"))
        }
        try { SceneXml.write(ThemeMetadata("invalid","无效",1),scene.copy(document=scene.document.copy(nodes=listOf(node.copy(day=mapOf("source" to "vehicle.rpm"))))));fail() } catch(_:IllegalArgumentException){}
    }
    @Test fun motionReplacesTargetAndIntroNeverResetsMovingSpeedToZero() {
        val motion=SceneMotion();motion.begin(1000)
        assertEquals(0f,motion.value("speed",0f,1000,"linear")!!,0f)
        motion.value("speed",100f,1000,"linear");motion.begin(1500)
        assertEquals(50f,motion.value("speed",100f,1000,"linear")!!,.01f)
        assertEquals(50f,motion.value("speed",60f,1000,"linear")!!,.01f)
        motion.begin(2000);assertEquals(55f,motion.value("speed",60f,1000,"linear")!!,.01f)
        assertNull(motion.intro("startup",true,1200,true));motion.begin(2100)
        assertNull(motion.intro("startup",true,1200,false))
    }
    @Test fun nestedMapGeometryAndGroupResizeRemainConsistent() {
        val child=SceneNode("map","map","地图",SceneRect(10f,20f,400f,160f))
        val group=SceneNode("group","card","组合",SceneRect(100f,100f,500f,300f),children=listOf(child))
        assertEquals(SceneRect(110f,120f,400f,160f),SceneTree.worldRects(listOf(group))["map"])
        val resized=SceneTree.resize(group,SceneRect(100f,100f,750f,450f))
        assertEquals(SceneRect(115f,130f,600f,240f),SceneTree.worldRects(listOf(resized))["map"])
    }
}
