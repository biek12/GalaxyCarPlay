package com.ecarx.autoservice.platform.root

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * 车辆帧和控制帧必须走 [RootServiceClient] 的生产写入。
 *
 * 超限时车辆帧不能先写出半帧；控制帧在判定体积前已经写了 operation。
 * op4 与 op6 仍由 [RootProjectionArchiveTest] 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class RootFramesTest {
    /** 记录 flush，避免用缓冲流把“已调用 flush”藏掉。 */
    private class TrackingSink : ByteArrayOutputStream() {
        var flushed = false
            private set

        override fun flush() {
            flushed = true
            super.flush()
        }
    }

    /** 构造 UTF-8 JSON 长度恰好为 [size] 的单键对象，用来卡住 16384 边界。 */
    private fun jsonOfUtf8Size(size: Int): JSONObject {
        val overhead =
            JSONObject()
                .put("k", "")
                .toString()
                .toByteArray(Charsets.UTF_8)
                .size
        val payload = JSONObject().put("k", "a".repeat(size - overhead))
        val actual = payload.toString().toByteArray(Charsets.UTF_8).size
        check(actual == size) { "json size $actual != $size" }
        return payload
    }

    @Test
    fun vehicleFrameWritesOpLengthUtf8AndFlushesWithoutTrailingBytes() {
        val payload = JSONObject().put("title", "中文歌词")
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val sink = TrackingSink()

        RootServiceClient.writeVehicleRequest(DataOutputStream(sink), bytes)

        assertTrue(sink.flushed)
        val raw = sink.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(raw))
        assertEquals(1, input.readInt())
        val parsed = RootWire.read(input)
        assertEquals("中文歌词", parsed.getString("title"))
        assertEquals(-1, input.read())
        assertEquals(bytes.size, raw.size - 8)
        assertArrayEquals(bytes, raw.copyOfRange(8, raw.size))
        assertTrue(bytes.size > "中文歌词".length)
    }

    @Test
    fun vehicleBoundaryAllows16384AndWritesNothingAt16385() {
        val accepted = TrackingSink()
        RootServiceClient.writeVehicleRequest(DataOutputStream(accepted), ByteArray(16384))
        assertTrue(accepted.flushed)
        val acceptedInput = DataInputStream(ByteArrayInputStream(accepted.toByteArray()))
        assertEquals(1, acceptedInput.readInt())
        assertEquals(16384, acceptedInput.readInt())
        assertEquals(16384, acceptedInput.readBytes().size)

        val rejected = TrackingSink()
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                RootServiceClient.writeVehicleRequest(DataOutputStream(rejected), ByteArray(16385))
            }
        assertEquals("vehicle_request_too_large", error.message)
        assertEquals(0, rejected.size())
        assertFalse(rejected.flushed)
    }

    @Test
    fun controlFramesKeepOpcodeAndWriteJsonOnlyWhenRequired() {
        val payload = JSONObject().put("title", "中文歌词")
        val cases =
            listOf(
                Triple(RootOperation.HEALTHY, 0, false),
                Triple(RootOperation.STOP_HELPER, 2, false),
                Triple(RootOperation.SHELL, 3, true),
                Triple(RootOperation.RECEIVER_CLEANUP, 5, true),
                Triple(RootOperation.AUTHORIZE_ADB_KEY, 7, true),
                Triple(RootOperation.STOP_VEHICLE, 8, false),
                Triple(RootOperation.SENTINEL, 9, true),
                Triple(RootOperation.SENTINEL_STREAM, 10, false),
                Triple(RootOperation.HOOK_CONTROL, 11, true),
                Triple(RootOperation.HOOK_RECEIPT, 12, false),
            )
        cases.forEach { (operation, code, writesJson) ->
            assertEquals(code, operation.code)
            assertEquals(writesJson, operation.requestWritesJson)
            val sink = TrackingSink()
            RootServiceClient.writeControlRequest(DataOutputStream(sink), operation, payload)
            assertTrue("$operation flush", sink.flushed)
            val raw = sink.toByteArray()
            val input = DataInputStream(ByteArrayInputStream(raw))
            assertEquals(code, input.readInt())
            if (writesJson) {
                val parsed = RootWire.read(input)
                assertEquals("中文歌词", parsed.getString("title"))
                assertEquals(-1, input.read())
                val body = payload.toString().toByteArray(Charsets.UTF_8)
                assertArrayEquals(body, raw.copyOfRange(8, raw.size))
                assertTrue(body.size > "中文歌词".length)
            } else {
                assertEquals(-1, input.read())
                assertEquals(4, raw.size)
            }
        }
    }

    @Test
    fun operationWithoutJsonIgnoresOversizedPayload() {
        val sink = TrackingSink()
        RootServiceClient.writeControlRequest(
            DataOutputStream(sink),
            RootOperation.HEALTHY,
            jsonOfUtf8Size(16385),
        )
        assertTrue(sink.flushed)
        val input = DataInputStream(ByteArrayInputStream(sink.toByteArray()))
        assertEquals(0, input.readInt())
        assertEquals(-1, input.read())
    }

    @Test
    fun oversizedControlKeepsTheOpcodeAlreadyWritten() {
        val sink = TrackingSink()
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                RootServiceClient.writeControlRequest(
                    DataOutputStream(sink),
                    RootOperation.SHELL,
                    jsonOfUtf8Size(16385),
                )
            }
        assertEquals("Failed requirement.", error.message)
        assertFalse(sink.flushed)
        val input = DataInputStream(ByteArrayInputStream(sink.toByteArray()))
        assertEquals(3, input.readInt())
        assertEquals(-1, input.read())
        val accepted = TrackingSink()
        RootServiceClient.writeControlRequest(
            DataOutputStream(accepted),
            RootOperation.SHELL,
            jsonOfUtf8Size(16384),
        )
        assertTrue(accepted.flushed)
        val acceptedInput = DataInputStream(ByteArrayInputStream(accepted.toByteArray()))
        assertEquals(3, acceptedInput.readInt())
        val parsed = RootWire.read(acceptedInput)
        val overhead =
            JSONObject()
                .put("k", "")
                .toString()
                .toByteArray(Charsets.UTF_8)
                .size
        assertEquals(16384 - overhead, parsed.getString("k").length)
        assertEquals(-1, acceptedInput.read())
    }
}
