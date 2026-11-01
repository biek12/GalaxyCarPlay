package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayLocationSelectionTest {
    @Test fun productsOwnSpeedSourceKeepsTheCarsPositionProvider() {
        assertEquals(CarPlayLocationSelection.Kind.PRODUCT,
            CarPlayLocationSelection.choose(locationReporting = true, speedEnabled = true, productSpeed = true, bydWheelSpeed = false))
    }

    @Test fun productSourceWinsOverTheUpstreamSdkInference() {
        // 上游只要SDK存在就只发轮速；产品来源在场时不进入该分支。
        assertEquals(CarPlayLocationSelection.Kind.PRODUCT,
            CarPlayLocationSelection.choose(true, true, productSpeed = true, bydWheelSpeed = true))
    }

    @Test fun disabledProductSourceFallsBackToTheUpstreamDecision() {
        assertEquals(CarPlayLocationSelection.Kind.VEHICLE_SPEED_ONLY,
            CarPlayLocationSelection.choose(true, speedEnabled = true, productSpeed = false, bydWheelSpeed = false))
        assertEquals(CarPlayLocationSelection.Kind.POSITION_WITH_SPEED,
            CarPlayLocationSelection.choose(true, speedEnabled = true, productSpeed = false, bydWheelSpeed = true))
        assertEquals(CarPlayLocationSelection.Kind.POSITION_ONLY,
            CarPlayLocationSelection.choose(true, speedEnabled = false, productSpeed = false, bydWheelSpeed = false))
    }

    @Test fun locationReportingOffSuppressesBothSources() {
        assertEquals(CarPlayLocationSelection.Kind.DISABLED,
            CarPlayLocationSelection.choose(locationReporting = false, speedEnabled = true, productSpeed = true, bydWheelSpeed = true))
    }
}