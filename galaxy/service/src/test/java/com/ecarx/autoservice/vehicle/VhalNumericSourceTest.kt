package com.ecarx.autoservice.vehicle

import org.junit.Assert.*
import org.junit.Test

class VhalNumericSourceTest {
    @Test fun emptyOrWrongTypedVectorsNeverBecomeZero() {
        assertNull(VhalNumericSource.scalar(559947208, emptyList<Number>(), emptyList<Number>(), 0))
        assertNull(VhalNumericSource.scalar(559947208, emptyList<Number>(), listOf(50), 0))
        assertNull(VhalNumericSource.scalar(557855126, listOf(50f), emptyList<Number>(), 0))
        assertNull(VhalNumericSource.scalar(559947208, listOf(Float.NaN), emptyList<Number>(), 0))
        assertNull(VhalNumericSource.scalar(559947208, listOf(0f), emptyList<Number>(), 16))
    }

    @Test fun rawNumbersKeepTheirTypeAndAreNotGuessedIntoPercentages() {
        assertEquals(4339f, VhalNumericSource.scalar(559947208, listOf(4339f), emptyList<Number>(), 0))
        assertEquals(4608, VhalNumericSource.scalar(557855126, emptyList<Number>(), listOf(4608), 0))
        val rows = VehicleCatalog.forModel("L6")
        assertNull(VehicleCatalog.convert(rows.first { it.key == "accelerator" }, 4339f))
        assertNull(VehicleCatalog.convert(rows.first { it.key == "brake" }, 4608))
        assertFalse(VhalNumericSource.withinBounds(32765, -14.0..14.0))
        assertFalse(VhalNumericSource.withinBounds(4339f, 0.0..100.0))
        assertTrue(VhalNumericSource.withinBounds(0, 0.0..100.0))
    }

    @Test fun targetL6UsesVerifiedVhalIdsWhileL7RetainsItsExistingRoute() {
        val l6 = VehicleCatalog.forModel("L6").associateBy { it.key }
        for (key in listOf("accelerator", "brake", "steeringAngle")) {
            assertEquals("vhal", l6.getValue(key).backend)
            assertEquals(0, l6.getValue(key).area)
        }
        assertEquals(557849839, l6.getValue("steeringAngle").propertyId)
        assertEquals(559971591, l6.getValue("steeringAngleSignal").propertyId)
        assertEquals("car_signal", VehicleCatalog.forModel("L7").first { it.key == "steeringAngle" }.backend)
    }

    @Test fun fs12SteeringUsesBothSignedBranchesAndRejectsNonEncodedValues() {
        val spec = VehicleCatalog.forModel("L6").first { it.key == "steeringAngle" }
        assertEquals("0", VehicleCatalog.convert(spec, 0))
        assertEquals("-2", VehicleCatalog.convert(spec, 29))
        assertEquals("-67", VehicleCatalog.convert(spec, 1199))
        assertEquals("0", VehicleCatalog.convert(spec, 32765))
        assertEquals("10", VehicleCatalog.convert(spec, 32587))
        for (invalid in listOf(-1.0, 32768.0, 29.5, Double.NaN)) assertNull(VehicleCatalog.convert(spec, invalid))
    }
}
