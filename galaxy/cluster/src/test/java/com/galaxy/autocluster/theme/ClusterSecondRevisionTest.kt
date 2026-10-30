package com.galaxy.autocluster.theme

import android.graphics.*
import android.os.Bundle
import com.galaxy.autocluster.theme.render.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterSecondRevisionTest {
    private fun data(state: String,now: Long=5000)=SceneData(Bundle().apply { putBundle("vehicle",Bundle().apply {
        for((key,value)in mapOf("cruiseState" to state,"cruiseSetSpeed" to "100","displaySpeed" to "109","regenLevel" to "auto","regenPowerKw" to "28"))
            putBundle(key,Bundle().apply {putString("value",value);putString("status","VALID");putLong("receivedAt",5000)})
    })})
    @Test fun cruiseOffStandbyActiveAndExpiredRenderDistinctlyAtTrueTargetAngle(){
        val node=SceneNode("speed","gauge","速度",SceneRect(0f,0f,490f,490f),mapOf("intro" to "false","valueY" to "114","regenVisible" to "true","regenY" to "171","secondarySource" to ""))
        val scene=ThemeScene(SceneDocument(listOf(node)),SceneStyles())
        val folder=File("build/screenshots/cruise").apply {mkdirs()}
        ThemeResources(scene,RuntimeEnvironment.getApplication().cacheDir){error("没有资源")}.use { resources->
            val compiled=CompiledScene(scene,true,resources)
            val images=mutableMapOf<String,Bitmap>()
            for(state in listOf("off","standby","active","unknown","expired")){
                val bitmap=Bitmap.createBitmap(490,490,Bitmap.Config.ARGB_8888)
                val renderer=SceneRenderer(resources)
                renderer.render(Canvas(bitmap),compiled,data(if(state=="expired")"active"else state),if(state=="expired")7001 else 5000,viewBox=node.rect)
                File(folder,"$state.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                images[state]=bitmap;renderer.close()
            }
            assertFalse(images.getValue("off").sameAs(images.getValue("standby")))
            assertFalse(images.getValue("standby").sameAs(images.getValue("active")))
            assertTrue(images.getValue("off").sameAs(images.getValue("unknown")))
            val angle=Math.toRadians(135.0+270.0*100/260)
            val x=(245+kotlin.math.cos(angle)*200*490/420*.89).toInt();val y=(245+kotlin.math.sin(angle)*200*490/420*.89).toInt()
            val active=images.getValue("active").getPixel(x,y)
            val standby=images.getValue("standby").getPixel(x,y)
            assertTrue(Color.green(active)>Color.red(active)+50)
            assertTrue(Color.red(standby)>200&&Color.green(standby)>200)
            images.values.forEach {it.recycle()}
        }
    }
    @Test fun pagesUseMonotonicEventSequenceAndDoNotReplayOnReconnect(){
        val pages=PageMotion();val order=listOf("music","tires","fuel","electric")
        fun read(seq: Long,now: Long,epoch: String="one")=pages.sample("pane",order,"music",epoch,seq,5100,now,260)
        assertEquals("music",read(8,5000).current)
        val switched=read(9,5100);assertEquals("tires",switched.current);assertEquals("music",switched.previous);assertEquals(0f,switched.fraction,0f)
        assertEquals("tires",read(9,5200).current)
        assertEquals(1f,read(9,5500).fraction,0f)
        assertEquals("electric",read(11,5500).current)
        assertEquals("electric",read(50,5500,"two").current)
        assertEquals("music",pages.sample("pane",order,"music","two",51,5600,5600,0).current)
        assertEquals("fuel",pages.sample("pane",order,"fuel","two",51,5600,5700,0).current)
    }
    @Test fun blurIsCachedWorkProductAndPreservesFlatColorAlpha(){
        val input=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply {eraseColor(0x804080C0.toInt())}
        val soft=ArtworkBlur.create(input,12)
        assertEquals(input.getPixel(16,16),soft.getPixel(16,16))
        input.eraseColor(Color.BLACK)
        assertNotEquals(input.getPixel(16,16),soft.getPixel(16,16))
        input.recycle();soft.recycle()
    }
    @Test fun invalidPageSelectionAndExcessiveMapBackdropsCannotBeSilentlyAccepted(){
        val pages=CompositeComponents.pages("pane",SceneRect(0f,0f,600f,440f))
        val invalid=pages.copy(properties=pages.properties + ("initialPage" to "electric"), children=pages.children.filter { it.properties["pageId"]!="electric" })
        val invalidOrder=invalid.copy(properties=invalid.properties+("pageOrder" to "music,tires,fuel"))
        try { SceneValidator.validate(ThemeScene(SceneDocument(listOf(invalidOrder)),SceneStyles()),emptySet());fail("初始页必须在页序中") } catch(expected: IllegalArgumentException){assertEquals("scene_pages_initial_invalid",expected.message)}
        val regions=(0..8).map { SceneNode("bg-$it","music-background","背景",SceneRect(0f,0f,100f,100f),mapOf("backgroundStyle" to "map")) }
        try { SceneValidator.validate(ThemeScene(SceneDocument(regions),SceneStyles()),emptySet());fail("不能无声裁掉第九个背景") } catch(expected: IllegalArgumentException){assertEquals("scene_backdrop_budget_exceeded",expected.message)}
        SceneValidator.validate(ThemeScene(SceneDocument(regions.take(8)),SceneStyles()),emptySet())
    }
    @Test fun editableReferenceThemeRoundTripsWithNoCallOrDateAndPendingOemComponents(){
        val original=BuiltinThemes.packages().first {it.metadata.id=="builtin-reference"}
        val restored=SceneXml.read(ThemePackageCodec.read(ThemePackageCodec.bytes(original).inputStream()))
        val nodes=SceneTree.flatten(restored.document.nodes)
        assertTrue(nodes.none {it.type=="call"})
        assertTrue(nodes.filter {it.type=="clock"}.all {it.properties["dateFormat"]=="none"})
        assertTrue(nodes.filter {it.type.endsWith("-range")}.all {!it.visible})
        assertEquals(4,nodes.first {it.type=="pages"}.children.size)
        assertTrue(nodes.any {it.type=="lyrics"&&it.properties["lines"]=="4"})
        val editor=EditorSession(original,true);editor.choose("speed");editor.moveSelection(10f,0f)
        assertEquals(45f,editor.node("speed")!!.rect.x,0f)
    }
}
