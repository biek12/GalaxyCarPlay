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
            val baseline=source.cycle(specs,android.os.SystemClock.elapsedRealtime(),"L6")
            assertEquals(0,baseline.getJSONArray("keyEvents").length()) // 补读缓存不伪造按键边沿。
            val callback=checkNotNull(ECarXCarPropertyManagerBase.callback)
            callback.onChangeEvent(ECarXCarPropertyValue(id,1,intArrayOf(0,6,0)))
            callback.onChangeEvent(ECarXCarPropertyValue(id,1,intArrayOf(1,6,0)))
            val batch=source.cycle(specs,android.os.SystemClock.elapsedRealtime(),"L6")
            val events=batch.getJSONArray("keyEvents")
            assertEquals(2,events.length());assertEquals(0,events.getJSONObject(0).getInt("action"));assertEquals(1,events.getJSONObject(1).getInt("action"))
            assertEquals(0,source.cycle(specs,android.os.SystemClock.elapsedRealtime(),"L6").getJSONArray("keyEvents").length())
            source.close();source.cycle(specs,android.os.SystemClock.elapsedRealtime(),"L6")
            callback.onChangeEvent(ECarXCarPropertyValue(id,1,intArrayOf(0,6,0)))
            assertEquals(0,source.cycle(specs,android.os.SystemClock.elapsedRealtime(),"L6").getJSONArray("keyEvents").length())
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
            val first=source.cycle(specs,android.os.SystemClock.elapsedRealtime(),"L6")
            assertEquals(listOf(0),areas);assertEquals("纯电",first.getJSONArray("samples").getJSONObject(0).getString("value"))
            checkNotNull(ECarXCarPropertyManagerBase.callback).onChangeEvent(ECarXCarPropertyValue(id,0,packet(6)))
            val changed=source.cycle(specs,android.os.SystemClock.elapsedRealtime(),"L6")
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
            source.cycle(specs, android.os.SystemClock.elapsedRealtime(), "L6")
            assertEquals(2, reads)
            val callback = checkNotNull(ECarXCarPropertyManagerBase.callback)
            callback.onChangeEvent(ECarXCarPropertyValue(id, 1, 42))
            val next = source.cycle(specs, android.os.SystemClock.elapsedRealtime(), "L6")
            assertEquals(2, reads)
            assertEquals("42.0", next.getJSONArray("samples").getJSONObject(0).getString("value"))
            assertEquals(2, next.getJSONArray("properties").length())
            source.close()
            source.cycle(specs, android.os.SystemClock.elapsedRealtime(), "L6")
            callback.onChangeEvent(ECarXCarPropertyValue(id, 1, 999))
            val final = source.cycle(specs, android.os.SystemClock.elapsedRealtime(), "L6")
            assertEquals("10.0", final.getJSONArray("samples").getJSONObject(0).getString("value"))
            eventDuringRead = true
            val raced = source.cycle(specs, android.os.SystemClock.elapsedRealtime() + 1000, "L6")
            assertEquals("77.0", raced.getJSONArray("samples").getJSONObject(0).getString("value"))
        } finally { source.close(); clearService() }
    }
}
