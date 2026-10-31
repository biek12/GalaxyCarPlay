package com.ecarx.autoservice.vehicle

import android.os.Binder
import ecarx.car.IECarXCar
import ecarx.car.hardware.property.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowServiceManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VehicleEventsTest {
    private fun clearService() {
        val field = ShadowServiceManager::class.java.getDeclaredField("binderServices").apply { isAccessible = true }
        (field.get(null) as MutableMap<*, *>).remove("ecarxcar_service")
    }
    private fun keys(specs: List<SignalSpec>): Set<String> = specs.mapTo(HashSet()) { it.key }

    /**
     * v4把“订阅键”与“本拍到期键”分开：read＝本拍到期读一次，cached＝本拍未到期只用缓存。
     * 两者都只能看到订阅键；未订阅的目录键不读取、不注册。
     */
    private fun EcarxVehicleSource.read(specs: List<SignalSpec>) =
        cycle(specs, android.os.SystemClock.elapsedRealtime(), "L6", keys(specs), keys(specs))
    private fun EcarxVehicleSource.cached(specs: List<SignalSpec>) =
        cycle(specs, android.os.SystemClock.elapsedRealtime(), "L6", keys(specs), emptySet())
    private fun EcarxVehicleSource.readLater(specs: List<SignalSpec>, offset: Long) =
        cycle(specs, android.os.SystemClock.elapsedRealtime() + offset, "L6", keys(specs), keys(specs))

    @Test fun menuEdgesSurviveOneSnapshotAndLateCallbacksCannotReachNewSession(){
        val root=Binder().apply {attachInterface(null,"ecarx.car.IECarXCar")}
        val property=Binder().apply {attachInterface(null,"ecarx.car.hardware.property.IECarXCarProperty")}
        val id=289475088
        IECarXCar.Stub.service=object:IECarXCar{override fun asBinder()=root;override fun getCarService(name:String)=property}
        IECarXCarProperty.Stub.service=object:IECarXCarProperty{
            override fun getProperty(id:Int,area:Int)=ECarXCarPropertyValue(id,area,intArrayOf(1,6,0))
            override fun getPropertyList()=listOf(TestConfig(id,intArrayOf(1)))
        }
        ShadowServiceManager.addBinderService("ecarxcar_service",IECarXCar::class.java,IECarXCar.Stub.service)
        val source=EcarxVehicleSource();val specs=listOf(VehicleCatalog.forModel("L6").first {it.propertyId==id})
        try{
            val baseline=source.read(specs)
            assertEquals(0,baseline.getJSONArray("keyEvents").length()) // 补读缓存不伪造按键边沿。
            val callback=checkNotNull(ECarXCarPropertyManagerBase.callback)
            callback.onChangeEvent(ECarXCarPropertyValue(id,1,intArrayOf(0,6,0)))
            callback.onChangeEvent(ECarXCarPropertyValue(id,1,intArrayOf(1,6,0)))
            // 键沿事件与“本拍是否到期”无关：未到期的拍也必须排空事件。
            val batch=source.cached(specs)
            val events=batch.getJSONArray("keyEvents")
            assertEquals(2,events.length());assertEquals(0,events.getJSONObject(0).getInt("action"));assertEquals(1,events.getJSONObject(1).getInt("action"))
            assertEquals(0,source.cached(specs).getJSONArray("keyEvents").length())
            source.close();source.read(specs)
            callback.onChangeEvent(ECarXCarPropertyValue(id,1,intArrayOf(0,6,0)))
            assertEquals(0,source.cached(specs).getJSONArray("keyEvents").length())
        }finally{source.close();clearService()}
    }
    @Test fun driveModeReadsAreaZeroPaAndProcessesLiveChanges(){
        val root=Binder().apply {attachInterface(null,"ecarx.car.IECarXCar")}
        val property=Binder().apply {attachInterface(null,"ecarx.car.hardware.property.IECarXCarProperty")}
        IECarXCar.Stub.service=object: IECarXCar {override fun asBinder()=root;override fun getCarService(name: String)=property}
        val id=561023639;val areas=mutableListOf<Int>()
        fun packet(mode: Int)=java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(mode).putInt(0).putInt(0).array()
        IECarXCarProperty.Stub.service=object: IECarXCarProperty {
            override fun getProperty(id: Int,area: Int): ECarXCarPropertyValue {areas.add(area);return ECarXCarPropertyValue(id,area,packet(2))}
            override fun getPropertyList()=emptyList<TestConfig>()
        }
        ShadowServiceManager.addBinderService("ecarxcar_service",IECarXCar::class.java,IECarXCar.Stub.service)
        val source=EcarxVehicleSource();val specs=VehicleCatalog.forModel("L6").filter {it.key=="driveMode"}
        try{
            val first=source.read(specs)
            assertEquals(listOf(0),areas);assertEquals("纯电",first.getJSONArray("samples").getJSONObject(0).getString("value"))
            checkNotNull(ECarXCarPropertyManagerBase.callback).onChangeEvent(ECarXCarPropertyValue(id,0,packet(6)))
            // 未到期的拍不得重新发起读取：值必须来自事件回调。
            val changed=source.cached(specs)
            assertEquals("智能",changed.getJSONArray("samples").getJSONObject(0).getString("value"));assertEquals(listOf(0),areas)
        }finally{source.close();clearService()}
    }
    @Test fun liveEventsReplacePollingWithoutDroppingCatalogAreasAndOldCallbacksCannotCrossEpoch() {
        val root = Binder().apply { attachInterface(null, "ecarx.car.IECarXCar") }
        val property = Binder().apply { attachInterface(null, "ecarx.car.hardware.property.IECarXCarProperty") }
        IECarXCar.Stub.service = object : IECarXCar { override fun asBinder() = root; override fun getCarService(name: String) = property }
        val id = 557874774
        var reads = 0
        var eventDuringRead = false
        IECarXCarProperty.Stub.service = object : IECarXCarProperty {
            override fun getProperty(id: Int, area: Int): ECarXCarPropertyValue {
                ++reads
                if (eventDuringRead && area == 1) ECarXCarPropertyManagerBase.callback?.onChangeEvent(ECarXCarPropertyValue(id, area, 77))
                return ECarXCarPropertyValue(id, area, 10)
            }
            override fun getPropertyList() = listOf(TestConfig(id, intArrayOf(1, 2)))
        }
        ShadowServiceManager.addBinderService("ecarxcar_service", IECarXCar::class.java, IECarXCar.Stub.service)
        val source = EcarxVehicleSource()
        val specs = listOf(SignalSpec("speed", id, "km/h"))
        try {
            source.read(specs)
            // 只读订阅键的语义属性（area 1）一次；不再有32项轮询窗口补齐第二区域。
            assertEquals(1, reads)
            val callback = checkNotNull(ECarXCarPropertyManagerBase.callback)
            callback.onChangeEvent(ECarXCarPropertyValue(id, 1, 42))
            val next = source.cached(specs)
            assertEquals(1, reads)
            assertEquals("42.0", next.getJSONArray("samples").getJSONObject(0).getString("value"))
            // 未到期就没有读取，也就没有raw行；raw只反映本拍真正读过的属性。
            assertEquals(0, next.getJSONArray("properties").length())
            source.close()
            source.read(specs)
            assertEquals(2, reads)
            callback.onChangeEvent(ECarXCarPropertyValue(id, 1, 999))
            val final = source.cached(specs)
            assertEquals("10.0", final.getJSONArray("samples").getJSONObject(0).getString("value"))
            eventDuringRead = true
            val raced = source.readLater(specs, 1000)
            assertEquals("77.0", raced.getJSONArray("samples").getJSONObject(0).getString("value"))
        } finally { source.close(); clearService() }
    }

    /**
     * v4核心断言：未订阅的目录键一次都不读。
     * 退回“全量轮询”会立刻失败——这是把常驻172次/s的读取压到订阅规模的关键约束。
     */
    @Test fun unsubscribedCatalogKeysAreNeverRead() {
        val root = Binder().apply { attachInterface(null, "ecarx.car.IECarXCar") }
        val property = Binder().apply { attachInterface(null, "ecarx.car.hardware.property.IECarXCarProperty") }
        IECarXCar.Stub.service = object : IECarXCar { override fun asBinder() = root; override fun getCarService(name: String) = property }
        val speedId = VehicleCatalog.forModel("L6").first { it.key == "displaySpeed" }.propertyId
        val socId = VehicleCatalog.forModel("L6").first { it.key == "soc" }.propertyId
        val reads = mutableListOf<Int>()
        IECarXCarProperty.Stub.service = object : IECarXCarProperty {
            override fun getProperty(id: Int, area: Int): ECarXCarPropertyValue { reads += id; return ECarXCarPropertyValue(id, area, 10) }
            override fun getPropertyList() = listOf(TestConfig(speedId, intArrayOf(1)), TestConfig(socId, intArrayOf(1)))
        }
        ShadowServiceManager.addBinderService("ecarxcar_service", IECarXCar::class.java, IECarXCar.Stub.service)
        val source = EcarxVehicleSource()
        val all = VehicleCatalog.forModel("L6")
        val onlySpeed = setOf("displaySpeed")
        try {
            val first = source.cycle(all, android.os.SystemClock.elapsedRealtime(), "L6", onlySpeed, onlySpeed)
            assertEquals(listOf(speedId), reads)
            assertEquals(1, first.getJSONArray("samples").length())
            assertEquals("displaySpeed", first.getJSONArray("samples").getJSONObject(0).getString("key"))
            // 再跑一拍未到期：仍然零读取。
            reads.clear()
            source.cycle(all, android.os.SystemClock.elapsedRealtime(), "L6", onlySpeed, emptySet())
            assertTrue(reads.isEmpty())
        } finally { source.close(); clearService() }
    }
}