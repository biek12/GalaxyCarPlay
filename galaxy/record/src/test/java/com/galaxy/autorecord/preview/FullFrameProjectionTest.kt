package com.galaxy.autorecord.preview

import org.junit.Assert.*
import org.junit.Test

class FullFrameProjectionTest {
    private val profile=LensProfile("synthetic",1280,800,308f,296f,652f,402f,
        floatArrayOf(.1184f,-.018f,.00661f,-.001925f),"synthetic-only")
    @Test fun retainsEveryPointOnAllFourOriginalEdges() {
        for(i in 0..200){val t=i/200.0
            for((u,v) in listOf(0.0 to t,1.0 to t,t to 0.0,t to 1.0)){
                val p=FullFrameProjection.source(u,v,profile)
                assertEquals(u,p.first,1e-10);assertEquals(v,p.second,1e-10)
            }
        }
    }
    @Test fun interiorHasNoBlackHolesOrFoldedCells() {
        for(i in 0..100)for(j in 0..100){
            val p=FullFrameProjection.source(i/100.0,j/100.0,profile)
            assertTrue(p.first in 0.0..1.0 && p.second in 0.0..1.0)
        }
        for(i in 0 until 100)for(j in 0 until 100){
            val p=FullFrameProjection.source(i/100.0,j/100.0,profile)
            val right=FullFrameProjection.source((i+1)/100.0,j/100.0,profile)
            val down=FullFrameProjection.source(i/100.0,(j+1)/100.0,profile)
            assertTrue((right.first-p.first)*(down.second-p.second)-(right.second-p.second)*(down.first-p.first)>0)
        }
    }
    @Test fun centerStillUsesOpticalPrincipalPointInsteadOfRawPassthrough() {
        val center=FullFrameProjection.source(.5,.5,profile)
        assertEquals(profile.cx/1280.0,center.first,1e-6)
        assertEquals(profile.cy/800.0,center.second,1e-6)
        val p=FullFrameProjection.source(.25,.5,profile)
        assertTrue(kotlin.math.abs(p.first-.25)>.01)
    }
}
