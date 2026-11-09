package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class CarPlayNavigationInputTest {
    private fun tlv(type: Int, vararg data: Int): ByteArray = byteArrayOf(
        ((data.size + 4) shr 8).toByte(), (data.size + 4).toByte(), 0, type.toByte(),
        *data.map(Int::toByte).toByteArray())
    private fun fields(vararg values: ByteArray) = ByteArrayOutputStream().apply { values.forEach { write(it) } }.toByteArray()
    @Test fun exposesAppleTurnDistanceRemainingTimeAndEpochSeparately() {
        val input = CarPlayNavigationInput()
        input.accept(Iap2Frame(0x5202, fields(tlv(1, 0, 7), tlv(3, 1), tlv(8, 0))))
        val result = input.accept(Iap2Frame(0x5201, fields(tlv(1, 1), tlv(10, 0, 0, 0, 150),
            tlv(13, 0, 7), tlv(6, 0, 0, 0, 0, 0, 0, 1, 44), tlv(7, 0, 0, 3, 232),
            tlv(5, 0, 0, 0, 0, 101, 83, 241, 0))))!!
        assertTrue(result.active); assertEquals(1, result.maneuverType)
        assertEquals(150, result.distanceToNextMeters); assertEquals(300L, result.remainingSeconds)
        assertEquals(1000L, result.remainingMeters); assertEquals(1700000000L, result.arrivalEpochSeconds)
        input.clear(); assertFalse(input.snapshot().active)
    }
}
