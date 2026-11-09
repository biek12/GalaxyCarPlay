package com.ecarx.autoservice.sentinel

import org.junit.Assert.assertEquals
import org.junit.Test

class SentinelPolicyTest {
    private val ready=SentinelPolicy.Input(true,true,20.0,3.0,"P","standby",6)
    @Test fun twentyPercentIsAllowedAndBelowTwentyExits(){
        assertEquals("monitoring",SentinelPolicy.reason(ready))
        assertEquals("low_battery",SentinelPolicy.reason(ready.copy(soc=19.999)))
    }
    @Test fun unknownOrExpiredBatteryNeverArms(){
        for(soc in listOf(null,Double.NaN,-1.0,101.0))assertEquals("battery_unknown",SentinelPolicy.reason(ready.copy(soc=soc)))
    }
    @Test fun lockAndParkAreIndependentOfScreen(){
        for(lock in listOf(0.0,1.0,2.0))assertEquals("waiting_lock",SentinelPolicy.reason(ready.copy(lock=lock)))
        assertEquals("lock_unknown",SentinelPolicy.reason(ready.copy(lock=4.0)))
        assertEquals("waiting_park",SentinelPolicy.reason(ready.copy(gear="D")))
    }
    @Test fun sleepDoesNotBecomeActiveRecording(){
        for(power in listOf(2,5,7))assertEquals("suspended",SentinelPolicy.reason(ready.copy(power=power)))
        assertEquals("power_unknown",SentinelPolicy.reason(ready.copy(power=0)))
        assertEquals("monitoring",SentinelPolicy.reason(ready.copy(power=3)))
    }
    @Test fun userAndPlatformOffWinAndUnknownVstdDoesNotArm(){
        assertEquals("disabled",SentinelPolicy.reason(ready.copy(enabled=false)))
        assertEquals("platform_disabled",SentinelPolicy.reason(ready.copy(platform=false)))
        assertEquals("waiting_mode",SentinelPolicy.reason(ready.copy(mode="off")))
        assertEquals("waiting_mode",SentinelPolicy.reason(ready.copy(mode=null)))
    }
}
