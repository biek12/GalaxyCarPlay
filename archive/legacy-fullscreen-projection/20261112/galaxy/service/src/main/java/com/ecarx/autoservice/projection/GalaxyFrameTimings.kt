package com.ecarx.autoservice.projection

/** 有界阶段计时。热路径O(1)，诊断时才复制/排序最近256个样本；全部使用Android单调时钟。 */
internal class GalaxyFrameTimings(private val capacity: Int=256) {
    private val values=LongArray(capacity)
    private var count=0
    private var index=0
    init { require(capacity>0) }
    @Synchronized fun recordMicros(value: Long) {
        if(value<0)return
        values[index]=value;index=(index+1)%capacity;count=minOf(count+1,capacity)
    }
    @Synchronized fun metrics(prefix: String): String {
        if(count==0)return "${prefix}Samples=0"
        val sorted=values.copyOf(count).apply { sort() }
        fun percentile(percent: Int)=sorted[((count.toLong()*percent+99)/100-1).toInt().coerceAtLeast(0)]
        return "${prefix}Samples=$count ${prefix}P50=${percentile(50)} ${prefix}P95=${percentile(95)} ${prefix}P99=${percentile(99)}"
    }
}
