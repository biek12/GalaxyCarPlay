package com.shilapi.xcertplay.steering

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 沿星枢SteeringTransportProtocolTest移植：合成Binder，不接车、不声称独占原厂按键。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class AutolinkSteeringTransportTest {
    private class Remote(private val reject: Boolean = false) : Binder() {
        lateinit var callback: IBinder
        val codes = CopyOnWriteArrayList<Int>()
        init { attachInterface(null, AutolinkSteeringTransport.SERVICE) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(AutolinkSteeringTransport.SERVICE)
            val received = requireNotNull(data.readStrongBinder())
            assertEquals(0, data.dataAvail())
            codes += code
            if (code == 1) callback = received else { assertEquals(2, code); assertSame(callback, received) }
            if (reject && code == 1) return false
            requireNotNull(reply).writeNoException()
            return true
        }
        fun send(code: Int, key: Int): Int {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(AutolinkSteeringTransport.CALLBACK)
                data.writeInt(key)
                assertTrue(callback.transact(code, data, reply, 0))
                reply.readException()
                reply.readInt()
            } finally { data.recycle(); reply.recycle() }
        }
    }

    private class BindingContext(private val remote: Remote) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val unbound = CountDownLatch(1)
        init {
            val info = ResolveInfo().apply { serviceInfo = ServiceInfo().apply {
                packageName = AutolinkSteeringTransport.PACKAGE
                name = "com.autolink.hardkeyservice.FixtureService"
            } }
            shadowOf(packageManager).addResolveInfoForIntent(
                Intent(AutolinkSteeringTransport.ACTION).setPackage(AutolinkSteeringTransport.PACKAGE), info)
        }
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            assertEquals(AutolinkSteeringTransport.PACKAGE, intent.component?.packageName)
            assertEquals(AutolinkSteeringTransport.ACTION, intent.action)
            connection.onServiceConnected(requireNotNull(intent.component), remote)
            return true
        }
        override fun unbindService(connection: ServiceConnection) { unbound.countDown() }
    }

    @Test fun exactRegisterCallbackAndUnregisterOrderAlwaysRepliesObservation() {
        val remote = Remote()
        val context = BindingContext(remote)
        val registered = CountDownLatch(1)
        val received = CountDownLatch(3)
        val events = CopyOnWriteArrayList<SteeringKeyEvent>()
        val session = AutolinkSteeringTransport(context).subscribe({ events += it; received.countDown() }, { state, _ ->
            if (state == SteeringConnection.REGISTERED) registered.countDown()
        })
        try {
            assertTrue(registered.await(3, TimeUnit.SECONDS))
            assertEquals(0, remote.send(1, 73))
            assertEquals(0, remote.send(2, 73))
            assertEquals(0, remote.send(3, 74))
            assertTrue(received.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(SteeringEdge.PRESS, SteeringEdge.RELEASE, SteeringEdge.CANCEL), events.map { it.edge })
            assertEquals(listOf(73, 73, 74), events.map { it.code })
        } finally { session.close(); session.close() }
        assertTrue(context.unbound.await(3, TimeUnit.SECONDS))
        assertEquals(listOf(1, 2), remote.codes)
        assertEquals(0, remote.send(1, 99))
        assertEquals(3, events.size)
    }

    @Test fun rejectedRegistrationNeverBecomesRegisteredAndUnregistersPartialBinding() {
        val remote = Remote(reject = true)
        val context = BindingContext(remote)
        val rejected = CountDownLatch(1)
        val states = CopyOnWriteArrayList<SteeringConnection>()
        val session = AutolinkSteeringTransport(context).subscribe({}, { state, detail ->
            states += state
            if (detail.startsWith("方控注册失败")) rejected.countDown()
        })
        try {
            assertTrue(rejected.await(3, TimeUnit.SECONDS))
            assertFalse(states.contains(SteeringConnection.REGISTERED))
        } finally { session.close() }
        assertTrue(context.unbound.await(3, TimeUnit.SECONDS))
        assertEquals(listOf(1, 2), remote.codes)
    }
}
