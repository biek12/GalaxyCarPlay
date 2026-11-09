package com.galaxy.autorecord.recording

/** 每一路独立核验输出活性，不能用其他健康方向的帧掩盖一路卡死。 */
internal object OutputWatchdog {
    const val TIMEOUT_US=8_000_000L
    fun stalled(nowUs:Long,startedUs:Long,lastOutputUs:Map<String,Long>):List<String> =
        lastOutputUs.filterValues { nowUs-maxOf(startedUs,it)>=TIMEOUT_US }.keys.toList()
}
