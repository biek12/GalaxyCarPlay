package com.shilapi.xcertplay

import android.os.Bundle
import android.os.SystemClock
import com.shilapi.xcertplay.transport.VehicleGear
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 车速/挡位订阅的判定回归：只接受本车AutoService发布的km/h有效值，且必须同时有已知挡位。
 * 这里直接投递Bundle，不建Binder；线程模型（主线程回调、iAP2线程drain）由synchronized覆盖，
 * 单线程测试不重复验证锁，只验证判定与边界。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GalaxyVehicleSpeedTest {
    private val context = RuntimeEnvironment.getApplication()
    private val speed = GalaxyVehicleSpeed(context)

    /** 与 VehicleSignal.bundle(now) 同形状。单独构造才能造“发送时VALID、消费时已过期”的Bundle。 */
    private fun signal(key: String, value: String?, unit: String, status: String = "VALID",
                       receivedAt: Long = SystemClock.elapsedRealtime(), maxAgeMillis: Long = 2_000L) =
        Bundle().apply {
            putString("key", key); putString("value", value); putString("unit", unit)
            putString("source", "test"); putInt("propertyId", 1)
            putLong("receivedAt", receivedAt); putLong("epoch", 0)
            putString("status", status); putLong("maxAgeMillis", maxAgeMillis)
        }

    /** 键名是契约上的线上名称；缺值键不出现，与 AutoService 的发布形状一致。 */
    private fun feed(speedKmh: String? = "60", gear: String? = "D", unit: String = "km/h",
                     status: String = "VALID", receivedAt: Long = SystemClock.elapsedRealtime(),
                     maxAgeMillis: Long = 2_000L): Bundle {
        val values = Bundle()
        if (speedKmh != null) values.putBundle("displaySpeed",
            signal("displaySpeed", speedKmh, unit, status, receivedAt, maxAgeMillis))
        if (gear != null) values.putBundle("gear", signal("gear", gear, "gear", status, receivedAt, maxAgeMillis))
        return values
    }

    @Test fun speedInKmhBecomesMetersPerSecondWithTheReportedGear() {
        assertNull("未开始订阅时没有状态可说", speed.status())
        speed.acceptVehicleFeed(feed("60", "D"))
        val reading = speed.drain()
        assertNotNull(reading)
        assertEquals(VehicleGear.DRIVE, reading!!.gear)
        assertEquals(1, reading.samples.size)
        assertEquals(60 / 3.6, reading.samples.single().metersPerSecond, 1e-9)
        assertNotNull(speed.status())
    }

    @Test fun everyReportedGearMapsToItsLetter() {
        assertEquals(VehicleGear.PARK, reading(feed("0", "P")).gear)
        assertEquals(VehicleGear.REVERSE, reading(feed("5", "R")).gear)
        assertEquals(VehicleGear.NEUTRAL, reading(feed("0", "N")).gear)
        assertEquals(VehicleGear.DRIVE, reading(feed("80", "D")).gear)
    }

    @Test fun unavailableWrongUnitAndOutOfRangeValuesAreDropped() {
        speed.acceptVehicleFeed(feed("60", "D", status = "UNAVAILABLE")); assertNull(speed.drain())
        speed.acceptVehicleFeed(feed("60", "D", status = "REJECTED")); assertNull(speed.drain())
        // 单位不是km/h就不换算：m/s或大小写变体都不猜。
        speed.acceptVehicleFeed(feed("60", "D", unit = "m/s")); assertNull(speed.drain())
        speed.acceptVehicleFeed(feed("60", "D", unit = "KM/H")); assertNull(speed.drain())
        speed.acceptVehicleFeed(feed("60", "D", unit = "")); assertNull(speed.drain())
        // 超量程与非法数字整轮丢弃，不截断成边界值。
        speed.acceptVehicleFeed(feed("400", "D")); assertNull(speed.drain())
        speed.acceptVehicleFeed(feed("-1", "D")); assertNull(speed.drain())
        speed.acceptVehicleFeed(feed("NaN", "D")); assertNull(speed.drain())
        speed.acceptVehicleFeed(feed("abc", "D")); assertNull(speed.drain())
        // 边界值本身可用。
        assertEquals(300 / 3.6, reading(feed("300", "D")).samples.single().metersPerSecond, 1e-9)
        assertEquals(0.0, reading(feed("0", "D")).samples.single().metersPerSecond, 1e-9)
    }

    @Test fun valueOlderThanItsOwnMaxAgeIsStaleEvenWhenSentAsValid() {
        // status 在发送端是VALID，但 receivedAt 超出字段自带 maxAgeMillis：消费端要自己重算。
        speed.acceptVehicleFeed(feed("60", "D",
            receivedAt = SystemClock.elapsedRealtime() - 5_000, maxAgeMillis = 2_000))
        assertNull(speed.drain())
        // 同一份数据在上限内仍然可用，说明丢的是新鲜度而不是字段本身。
        speed.acceptVehicleFeed(feed("60", "D", receivedAt = SystemClock.elapsedRealtime() - 500))
        assertNotNull(speed.drain())
    }

    @Test fun unknownGearDropsTheRoundAndStopsPairingOldGearWithNewSamples() {
        // 未知挡位不猜：本车没有 M/S 这类挡位语义。
        speed.acceptVehicleFeed(feed("60", "S")); assertNull(speed.drain())
        speed.acceptVehicleFeed(feed("60", "D")); assertNotNull(speed.drain())
        speed.acceptVehicleFeed(feed("60", "D"))
        speed.acceptVehicleFeed(feed("61", "unknown"))
        // 挡位变未知后，先前样本不得配上旧挡位发出。
        assertNull(speed.drain())
    }

    @Test fun missingGearKeyDoesNotProduceASample() {
        speed.acceptVehicleFeed(feed("60", gear = null))
        assertNull(speed.drain())
    }

    @Test fun drainTakesTheBatchOnceAndLeavesNothingBehind() {
        speed.acceptVehicleFeed(feed("50", "R"))
        val first = speed.drain()
        assertEquals(VehicleGear.REVERSE, first!!.gear)
        assertEquals(1, first.samples.size)
        assertNull(speed.drain())
    }

    @Test fun sampleQueueIsBoundedAndDropsTheOldest() {
        for (kmh in 0..39) speed.acceptVehicleFeed(feed(kmh.toString(), "D"))
        val reading = speed.drain()
        assertNotNull(reading)
        assertEquals(GalaxyVehicleSpeed.MAX_SAMPLES, reading!!.samples.size)
        // 40轮只留最后32轮：第一份是被保留下来的第8轮（8 km/h）。
        assertEquals(8 / 3.6, reading.samples.first().metersPerSecond, 1e-9)
        assertEquals(39 / 3.6, reading.samples.last().metersPerSecond, 1e-9)
    }

    @Test fun outputSettingDefaultsOnAndReadsBack() {
        assertTrue("默认开启：数据本来就由AutoService采集，不需要ADB/Root",
            GalaxyVehicleOutputSettings.vehicleSpeedToIphone(context))
        GalaxyVehicleOutputSettings.setVehicleSpeedToIphone(context, false)
        assertFalse(GalaxyVehicleOutputSettings.vehicleSpeedToIphone(context))
        GalaxyVehicleOutputSettings.setVehicleSpeedToIphone(context, true)
        assertTrue(GalaxyVehicleOutputSettings.vehicleSpeedToIphone(context))
    }

    private fun reading(values: Bundle) = run {
        speed.acceptVehicleFeed(values)
        val reading = speed.drain()
        assertNotNull(reading)
        reading!!
    }
}