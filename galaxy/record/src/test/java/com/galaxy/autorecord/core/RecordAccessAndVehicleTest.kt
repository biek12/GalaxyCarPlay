package com.galaxy.autorecord.core

import android.os.Bundle
import com.galaxy.auto.contract.SignalStatus
import com.galaxy.auto.contract.VehicleSignal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordAccessAndVehicleTest {
    @Test fun `授权是串行屏障且必须通过本进程复核才可拉数据`() {
        var permission = false
        var requests = 0
        var starts = 0
        var callback: ((Boolean,String)->Unit)? = null
        val gate = RecordAccessGate({permission}, { requests++; callback=it }, { starts++ }, {})
        gate.begin(); gate.begin()
        assertEquals(1, requests); assertEquals(0, starts)
        callback!!(true, "")
        assertEquals(RecordAccessGate.State.BLOCKED, gate.state)
        assertEquals(0, starts)
        gate.begin(); permission=true; callback!!(true, "")
        assertEquals(1, starts)
        gate.begin(); assertEquals(2, requests)
    }
    @Test fun `断线或超时后的迟到授权不能启动资源`() {
        var permission=false
        var starts=0
        var old: ((Boolean,String)->Unit)?=null
        val gate=RecordAccessGate({permission}, {old=it}, {starts++}, {})
        gate.begin(); gate.reset(); permission=true; old!!(true, "")
        assertEquals(0, starts)
        permission=false; gate.begin(); gate.block("timeout");permission=true;old!!(true, "")
        assertEquals(0, starts)
        gate.close();gate.begin();assertEquals(RecordAccessGate.State.CLOSED,gate.state)
    }
    @Test fun `已有权限不重复申请`() {
        var starts=0
        RecordAccessGate({true},{fail("不应申请")},{starts++},{}).begin()
        assertEquals(1,starts)
    }
    @Test fun `速度档位经真实契约读取并按时效失效断线清空`() {
        var now=1000L
        val model=AutoVehicleFeed {now}
        model.connected(true)
        model.accept(Bundle().apply {
            putBundle("displaySpeed", VehicleSignal("displaySpeed","0","km/h","fixture",1,1000,null,1,SignalStatus.VALID,2000).bundle(now))
            putBundle("gear", VehicleSignal("gear","P","raw","fixture",2,1000,null,1,SignalStatus.VALID,2000).bundle(now))
        })
        assertEquals("0",model.snapshot().display(VehicleKey.SPEED))
        assertEquals("P",model.snapshot().display(VehicleKey.GEAR))
        now=3001
        assertEquals(VehicleValueStatus.STALE,model.snapshot().value(VehicleKey.GEAR).status)
        assertEquals("—",model.snapshot().display(VehicleKey.GEAR))
        model.connected(false,"断线")
        assertEquals(VehicleValueStatus.UNAVAILABLE,model.snapshot().value(VehicleKey.SPEED).status)
        assertEquals("—",model.snapshot().display(VehicleKey.SPEED))
    }
    @Test fun `最大记录集仍满足订阅上限且未知属性不猜映射`() {
        val all=VehicleKey.entries.flatMap(RecordVehicleAdapter::keys).distinct()
        assertTrue(all.size <= com.galaxy.auto.contract.AutoContract.MAX_FEED_KEYS)
        assertTrue(RecordVehicleAdapter.keys(VehicleKey.AUTO_HOLD).isEmpty())
        assertTrue(RecordVehicleAdapter.keys(VehicleKey.ODOMETER).isEmpty())
        assertEquals(listOf("property.10042"),RecordVehicleAdapter.keys(VehicleKey.REAR_FOG))
    }
    @Test fun `小卷自动配额不能超过一半也不能无声放大到一GB`() {
        assertEquals(256L*1024*1024,RecordSettings().effectiveQuotaBytes(512L*1024*1024))
    }
    @Test fun `关闭仪表surface不影响记录仪车辆连接`() {
        assertFalse(AutoServiceRecordFeed.linkFailure("surface_closed"))
        assertTrue(AutoServiceRecordFeed.linkFailure("service_disconnected"))
    }
}
