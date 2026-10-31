package com.ecarx.autoservice.vehicle

import com.galaxy.auto.contract.AutoContract
import org.junit.Assert.*
import org.junit.Test

/**
 * v4订阅需求合并与到期判定的纯逻辑回归：不依赖车机、原厂SDK或线程。
 *
 * 这里锁的是“没人订阅不采集”与“同键取最紧间隔”这两条，采集端的所有省电收益都建立在它们之上。
 */
class VehicleDemandTest {
    @Test fun nobodySubscribedMeansNoCollectionAtAll() {
        val demand = VehicleDemand()
        demand.assign(VehicleDemand.merge(listOf(emptySet<String>() to 250)))
        assertFalse(demand.active)
        assertEquals(0L, demand.periodMillis)
        assertTrue(demand.due(1000).isEmpty())
    }

    @Test fun sameKeyTakesTheTightestIntervalNotTheLastOrAverage() {
        val merged = VehicleDemand.merge(listOf(
            setOf("displaySpeed", "gear") to 1000,
            setOf("displaySpeed") to 250,
        ))
        assertEquals(250, merged["displaySpeed"])
        assertEquals(1000, merged["gear"])
        assertEquals(setOf("displaySpeed", "gear"), merged.keys)
    }

    @Test fun intervalsAreClampedToTheContractRange() {
        val low = VehicleDemand.merge(listOf(setOf("displaySpeed") to 1))
        assertEquals(AutoContract.MIN_FEED_INTERVAL_MS, low["displaySpeed"])
        val high = VehicleDemand.merge(listOf(setOf("displaySpeed") to Int.MAX_VALUE))
        assertEquals(AutoContract.MAX_FEED_INTERVAL_MS, high["displaySpeed"])
    }

    @Test fun keysAreOnlyReadWhenTheirOwnIntervalElapsed() {
        val demand = VehicleDemand()
        demand.assign(mapOf("displaySpeed" to 250, "gear" to 1000))
        assertEquals(250L, demand.periodMillis)
        assertEquals(setOf("displaySpeed", "gear"), demand.due(10_000))
        demand.markRead(setOf("displaySpeed", "gear"), 10_000)
        assertTrue(demand.due(10_100).isEmpty())
        assertEquals(setOf("displaySpeed"), demand.due(10_250))
        assertEquals(setOf("displaySpeed", "gear"), demand.due(11_000))
    }

    @Test fun resubscribingAfterUnsubscribeReadsImmediatelyInsteadOfWaiting() {
        val demand = VehicleDemand()
        demand.assign(mapOf("displaySpeed" to 1000))
        demand.markRead(setOf("displaySpeed"), 5_000)
        assertTrue(demand.due(5_100).isEmpty())
        // 退订再订阅：旧“刚读过”的记录必须作废，否则新订阅者要白等一个周期。
        demand.assign(emptyMap())
        demand.assign(mapOf("displaySpeed" to 1000))
        assertEquals(setOf("displaySpeed"), demand.due(5_200))
    }

    @Test fun clockGoingBackwardsIsTreatedAsDue() {
        val demand = VehicleDemand()
        demand.assign(mapOf("displaySpeed" to 1000))
        demand.markRead(setOf("displaySpeed"), 9_000)
        // 回拨时宁可多读一次，也不让消费者拿旧值。
        assertEquals(setOf("displaySpeed"), demand.due(1_000))
    }
}