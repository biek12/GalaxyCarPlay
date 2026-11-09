package com.galaxy.autorecord.ui

import android.content.ComponentName
import android.os.*
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 退出时迟到的 ServiceConnection 不能复活客户端，也不能把 detach 发给新建的错误 owner。 */
@RunWith(RobolectricTestRunner::class)
class RecordClientCloseTest {
    @Test fun `关闭后的迟到连接不替换owner且准确释放原订阅租约`() {
        val app=RuntimeEnvironment.getApplication()
        val attached=AtomicReference<IBinder>()
        val detached=AtomicReference<IBinder>()
        val reading=CountDownLatch(1);val release=CountDownLatch(1)
        var ready=false
        lateinit var binder: Binder
        val api=Proxy.newProxyInstance(IAutoService::class.java.classLoader,arrayOf(IAutoService::class.java)) {_,method,args->
            when(method.name) {
                "asBinder"->binder
                "getProtocolVersion"->AutoContract.VERSION
                "attach"->{attached.set(args!![0] as IBinder);null}
                "detach"->{detached.set(args!![0] as IBinder);null}
                "getVehicleValues"->{reading.countDown();check(release.await(5,TimeUnit.SECONDS));Bundle()}
                "toString"->"CloseTestService"
                "hashCode"->1
                "equals"->false
                else->null
            }
        } as IAutoService
        binder=Binder().apply {attachInterface(api,"com.galaxy.auto.contract.IAutoService")}
        val name=ComponentName(AutoContract.SERVICE_PACKAGE,AutoContract.SERVICE_CLASS)
        val shadow=Shadows.shadowOf(app)
        shadow.setComponentNameAndServiceForBindService(name,binder)
        val client=AutoClient(app,AutoContract.OBSERVER,onReady={ready=true})
        try {
            client.connect();waitFor {ready}
            val connection=shadow.boundServiceConnections.single()
            client.getVehicleValues(listOf("gear")) {}
            waitFor {reading.count==0L}
            client.close()
            connection.onServiceConnected(name,binder)
            release.countDown()
            waitFor {detached.get()!=null}
            assertSame(attached.get(),detached.get())
        } finally {release.countDown();client.close()}
    }
    private fun waitFor(predicate:()->Boolean) {
        val deadline=System.nanoTime()+5_000_000_000L
        while(!predicate() && System.nanoTime()<deadline) {Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
        assertTrue(predicate())
    }
}
