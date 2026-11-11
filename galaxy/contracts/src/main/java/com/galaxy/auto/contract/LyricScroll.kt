package com.galaxy.auto.contract

/** 单次长句滚动：起始停留800ms，28像素/秒到句尾；时钟由播放位置提供，暂停自然冻结。 */
object LyricScroll {
    fun offset(distance: Float,elapsedMillis: Long): Float =
        if(!distance.isFinite()||distance<=0)0f else ((elapsedMillis-800).coerceAtLeast(0)*.028f).coerceAtMost(distance)
}
