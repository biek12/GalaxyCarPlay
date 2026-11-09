package com.galaxy.autorecord.recording

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class FrameReaderGateTest {
    @Test fun stoppedConsumerRetainsReaderUntilProducerAcknowledgesThenClosesOnce() {
        val gate = FrameReaderGate()
        var closes = 0
        gate.stop()
        gate.consume({ fail("停止后不能借图像"); Any() }, {}, {})
        assertEquals(0, closes)
        gate.close { closes++ }
        gate.close { closes++ }
        assertEquals(1, closes)
    }

    @Test fun rolloverWaitsUntilBorrowedFrameHasBeenReturned() {
        val gate = FrameReaderGate()
        val entered = CountDownLatch(1)
        val finishFrame = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val consumer = thread {
            try { gate.consume({ Any() }, {
                order.add("using")
                entered.countDown()
                check(finishFrame.await(3, TimeUnit.SECONDS))
            }, { order.add("frame_returned") }) } catch (failure: Throwable) { error.set(failure) }
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val closer = thread {
            closing.countDown()
            gate.close { order.add("reader_closed"); closed.countDown() }
        }
        try {
            assertTrue(closing.await(3, TimeUnit.SECONDS))
            assertFalse("图像使用期间不能关闭原生Reader", closed.await(100, TimeUnit.MILLISECONDS))
        } finally { finishFrame.countDown(); consumer.join(3000); closer.join(3000) }
        assertNull(error.get())
        assertEquals(listOf("using", "frame_returned", "reader_closed"), order)
        var lateAcquires = 0
        gate.consume({ lateAcquires++; Any() }, {}, {})
        gate.close { fail("Reader不能重复释放") }
        assertEquals(0, lateAcquires)
    }

    @Test fun frameIsReturnedEvenWhenTimestampOrEncoderThrows() {
        val gate = FrameReaderGate()
        var returned = 0
        try {
            gate.consume({ Any() }, { throw IllegalStateException("frame failure") }, { returned++ })
            fail("应保留原始错误")
        } catch (_: IllegalStateException) { }
        gate.close { }
        assertEquals(1, returned)
    }
}
