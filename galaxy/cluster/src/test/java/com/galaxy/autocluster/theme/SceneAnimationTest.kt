package com.galaxy.autocluster.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import com.galaxy.autocluster.theme.render.SceneRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SceneAnimationTest {
    private fun media(position: Long,at: Long,playing: Boolean=true,track: String="first")=SceneData(Bundle().apply {
        putBundle("media",Bundle().apply {putString("track",track);putBoolean("playing",playing);putLong("elapsed",position);putLong("receivedAt",at)
            putLong("duration",20000);putStringArrayList("lyrics",arrayListOf("第一行","第二行","第三行","第四行"));putLongArray("lyricTimes",longArrayOf(0,1000,2000,3000))})
    })
    @Test fun lyricBoundariesAnimateButSeekAndTrackReplacementDoNotReplayOldLines(){
        val node=SceneNode("lyrics","lyrics","歌词",SceneRect(0f,0f,600f,300f))
        val scene=ThemeScene(SceneDocument(listOf(node)),SceneStyles())
        ThemeResources(scene,RuntimeEnvironment.getApplication().cacheDir){error("无资源")}.use { resources->
            val compiled=CompiledScene(scene,true,resources);val renderer=SceneRenderer(resources)
            val bitmap=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
            val initial=renderer.render(canvas,compiled,media(900,5000),5000)
            assertFalse(initial.animation);assertEquals(5100L,initial.nextAt)
            assertTrue(renderer.render(canvas,compiled,media(900,5000),5110).animation)
            assertFalse(renderer.render(canvas,compiled,media(900,5000),5600).animation)
            assertFalse(renderer.render(canvas,compiled,media(50,5650),5650).animation)
            assertFalse(renderer.render(canvas,compiled,media(3050,5660),5660).animation)
            assertFalse(renderer.render(canvas,compiled,media(1050,5670,track="second"),5670).animation)
            val paused=media(1050,5680,false,track="second")
            val stopped=renderer.render(canvas,compiled,paused,7000)
            assertFalse(stopped.animation);assertNull(stopped.nextAt)
            val frozen=bitmap.copy(Bitmap.Config.ARGB_8888,false)
            renderer.render(canvas,compiled,paused,17000)
            assertTrue(frozen.sameAs(bitmap))
            frozen.recycle();bitmap.recycle();renderer.close()
        }
    }
    @Test fun zeroSpeedStaysValidAndExpiryIsScheduledWithoutConstantPolling(){
        val snapshot=Bundle().apply {putBundle("vehicle",Bundle().apply {putBundle("displaySpeed",Bundle().apply {putString("value","0");putString("status","VALID");putLong("receivedAt",5000)})})}
        val data=SceneData(snapshot)
        assertEquals(0f,data.number("vehicle.displaySpeed",5000)!!,0f)
        assertEquals(7001L,data.nextExpiry(setOf("vehicle.displaySpeed"),5000))
        assertNull(data.nextExpiry(setOf("vehicle.displaySpeed"),7001))
        assertNull(data.number("vehicle.displaySpeed",7001))
    }
}
