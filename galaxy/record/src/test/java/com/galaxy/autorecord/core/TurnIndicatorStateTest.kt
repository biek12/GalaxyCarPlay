package com.galaxy.autorecord.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnIndicatorStateTest {
    private fun left(value: String, status: VehicleValueStatus = VehicleValueStatus.LIVE) = VehicleValue(VehicleKey.LEFT_TURN,value,status)
    private fun right(value: String, status: VehicleValueStatus = VehicleValueStatus.LIVE) = VehicleValue(VehicleKey.RIGHT_TURN,value,status)
    @Test fun `服务小数字符串能够显示左右与双闪`() {
        assertEquals("左",TurnIndicatorState.display(left("1.0"),right("0.0")))
        assertEquals("右",TurnIndicatorState.display(left("0.0"),right("1.0")))
        assertEquals("双闪",TurnIndicatorState.display(left("1.0"),right("1.0")))
        assertEquals("关",TurnIndicatorState.display(left("0.0"),right("0.0")))
    }
    @Test fun `未知枚举和过期状态不得被解释成关闭`() {
        assertEquals("—",TurnIndicatorState.display(left("2.0"),right("0.0")))
        assertEquals("—",TurnIndicatorState.display(left("1.0",VehicleValueStatus.STALE),right("0.0")))
        assertEquals("—",TurnIndicatorState.display(left("NaN"),right("—",VehicleValueStatus.UNAVAILABLE)))
    }
}
