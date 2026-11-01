package com.ecarx.autoservice.vehicle

import android.os.Binder
import ecarx.car.IECarXCar
import ecarx.car.hardware.property.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowServiceManager
import java.util.Collections

/**
 * 采集器调度约束（v4）：一条可取消的轮询链，且退订只取消轮询、不吞掉排队中的新订阅。
 * 这两条都只在 worker 线程的消息队列上才暴露，所以用真实 HandlerThread + 假车机服务跑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VehicleCollectorSchedulingTest {
    private fun clearService() {
        val field = ShadowServiceManager::class.java.getDeclaredField("binderServices").apply { isAccessible = true }
        (field.get(null) as MutableMap<*, *>).remove("ecarxcar_service")
        ECarXCarPropertyManagerBase.callback = null
    }

    /** 装一台只会回10的假车机：发现状态离开重试态后，节拍才按订阅间隔走而不是3秒重试。 */
    private fun installVehicle(propertyId: Int) {
        val root = Binder().apply { attachInterface(null, "ecarx.car.IECarXCar") }
        val endpoint = Binder().apply { attachInterface(null, "ecarx.car.hardware.property.IECarXCarProperty") }
        IECarXCar.Stub.service = object : IECarXCar {
            override fun asBinder() = root
            override fun getCarService(name: String) = endpoint
        }
        IECarXCarProperty.Stub.service = object : IECarXCarProperty {
            override fun getProperty(id: Int, area: Int) = ECarXCarPropertyValue(id, area, 10)
            override fun getPropertyList() = listOf(TestConfig(propertyId, intArrayOf(1)))
        }
        ShadowServiceManager.addBinderService("ecarxcar_service", IECarXCar::class.java, IECarXCar.Stub.service)
    }

    private fun collector(feeds: MutableList<Long>) = VehicleCollector(
        RuntimeEnvironment.getApplication(), VehicleInventory(), { _, _ -> }, {}, {}, { _, at -> feeds += at })

    private fun awaitCondition(timeoutMillis: Long = 3000, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) { if (check()) return true; Thread.sleep(20) }
        return check()
    }

    /**
     * “全部退订”和“新客户端订阅”连续排队时，退订只能取消轮询任务。
     *
     * 修复前（48d3fde）idle() 用 `removeCallbacksAndMessages(null)` 清空整个 worker 队列，
     * 把排在前面的那条 setDemand 一起删掉：服务端租约认为已订阅，采集端 demand 却停在空，
     * 一拍都不读，且没有任何报错可查。
     */
    @Test fun unsubscribeThenSubscribeKeepsNewDemand() {
        installVehicle(VehicleCatalog.forModel("L6").first { it.key == "displaySpeed" }.propertyId)
        val collector = collector(Collections.synchronizedList(ArrayList()))
        try {
            collector.start()
            collector.setDemand(mapOf("displaySpeed" to 1000))
            collector.setDemand(emptyMap())
            collector.setDemand(mapOf("displaySpeed" to 1000))
            assertTrue("新订阅被退订吞掉了：${collector.stats()}",
                awaitCondition { collector.stats().let { it.contains("active=true") && it.contains("keys=1") } })
        } finally { collector.close(); clearService() }
    }

    /**
     * 反复更新订阅不得累积出第二条轮询链（审计第4条）。
     *
     * 这里只断言可从外部观察到的结果：更新订阅不会让采集端变成“多拍并行”。
     * 轮询链唯一性本身由 [PollScheduleTest] 在可控时钟上验证——真机节拍依赖
     * `postDelayed`，Robolectric 的后台Looper不会随 wall clock 推进，按时间窗口数拍数不可靠。
     */
    @Test fun repeatedDemandUpdatesKeepDemandStable() {
        installVehicle(VehicleCatalog.forModel("L6").first { it.key == "displaySpeed" }.propertyId)
        val collector = collector(Collections.synchronizedList(ArrayList()))
        try {
            collector.start()
            repeat(6) {
                collector.setDemand(mapOf("displaySpeed" to 250))
                Thread.sleep(60)
            }
            assertTrue("反复更新订阅后订阅集合丢失：${collector.stats()}",
                awaitCondition { collector.stats().let { it.contains("active=true") && it.contains("keys=1") } })
        } finally { collector.close(); clearService() }
    }

    /**
     * 停服/paused 期间建立的订阅必须在恢复后立即生效。
     *
     * 服务在 paused 时仍可被Binder绑定，客户端照常 attach+setVehicleFeed；恢复路径只 `start()`。
     * 若 setDemand 因 enabled=false 把需求一起丢掉，采集端 demand 停在空、客户端因“已订阅”
     * 不再重发，就会永久没有车数据。需求必须被记住，只把排程挡在 enabled 之后。
     */
    @Test fun demandSetWhileStoppedAppliesOnStart() {
        installVehicle(VehicleCatalog.forModel("L6").first { it.key == "displaySpeed" }.propertyId)
        val feeds = Collections.synchronizedList(ArrayList<Long>())
        val collector = collector(feeds)
        try {
            collector.setDemand(mapOf("displaySpeed" to 1000))
            Thread.sleep(150)                     // 让停服期间那条块先执行完
            collector.start()
            assertTrue("停服期间的订阅丢了：${collector.stats()}",
                awaitCondition { collector.stats().let { it.contains("active=true") && it.contains("keys=1") } })
            assertTrue("恢复后没有真正采集：${collector.stats()} / feeds=${feeds.size}",
                awaitCondition { feeds.isNotEmpty() })
        } finally { collector.close(); clearService() }
    }
}
