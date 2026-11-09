package com.galaxy.auto.contract

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE)
class ProjectionConfigurationTest {
    @Test fun mapRequestFollowsComponentDimensionsAndPositionDoesNotChangeRequest(){
        val viewport=MapViewport(475f,64f,1390f,448f)
        val size=MapStreamSize.forViewport(viewport)
        assertEquals(MapStreamSize(1392,448),size)
        assertEquals(size,MapStreamSize.forViewport(viewport.copy(x=10f,y=10f)))
        val resized=MapStreamSize.forViewport(MapViewport(0f,0f,800f,400f))
        assertEquals(MapStreamSize(800,400),resized)
        val fit=MapViewport(0f,0f,800f,400f).fitted(resized.width,resized.height)
        assertEquals(800f,fit.width,.01f);assertEquals(400f,fit.height,.01f)
        assertEquals(size,MapStreamSize.from(size.bundle()))
    }
    @Test fun fractionalThirtyFrameScheduleDoesNotAccumulateThirtyThreeMillisecondDrift(){
        val clock=FramePacer(30)
        clock.started(1)
        repeat(29){clock.started(clock.nextAtNanos)}
        assertEquals(1_000_000_001L,clock.nextAtNanos)
    }
    @Test fun lateWorkSkipsExpiredSlotsAndHotRateUpdateDoesNotNeedNewSession(){
        val clock=FramePacer(30)
        clock.started(1_000_000_000)
        clock.started(1_200_000_000)
        assertTrue(clock.nextAtNanos>1_200_000_000)
        assertEquals(34L,clock.delayMillis(1_200_000_000))
        clock.setRate(20,1_200_000_000);clock.started(1_200_000_000)
        assertEquals(1_250_000_000L,clock.nextAtNanos)
    }
    @Test fun viewportKeepsEntireNativeMapAndConfigurationRoundTrips(){
        val viewport=MapViewport(500f,30f,900f,400f)
        val fitted=viewport.fitted()
        assertEquals(900f,fitted.width,.01f)
        assertEquals(900f*536/1920,fitted.height,.01f)
        assertTrue(fitted.y>30)
        val scene=SceneConfiguration(35,"custom-theme",viewport)
        assertEquals(scene,SceneConfiguration.from(scene.bundle()))
        val policy=ProjectionPolicy("content-aware",30,24)
        assertEquals(policy,ProjectionPolicy.from(policy.bundle()))
    }
    @Test fun mapEffectsAndMusicBackdropRoundTripWithoutChangingViewport(){
        val effects=MapAppearance(24f,12f,"edges",120f,.8f)
        val regions=listOf(MapBackdrop(MapViewport(1300f,80f,520f,400f),22f,15f))
        val scene=SceneConfiguration(40,"composed",MapViewport(475f,64f,1390f,448f),effects,regions)
        assertEquals(scene,SceneConfiguration.from(scene.bundle()))
        for(action in listOf<()->Any>({MapAppearance(blurRadius=Float.NaN)},{MapAppearance(blurRadius=33f)},{MapBackdrop(MapViewport(0f,0f,10f,10f),radius=-1f)})){
            try{action();fail()}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun invalidRatesAndOutOfBoundsMapAreRejectedAtIpcBoundary(){
        for(action in listOf<()->Any>({ProjectionPolicy(maximumFps=0)},{ProjectionPolicy(maximumFps=15,animationFps=30)},{MapViewport(Float.NaN,0f,20f,20f)},{MapViewport(1900f,0f,100f,532f)})){
            try{action();fail("invalid configuration accepted")}catch(_:IllegalArgumentException){}
        }
    }
}
