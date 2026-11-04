package com.galaxy.autorecord.ui

import android.Manifest
import android.content.ComponentName
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import com.galaxy.auto.contract.*
import com.galaxy.autorecord.core.UnavailableCameraSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/** 真实 Activity + AutoClient 租约，替换的只有远端服务；验证授权前没有订阅或相机资源。 */
@RunWith(RobolectricTestRunner::class)
class RecordPermissionSequenceTest {
    @Test fun `首次启动必须等AutoService回调与本地授权复核后才能订阅及开相机`() {
        val app=RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        val response=java.util.concurrent.atomic.AtomicReference<IAutoResult?>()
        val calls=CopyOnWriteArrayList<String>()
        val observer=java.util.concurrent.atomic.AtomicReference<IAutoCallback?>()
        lateinit var binder: Binder
        val snapshot=Bundle().apply {
            putInt("version",AutoContract.VERSION);putStringArray("vehicleKeys",arrayOf("displaySpeed","gear"))
            putBoolean("automatic",true)
        }
        val api=Proxy.newProxyInstance(IAutoService::class.java.classLoader,arrayOf(IAutoService::class.java)) { _,method,args ->
            when(method.name) {
                "asBinder" -> binder
                "getProtocolVersion" -> AutoContract.VERSION
                "getSnapshot" -> snapshot
                "getVehicleValues" -> Bundle()
                "attach" -> { observer.set(args!![2] as IAutoCallback); observer.get()!!.onSnapshot(snapshot);null }
                "requestClientPermissions" -> { calls.add("request"); response.set(args!![2] as IAutoResult);null }
                "setVehicleFeed" -> { if ((args!![1] as Array<*>).isNotEmpty()) calls.add("subscribe");null }
                "toString" -> "FakeAutoService"
                "hashCode" -> 1
                "equals" -> false
                else -> null
            }
        } as IAutoService
        binder=Binder().apply { attachInterface(api,"com.galaxy.auto.contract.IAutoService") }
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(AutoContract.SERVICE_PACKAGE,AutoContract.SERVICE_CLASS),binder)
        val controller=Robolectric.buildActivity(RecordActivity::class.java).setup()
        val activity=controller.get()
        try {
            waitFor {response.get()!=null}
            repeat(3) {activity.requestCameraPermission(force=true)}
            repeat(5) {Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
            assertEquals(listOf("request"),calls.toList())
            assertFalse(activity.cameraAccessReady)
            assertTrue(activity.cameraSource is UnavailableCameraSource)
            // 服务说成功，但本进程权限仍没有同步：不能越过屏障。
            response.get()!!.onResult(Bundle().apply {putBoolean("granted",true)})
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertFalse(activity.cameraAccessReady)
            assertFalse(calls.contains("subscribe"))
            activity.requestCameraPermission(force=true)
            waitFor {calls.count {it=="request"}==2}
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
            response.get()!!.onResult(Bundle().apply {putBoolean("granted",true)})
            waitFor {activity.cameraAccessReady && calls.contains("subscribe")}
            assertEquals("request",calls.first())
            assertEquals(280,activity.resources.configuration.densityDpi)
            // 推送直接到界面；不推进1000ms ticker，避免回到“缓存已变但界面等下一秒”的旧行为。
            val now=android.os.SystemClock.elapsedRealtime()
            observer.get()!!.onVehicleFeed(Bundle().apply {
                putBundle("displaySpeed",VehicleSignal("displaySpeed","17.0","km/h","fixture",1,now,null,1,SignalStatus.VALID).bundle(now))
                putBundle("gear",VehicleSignal("gear","D","raw","fixture",2,now,null,1,SignalStatus.VALID).bundle(now))
            })
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("17.0",activity.vehicle.display(com.galaxy.autorecord.core.VehicleKey.SPEED))
            fun texts(view: android.view.View): List<String> = when(view) {
                is android.widget.TextView -> listOf(view.text.toString())
                is android.view.ViewGroup -> (0 until view.childCount).flatMap {texts(view.getChildAt(it))}
                else -> emptyList()
            }
            assertTrue(texts(activity.currentPageView()!!).contains("17.0"))
        } finally {controller.pause().stop().destroy()}
    }
    private fun waitFor(predicate:()->Boolean) {
        val deadline=System.nanoTime()+5_000_000_000L
        while(!predicate() && System.nanoTime()<deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("本机异步结果未完成",predicate())
    }
}
