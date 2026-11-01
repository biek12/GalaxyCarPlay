package com.ecarx.autoservice.vehicle

import com.galaxy.auto.contract.AutoContract

/**
 * v4订阅驱动的采集需求：谁要哪些键、要多快。
 *
 * 服务端只做“合并 + 到期判定”，不持有Binder/线程状态，便于离线测试。
 * 关键约束：**没人订阅的键不进入采集集合**，所以这里合并出的键集直接决定车机读取次数；
 * 同一键被多个租约订阅时取最紧（最小）的间隔，否则慢的那个订阅者会被快的拖慢而不自知。
 */
internal class VehicleDemand {
    // dump()/stats() 在Binder线程读这张表，赋值只在采集线程；@Volatile保证诊断读数看到的是一张完整的表。
    @Volatile private var intervals: Map<String, Int> = emptyMap()
    private val readAt = HashMap<String, Long>()

    /** 当前需要采集的键及其最小间隔；空表示无人订阅（采集端应完全停摆）。 */
    fun intervals(): Map<String, Int> = intervals
    fun keys(): Set<String> = intervals.keys
    val active: Boolean get() = intervals.isNotEmpty()
    /** 采集节拍：最紧间隔本身，且不小于契约下限。无订阅时返回0，调用方不得据此调度。 */
    val periodMillis: Long get() {
        val fastest = intervals.values.minOrNull() ?: return 0L
        return fastest.coerceAtLeast(AutoContract.MIN_FEED_INTERVAL_MS).toLong()
    }

    /**
     * 用租约的(key集, 间隔)重新合并。
     *
     * 被取消订阅的键立即从[readAt]移除：重新订阅时必须立刻读一次，
     * 不能沿用上一代订阅的“刚读过”而让新订阅者等一个完整周期。
     */
    fun assign(next: Map<String, Int>) {
        val safe = if (next.isEmpty()) emptyMap() else HashMap<String, Int>(next.size).also { out ->
            next.forEach { (key, interval) ->
                out[key] = interval.coerceIn(AutoContract.MIN_FEED_INTERVAL_MS, AutoContract.MAX_FEED_INTERVAL_MS)
            }
        }
        if (safe.keys != intervals.keys) readAt.keys.retainAll(safe.keys)
        intervals = safe
    }

    /**
     * 到期键：从未读过、或距上次读取已达该键间隔。
     * 只返回仍在订阅集合里的键，避免上一代残留键被继续读取。
     */
    fun due(now: Long): Set<String> {
        if (intervals.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>(intervals.size)
        for ((key, interval) in intervals) {
            val last = readAt[key] ?: -1L
            // now可能因回拨小于last：按到期处理，宁可多读一次也不让消费者拿旧值。
            if (last < 0L || now < last || now - last >= interval) out += key
        }
        return out
    }

    fun markRead(keys: Collection<String>, now: Long) { keys.forEach { readAt[it] = now } }

    /** 暂停/停服时清空，恢复后重新订阅的第一拍必须立即补读。 */
    fun reset() { readAt.clear() }

    companion object {
        /** 多租约合并：同一键取最紧间隔，没人订阅的键不出现。 */
        fun merge(leases: Iterable<Pair<Set<String>, Int>>): Map<String, Int> {
            val out = HashMap<String, Int>()
            leases.forEach { (keys, interval) ->
                if (keys.isEmpty()) return@forEach
                val safe = interval.coerceIn(AutoContract.MIN_FEED_INTERVAL_MS, AutoContract.MAX_FEED_INTERVAL_MS)
                keys.forEach { key -> out.merge(key, safe) { a, b -> minOf(a, b) } }
            }
            return out
        }
    }
}