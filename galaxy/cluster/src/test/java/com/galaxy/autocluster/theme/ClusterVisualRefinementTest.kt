package com.galaxy.autocluster.theme

import android.graphics.*
import android.os.Bundle
import com.galaxy.autocluster.theme.render.*
import com.galaxy.autocluster.ui.DemoContent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterVisualRefinementTest {
    private fun data(actual: Float,target: Float=100f,power: Float?=null,unit: String="kW"): SceneData = SceneData(Bundle().apply {
        putBundle("vehicle",Bundle().apply {
            val values=mutableMapOf("displaySpeed" to actual.toString(),"cruiseState" to "active","cruiseSetSpeed" to target.toString(),"regenLevel" to "auto")
            if(power!=null)values["tractionPowerKw"]=power.toString()
            for((key,value)in values)putBundle(key,Bundle().apply {putString("value",value);putString("unit",unit);putString("status","VALID");putLong("receivedAt",5000)})
        })
    })
    @Test fun cruiseArcEndsAtNeedleForBothSignsAndCustomScale(){
        fun check(actual: Float,target: Float,inside: Float,outside: Float,start: Float=135f,sweep: Float=270f,min: Float=0f,max: Float=260f){
            val node=SceneNode("g","gauge","GT",SceneRect(0f,0f,420f,420f),mapOf("intro" to "false","secondarySource" to "","startAngle" to "$start","sweepAngle" to "$sweep","minimum" to "$min","maximum" to "$max"))
            val scene=ThemeScene(SceneDocument(listOf(node)),SceneStyles())
            ThemeResources(scene,RuntimeEnvironment.getApplication().cacheDir){error("no asset")}.use { resources->
                val bitmap=Bitmap.createBitmap(420,420,Bitmap.Config.ARGB_8888)
                val renderer=SceneRenderer(resources)
                renderer.render(Canvas(bitmap),CompiledScene(scene,true,resources),data(actual,target),5000,viewBox=node.rect)
                fun green(speed: Float): Boolean {
                    val angle=Math.toRadians((start+sweep*(speed-min)/(max-min)).toDouble())
                    val x=(210+cos(angle)*207).roundToInt();val y=(210+sin(angle)*207).roundToInt()
                    return (-1..1).any { dx->(-1..1).any { dy->val color=bitmap.getPixel(x+dx,y+dy);Color.green(color)>Color.red(color)+65&&Color.green(color)>150 } }
                }
                assertTrue("弧内应覆盖速度$inside",green(inside));assertFalse("不能越过实际指针${actual}到$outside",green(outside))
                renderer.close();bitmap.recycle()
            }
        }
        check(109f,100f,104.5f,114f)
        check(80f,100f,90f,105f)
        check(109f,100f,104.5f,120f,180f,180f,40f,140f)
    }
    @Test fun musicHasOnePastCurrentAndTwoFutureRowsAndShortProgress(){
        val theme=BuiltinThemes.packages().first {it.metadata.id=="builtin-reference"}
        val scene=SceneXml.read(theme)
        val node=SceneTree.flatten(scene.document.nodes).first {it.type=="lyrics"}
        assertEquals(324f,node.rect.width,0f)
        val world=SceneTree.worldRects(scene.document.nodes).getValue(node.id)
        assertEquals(1510f,world.x+world.width/2,0f)
        val isolated=ThemeScene(SceneDocument(listOf(node.copy(rect=node.rect.copy(x=0f,y=0f)))),scene.styles)
        ThemeResources(isolated,RuntimeEnvironment.getApplication().cacheDir){error("no asset")}.use { resources->
            val bitmap=Bitmap.createBitmap(324,236,Bitmap.Config.ARGB_8888);val renderer=SceneRenderer(resources)
            renderer.render(Canvas(bitmap),CompiledScene(isolated,true,resources),DemoContent.data(5000),5000,viewBox=SceneRect(0f,0f,324f,236f))
            val brightness=(0..3).map { line->
                val y=46+line*48
                (y-14..y+14).maxOf { yy->(0 until 324).maxOf { x->Color.red(bitmap.getPixel(x,yy)) } }
            }
            assertTrue(brightness.all {it>90})
            assertTrue(brightness[1]>brightness[0]+30);assertTrue(brightness[1]>brightness[2]+30)
            assertTrue(brightness[2]>brightness[3]+20)
            assertEquals("4",node.properties["lines"]);assertEquals("second",node.properties["activeRow"])
            val progress=SceneTree.flatten(scene.document.nodes).first {it.type=="progress"}
            assertEquals(220f,progress.rect.width,0f)
            val folder=File("build/screenshots/refinement").apply {mkdirs()}
            File(folder,"lyrics-four-lines.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            renderer.close();bitmap.recycle()
        }
    }
    @Test fun lyricsAfterCenteredTrackAreIdenticalToIsolatedLyrics(){
        val template=SceneXml.read(BuiltinThemes.packages().first {it.metadata.id=="builtin-reference"})
        val lyric=SceneTree.flatten(template.document.nodes).first {it.type=="lyrics"}.copy(rect=SceneRect(0f,120f,324f,244f))
        val track=SceneNode("title","track","标题",SceneRect(0f,0f,324f,90f),mapOf("align" to "center","titleSize" to "25","artistSize" to "18","gap" to "5"))
        fun render(nodes: List<SceneNode>): Bitmap {
            val scene=ThemeScene(SceneDocument(nodes),template.styles)
            ThemeResources(scene,RuntimeEnvironment.getApplication().cacheDir){error("no asset")}.use { resources->
                val bitmap=Bitmap.createBitmap(324,364,Bitmap.Config.ARGB_8888);val renderer=SceneRenderer(resources)
                renderer.render(Canvas(bitmap),CompiledScene(scene,true,resources),DemoContent.data(5000),5000,viewBox=SceneRect(0f,0f,324f,364f))
                renderer.close();return bitmap
            }
        }
        val a=render(listOf(lyric));val b=render(listOf(track,lyric))
        val ca=Bitmap.createBitmap(a,0,120,324,244);val cb=Bitmap.createBitmap(b,0,120,324,244)
        assertTrue("前一个居中标题不能改变歌词布局或裁掉开头",ca.sameAs(cb))
        a.recycle();b.recycle();ca.recycle();cb.recycle()
    }
    @Test fun headerGearAndFourModesAreIndependentEditableNodesAndExpire(){
        val scene=SceneXml.read(BuiltinThemes.packages().first {it.metadata.id=="builtin-reference"})
        val gear=SceneTree.find(scene.document.nodes,"gear")!!;val mode=SceneTree.find(scene.document.nodes,"drive-mode")!!
        assertEquals("vehicle.gear",gear.properties["source"]);assertEquals("vehicle.driveMode",mode.properties["source"])
        assertTrue(gear.rect.y+gear.rect.height<=64f&&mode.rect.y+mode.rect.height<=64f)
        val editor=EditorSession(BuiltinThemes.packages().first {it.metadata.id=="builtin-reference"},true)
        editor.choose("drive-mode");editor.moveSelection(-20f,0f);assertEquals(mode.rect.x-20f,editor.node("drive-mode")!!.rect.x,0f)
        for((g,m)in listOf("P" to "纯电","D" to "增程","R" to "性能","N" to "智能")){
            val data=SceneData(Bundle().apply {putBundle("vehicle",Bundle().apply {
                for((key,value)in mapOf("gear" to g,"driveMode" to m))putBundle(key,Bundle().apply {putString("value",value);putString("status","VALID");putLong("receivedAt",5000)})
            })})
            assertEquals(g,data.text("vehicle.gear",5000));assertEquals(m,data.text("vehicle.driveMode",5000))
            assertNull(data.text("vehicle.driveMode",7001))
        }
    }
    @Test fun signedPowerSupportsSixtyBothDirectionsReversedConventionAndRejectsRawUnits(){
        val node=SceneNode("power","regen","功率",SceneRect(0f,0f,360f,150f),mapOf("powerInput" to "signed","powerDisplay" to "bipolar"))
        val scene=ThemeScene(SceneDocument(listOf(node)),SceneStyles())
        ThemeResources(scene,RuntimeEnvironment.getApplication().cacheDir){error("no asset")}.use { resources->
            val p=CompiledScene(scene,true,resources).nodes.single().properties
            assertEquals(PowerFlow(60f,0f),PowerFlow.read(p,data(100f,power=60f),5000))
            assertEquals(PowerFlow(0f,60f),PowerFlow.read(p,data(100f,power=-60f),5000))
            assertEquals(PowerFlow(280f,0f),PowerFlow.read(p,data(100f,power=280f),5000)) // 读数不按仪表量程裁切。
            assertEquals(PowerFlow(null,null),PowerFlow.read(p,data(100f,power=-60f,unit="raw"),5000))
            assertEquals(PowerFlow(null,null),PowerFlow.read(p,data(100f,power=-60f),7001))
            val reverse=scene.copy(document=scene.document.copy(nodes=listOf(node.copy(properties=node.properties+("powerPositive" to "regen")))))
            assertEquals(PowerFlow(0f,60f),PowerFlow.read(CompiledScene(reverse,true,resources).nodes.single().properties,data(100f,power=60f),5000))
            val folder=File("build/screenshots/refinement").apply {mkdirs()}
            for(power in listOf(60f,-60f)){
                val bitmap=Bitmap.createBitmap(360,150,Bitmap.Config.ARGB_8888);val renderer=SceneRenderer(resources)
                renderer.render(Canvas(bitmap),CompiledScene(scene,true,resources),data(100f,power=power),5000,viewBox=node.rect)
                File(folder,if(power>0)"drive-plus60.png"else"regen-minus60.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                renderer.close();bitmap.recycle()
            }
        }
    }
}
