package com.ecarx.autoservice.vehicle

import org.junit.Assert.*
import org.junit.Test

class VehicleCatalogTest {
    @Test fun allReferenceReadableMappingsExistWithoutWriteCommands() {
        for (model in listOf("L6", "L7")) {
            val rows = VehicleCatalog.forModel(model)
            assertEquals(72, rows.size)
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
    @Test(expected = IllegalArgumentException::class) fun unknownModelIsNotAnotherVehicle() { VehicleCatalog.forModel("FX11") }
}
