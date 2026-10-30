package com.ecarx.autoservice

import android.os.*
import android.view.Surface
import com.galaxy.auto.contract.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ClientRegistryTest {
    private val callback = object : IAutoCallback.Stub() {
        override fun onSnapshot(snapshot: Bundle) = Unit
        override fun onSurface(role: String, surface: Surface) = Unit
        override fun onProjectionState(role: String, state: String) = Unit
    }
    @Test fun oneMapOwnerAndIndependentObserversUnlinkIdempotently() {
        val clients = ClientRegistry {}
        val map = Binder(); val observer = Binder()
        clients.attach(map, 10001, AutoContract.MAP, callback)
        clients.attach(observer, 10002, AutoContract.OBSERVER, callback)
        assertThrows(IllegalStateException::class.java) { clients.attach(Binder(), 10003, AutoContract.MAP, callback) }
        assertThrows(IllegalStateException::class.java) { clients.require(map, 10002) }
        clients.remove(observer); clients.remove(observer)
        assertNotNull(clients.role(AutoContract.MAP)); clients.close(); assertTrue(clients.all().isEmpty())
    }
}
