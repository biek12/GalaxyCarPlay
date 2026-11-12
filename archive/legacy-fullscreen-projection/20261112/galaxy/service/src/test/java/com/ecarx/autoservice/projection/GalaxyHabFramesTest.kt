package com.ecarx.autoservice.projection

import org.junit.Assert.*
import org.junit.Test

class GalaxyHabFramesTest {
    @Test fun timingFollowsTheFrameThatSurvivesReplacement() {
        val frames = GalaxyHabFrames(4)
        val old = frames.acquire()!!
        frames.publish(old, 100)
        val latest = frames.acquire()!!
        frames.publish(latest, 200)
        val frame = frames.takeStamped()!!
        assertSame(latest, frame.pixels)
        assertEquals(200L, frame.acquiredAtNanos)
        assertNull(frames.takeStamped())
        frames.release(frame.pixels)
        frames.close()
    }
    @Test fun slowSenderDoesNotBlockCaptureOrReuseItsBufferAndOnlyLatestFrameSurvives() {
        val frames = GalaxyHabFrames(4)
        val sending = frames.acquire()!!
        frames.publish(sending)
        assertSame(sending, frames.take())
        val old = frames.acquire()!!
        frames.publish(old)
        val latest = frames.acquire()!!
        latest[0] = 3
        frames.publish(latest)
        val reusable = frames.acquire()!!
        assertSame(old, reusable)
        assertNotSame(sending, reusable)
        assertSame(latest, frames.take())
        assertNull(frames.take())
        frames.release(reusable)
        frames.release(latest)
        frames.release(sending)
        assertNotNull(frames.acquire())
        assertNotNull(frames.acquire())
        assertNotNull(frames.acquire())
        assertNull(frames.acquire())
    }
    @Test fun lateProducerAndConsumerAreDiscardedAfterClose() {
        val frames = GalaxyHabFrames(4)
        val capture = frames.acquire()!!
        val sending = frames.acquire()!!
        frames.close()
        frames.publish(capture)
        frames.release(sending)
        assertNull(frames.acquire())
        assertNull(frames.take())
    }
}
