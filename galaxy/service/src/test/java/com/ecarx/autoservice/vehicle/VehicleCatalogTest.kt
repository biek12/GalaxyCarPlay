package com.ecarx.autoservice.vehicle

import org.junit.Assert.*
import org.junit.Test

class VehicleCatalogTest {
    @Test fun allReferenceReadableMappingsExistWithoutWriteCommands() {
        for (model in listOf("L6", "L7")) {
            val rows = VehicleCatalog.forModel(model)
            assertEquals(78, rows.size)
            assertEquals(rows.size, rows.map { it.normalizedId }.toSet().size)
            assertTrue(rows.any { it.key == "property.30011" })
            assertTrue(rows.any { it.key == "property.10056" })
        }
    }
    @Test fun modelsKeepDifferentSocAndTireIds() {
        fun id(model: String, key: String) = VehicleCatalog.forModel(model).first { it.key == key }.propertyId
        assertEquals(561024567, id("L6", "soc")); assertEquals(561024570, id("L7", "soc"))
        assertEquals(561024429, id("L6", "tire.frontLeft")); assertEquals(561024431, id("L7", "tire.frontLeft"))
    }
    @Test fun invalidOrFractionalGearDoesNotBecomeParkAndSentinelsNeverBecomeSpeed() {
        val rows = VehicleCatalog.forModel("L6")
        val gear = rows.first { it.key == "gear" }
        assertNull(VehicleCatalog.convert(gear, 0.5)); assertNull(VehicleCatalog.convert(gear, 8))
        assertEquals("D", VehicleCatalog.convert(gear, 3))
        val speed = rows.first { it.key == "displaySpeed" }
        for (raw in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, Float.MIN_VALUE.toDouble(), 500.0))
            assertNull(VehicleCatalog.convert(speed, raw))
    }
    @Test fun engineeringUnitsKeepRawTransformEvidence() {
        val rows = VehicleCatalog.forModel("L6")
        assertEquals("240.0", VehicleCatalog.convert(rows.first { it.key == "tire.frontLeft" }, 2400))
        assertEquals("1500.0", VehicleCatalog.convert(rows.first { it.key == "rpm" }, 3000))
    }
    @Test fun cruiseThreeStatesAndRegenFourLevelsRejectUnknownOrFractionalValues(){
        val specs=VehicleCatalog.forModel("L6")
        val cruise=specs.first { it.key=="cruiseState" };val regen=specs.first { it.key=="regenLevel" }
        assertEquals(listOf("off","standby","active"),(1..3).map { VehicleCatalog.convert(cruise,it) })
        fun packet(value: Int)=java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(value).putInt(0).putInt(0).array()
        assertEquals(561024796,regen.propertyId);assertEquals(1,regen.area)
        assertEquals(listOf("low","medium","high","auto"),(0..3).map { VehicleCatalog.convert(regen,packet(it)) })
        assertNull(VehicleCatalog.convert(regen,packet(4)))
        assertNull(VehicleCatalog.convert(cruise,0));assertNull(VehicleCatalog.convert(regen,4));assertNull(VehicleCatalog.convert(regen,1.5))
        val keys=specs.first { it.key=="property.10037" }
        assertEquals("0,6,0",VehicleCatalog.convert(keys,intArrayOf(0,6,0)))
        assertNull(VehicleCatalog.convert(keys,6))
        assertTrue(specs.none { it.key=="regenPowerKw" }) // 原始电功率尚无独立回收kW合同。
    }
    @Test fun driveModeUsesVerifiedPaBytesAndRejectsVstdAndReservedEnums(){
        val spec=VehicleCatalog.forModel("L6").first {it.key=="driveMode"}
        assertEquals(561023639,spec.propertyId);assertEquals(0,spec.area)
        fun packet(value: Int,available: Int=1,format: Int=0,status: Int=0)=java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(available).putInt(value).putInt(format).putInt(status).array()
        assertEquals(listOf("增程","纯电","性能","智能"),listOf(1,2,3,6).map {VehicleCatalog.convert(spec,packet(it))})
        for(raw in listOf(packet(8),packet(2,available=0),packet(2,format=1),packet(2,status=1),byteArrayOf(1,2),2))assertNull(VehicleCatalog.convert(spec,raw))
    }
    @Test(expected = IllegalArgumentException::class) fun unknownModelIsNotAnotherVehicle() { VehicleCatalog.forModel("FX11") }
}
