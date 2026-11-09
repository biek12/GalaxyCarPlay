package com.galaxy.autorecord.recording

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class EncoderDrainOwnerTest {
    @Test fun timeoutNeverReleasesResourcesStillUsedByNativeWork() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val releases = AtomicInteger()
        val workerThread = AtomicReference<Thread>()
        val owner = EncoderDrainOwner("fake-native-drain", 20, drain = {
            workerThread.set(Thread.currentThread()); entered.countDown()
            check(unblock.await(5, TimeUnit.SECONDS))
        }, release = {
            assertSame(workerThread.get(), Thread.currentThread())
            releases.incrementAndGet()
        })
        owner.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            assertFalse(owner.finish())
            assertTrue(owner.expired)
            assertFalse(owner.completed)
            assertEquals(0, releases.get())
        } finally { unblock.countDown() }
        assertTrue(owner.finish())
        owner.start(); assertTrue(owner.finish())
        assertEquals(1, releases.get())
    }

    @Test fun finishBeforeStartAndConcurrentFinishHaveOneOwner() {
        val releases = AtomicInteger()
        val owner = EncoderDrainOwner("fake-eos", drain = { assertTrue(it.stopping) }, release = { releases.incrementAndGet() })
        val results = (1..4).map { java.util.concurrent.FutureTask { owner.finish() }.also { Thread(it).start() } }
        results.forEach { assertTrue(it.get(2, TimeUnit.SECONDS)) }
        assertTrue(owner.completed)
        assertEquals(1, releases.get())
    }
}
