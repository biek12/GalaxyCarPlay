package com.galaxy.autorecord.preview

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LensProfileTest {
    private fun profile()=LensProfile("2",1280,800,308f,306f,640f,400f,floatArrayOf(.12f,-.02f,.006f,-.001f),"synthetic-test-only")
    @Test fun scalesIntrinsicsForFullFrameRecording(){
        val scaled=LensProfile.parse(profile().toJson().toString(),"2",640,400)!!
        assertEquals(154f,scaled.fx,0f);assertEquals(153f,scaled.fy,0f)
        assertEquals(320f,scaled.cx,0f);assertArrayEquals(profile().coefficients,scaled.coefficients,0f)
    }
    @Test fun rejectsDifferentCameraAndCrop(){
        val raw=profile().toJson().toString()
        assertNull(LensProfile.parse(raw,"3",1280,800))
        assertNull(LensProfile.parse(raw,"2",1280,720))
        assertNull(LensProfile.parse(raw,"2",0,0))
    }
    @Test fun rejectsUnverifiedUnsupportedOrInvalidProfile(){
        assertNull(LensProfile.parse(profile().toJson().put("verified",false).toString()))
        assertNull(LensProfile.parse(profile().toJson().put("model","rational8").toString()))
        assertNull(LensProfile.parse(profile().toJson().put("fx",-1).toString()))
        assertNull(LensProfile.parse(profile().toJson().put("source","").toString()))
    }
}
