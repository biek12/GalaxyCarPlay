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
}
