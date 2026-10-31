package com.ecarx.autoservice.vehicle

import android.os.Bundle
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.SignalStatus
import com.galaxy.auto.contract.VehicleSignal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v3按需订阅的纯逻辑：键校验与按租约裁剪；不涉及Binder、线程和采集节拍。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VehicleFeedTest {
    /** 与服务端同形状的单键信号Bundle。 */
    private fun signal(key: String, value: String?): Bundle = VehicleSignal(key, value, "km/h",
        "ECARX.car_signal", 557874774, 1000L, null, 1L, SignalStatus.VALID).bundle(1500L)

    @Test fun validateAcceptsTheTwoV3ContractAliases() {
        assertEquals(setOf(AutoContract.SIGNAL_DISPLAY_SPEED, AutoContract.SIGNAL_GEAR),
            VehicleFeed.validate(arrayOf(AutoContract.SIGNAL_DISPLAY_SPEED, AutoContract.SIGNAL_GEAR), "L6"))
    }

    @Test fun validateRejectsUnknownKeyInsteadOfDroppingHalfASubscription() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            VehicleFeed.validate(arrayOf(AutoContract.SIGNAL_DISPLAY_SPEED, "notARealSignal"), "L6")
        }
        assertEquals("unknown_signal:notARealSignal", error.message)
    }

    @Test fun validateDeduplicatesAndSortsStably() {
        // 重复键只保留一次，顺序与请求顺序无关，便于订阅回显/日志直接比较。
        val keys = VehicleFeed.validate(arrayOf("soc", "gear", "displaySpeed", "gear"), "L6")
        assertEquals(listOf("displaySpeed", "gear", "soc"), keys.toList())
    }

    /**
     * 旧公共别名必须仍可订阅：它们没有独立属性，采集侧翻译成基础键读一次再派生同名子Bundle。
     * 若这里退化成“别名不可订阅”，主题里的油耗/电耗/手刹/位置灯/安全带会从有值变成永远没有。
     */
    @Test fun legacyAliasesStaySubscribableAndMapToTheirBaseProperty() {
        assertEquals(setOf("fuelConsumption"), VehicleFeed.validate(arrayOf("fuelConsumption"), "L6"))
        assertEquals("property.10061", VehicleCatalog.baseKey("fuelConsumption"))
        assertEquals("property.10062", VehicleCatalog.baseKey("electricConsumption"))
        assertEquals("property.10040", VehicleCatalog.baseKey("parkingBrake"))
        assertEquals("property.10041", VehicleCatalog.baseKey("positionLights"))
        assertEquals("property.10056", VehicleCatalog.baseKey("driverSeatbeltWarning"))
        // 普通目录键原样返回，翻译只作用于别名表内的键。
        assertEquals("displaySpeed", VehicleCatalog.baseKey("displaySpeed"))
        assertFalse("displaySpeed" in VehicleCatalog.LEGACY_ALIASES)
    }

    @Test fun emptyOrNullKeysMeanUnsubscribed() {
        assertTrue(VehicleFeed.validate(emptyArray(), "L6").isEmpty())
        assertTrue(VehicleFeed.validate(null, "L6").isEmpty())
    }

    @Test fun selectKeepsOnlyRequestedKeysAndAddsNoTickFields() {
        val values = Bundle().apply {
            putBundle("displaySpeed", signal("displaySpeed", "42.0"))
            putBundle("gear", signal("gear", "D"))
            putBundle("soc", signal("soc", "80"))
        }
        val out = VehicleFeed.select(values, setOf("displaySpeed", "soc"))
        assertEquals(setOf("displaySpeed", "soc"), out.keySet())
        assertEquals("42.0", out.getBundle("displaySpeed")!!.getString("value"))
        // at/sequence 由 AutoService 追加；select 自己不写，便于测试与复用。
        assertFalse(out.containsKey("at")); assertFalse(out.containsKey("sequence"))
    }

    @Test fun selectDeepCopiesSoLaterSourceMutationCannotLeak() {
        val source = signal("displaySpeed", "42.0")
        val values = Bundle().apply { putBundle("displaySpeed", source) }
        val out = VehicleFeed.select(values, setOf("displaySpeed"))
        val copy = out.getBundle("displaySpeed")!!
        // Bundle(Bundle)只是浅拷贝map条目；若实现退化成它，这里的断言会看到被改后的值。
        source.putString("value", "99.0")
        values.putBundle("displaySpeed", signal("displaySpeed", "0.0"))
        assertEquals("42.0", copy.getString("value"))
        assertEquals("42.0", out.getBundle("displaySpeed")!!.getString("value"))
    }

    @Test fun missingSignalIsOmittedInsteadOfFilledWithDefault() {
        val values = Bundle().apply { putBundle("displaySpeed", signal("displaySpeed", "42.0")) }
        // 只有订阅键存在才复制；不出现即代表本轮无值，不补0也不补默认状态。
        assertTrue(VehicleFeed.select(values, setOf("gear")).isEmpty())
        assertTrue(VehicleFeed.select(values, emptySet()).isEmpty())
    }
}