package com.ecarx.autoservice.platform

import org.junit.Assert.*
import org.junit.Test

class FoundationRecoveryTest {
    @Test fun oneLoopBacksOffWithoutPollingVehicleAndStopInvalidatesCompletion() {
        var enabled = true
        val tasks = ArrayDeque<Pair<Long, () -> Unit>>()
        var pending: ((Boolean) -> Unit)? = null
        val loop = FoundationRecovery({ enabled }, { delay, task -> tasks.add(delay to task); AutoCloseable { tasks.removeAll { it.second === task } } },
            { _, complete -> pending = complete })
        loop.start(); loop.start(); assertEquals(1, tasks.size)
        tasks.removeFirst().second(); pending!!(false); assertEquals(30000L, tasks.first().first)
        tasks.removeFirst().second(); pending!!(false); assertEquals(60000L, tasks.first().first)
        tasks.removeFirst().second(); val late = pending!!
        enabled = false; loop.close(); late(true); assertTrue(tasks.isEmpty())
    }
    @Test fun healthyCompletionResetsBackoffAndSchedulesLowFrequencyCheck() {
        val tasks = ArrayDeque<Pair<Long, () -> Unit>>()
        var outcome = false
        val loop = FoundationRecovery({ true }, { delay, task -> tasks.add(delay to task); AutoCloseable {} }, { _, complete -> complete(outcome) })
        loop.start(); tasks.removeFirst().second(); assertEquals(30000L, tasks.first().first)
        outcome = true; tasks.removeFirst().second(); assertEquals(60000L, tasks.first().first)
        outcome = false; tasks.removeFirst().second(); assertEquals(30000L, tasks.first().first)
        loop.close()
    }
    @Test fun projectionDemandExpeditesScheduledBackoffAndDeduplicatesPendingWork() {
        val tasks = ArrayDeque<Pair<Long, () -> Unit>>()
        var completion: ((Boolean) -> Unit)? = null
        val loop = FoundationRecovery({ true }, { delay, task ->
            tasks.add(delay to task); AutoCloseable { tasks.removeAll { it.second === task } }
        }, { _, done -> completion = done })
        loop.start(); tasks.removeFirst().second(); completion!!(false)
        assertEquals(30000L, tasks.single().first)
        loop.requestNow(); loop.requestNow()
        assertEquals(1, tasks.size); assertEquals(0L, tasks.single().first)
        tasks.removeFirst().second()
        loop.requestNow(); assertTrue(tasks.isEmpty()) // Root正在初始化，新的需求复用这一次。
        completion!!(true)
        assertEquals(60000L, tasks.single().first)
        loop.close()
    }
    @Test fun stoppedRecoveryCannotBeWokenByLateDemandWhenAutomaticModeIsDisabled() {
        var enabled = true
        val tasks = ArrayDeque<Pair<Long, () -> Unit>>()
        var completion: ((Boolean) -> Unit)? = null
        val loop = FoundationRecovery({ enabled }, { delay, task ->
            tasks.add(delay to task); AutoCloseable { tasks.removeAll { it.second === task } }
        }, { _, done -> completion = done })
        loop.start(); tasks.removeFirst().second()
        enabled = false; loop.close(); loop.requestNow(); completion!!(true)
        assertTrue(tasks.isEmpty())
    }

}
