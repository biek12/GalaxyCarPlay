package com.ecarx.autoservice.sentinel

/** 无计时上限；20%是用户确认的退出线，未知值不能当作满足布防条件。 */
internal object SentinelPolicy {
    data class Input(val enabled:Boolean,val platform:Boolean,val soc:Double?,val lock:Double?,val gear:String?,val mode:String?,val power:Int)
    fun reason(i:Input):String=when {
        !i.enabled->"disabled"
        !i.platform->"platform_disabled"
        i.soc==null||i.soc !in 0.0..100.0->"battery_unknown"
        i.soc<20.0->"low_battery"
        i.power in setOf(2,5,7)->"suspended"
        i.power !in setOf(3,6,8)->"power_unknown"
        i.lock==null||i.lock !in setOf(0.0,1.0,2.0,3.0)->"lock_unknown"
        i.lock!=3.0->"waiting_lock"
        i.gear!="P"->"waiting_park"
        i.mode !in setOf("standby","on","alert","alarm")->"waiting_mode"
        else->"monitoring"
    }
}
