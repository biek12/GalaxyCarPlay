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
        // v3按需订阅回调：AIDL新增方法，Stub保持抽象，必须显式实现。
        override fun onVehicleFeed(values: Bundle) = Unit
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

    @Test fun elementsHaveIndependentLeaseAndDemandDisappearsOnStopOrDetach() {
        val clients = ClientRegistry {}
        val owner = Binder()
        val lease = clients.attach(owner, 10004, AutoContract.ELEMENTS, callback)
        lease.elements = ClusterDisplayConfiguration(mode = ClusterDisplayConfiguration.OEM_ELEMENTS)
        assertNull(clients.elementDemand())
        lease.active = true
        assertNotNull(clients.elementDemand())
        assertFalse(AutoContract.producesOutput(lease.role))
        clients.attach(Binder(), 10004, AutoContract.THEME, callback)
        assertThrows(IllegalStateException::class.java) { clients.attach(Binder(), 10004, AutoContract.ELEMENTS, callback) }
        lease.active = false
        assertNull(clients.elementDemand())
        lease.active = true
        clients.remove(owner)
        assertNull(clients.elementDemand())
        assertNotNull(clients.role(AutoContract.THEME))
        clients.close()
    }

    /**
     * 停服/换车型只重置节流时刻、保留订阅意图。
     *
     * 整清 lease.feed 会让客户端与服务端认知分叉：客户端本地状态没变就不重发
     * setVehicleFeed（AutoClient 只在重连时重放、Cluster 键集相同就返回），
     * “停止→启动”“换车型”之后会永久没有车辆数据。
     */
    @Test fun retainFeedsKeepsIntentAndDropsKeysUnknownToNewModel() {
        val clients = ClientRegistry {}
        val map = Binder()
        val lease = clients.attach(map, 10001, AutoContract.MAP, callback)
        lease.feed = setOf("displaySpeed", "oldModelOnly"); lease.lastFeedAt = 123L
        // 停服：键原样保留（车型没变），只把节流时刻归零，恢复后第一拍不被节流。
        clients.retainFeeds()
        assertEquals(setOf("displaySpeed", "oldModelOnly"), lease.feed)
        assertEquals(0L, lease.lastFeedAt)
        // 换车型：摘掉新目录里不存在的键，仍有效的继续订阅，客户端不必重发。
        lease.feed = setOf("displaySpeed", "oldModelOnly"); lease.lastFeedAt = 456L
        clients.retainFeeds(setOf("displaySpeed", "gear"))
        assertEquals(setOf("displaySpeed"), lease.feed)
        assertEquals(0L, lease.lastFeedAt)
        assertNotNull(clients.role(AutoContract.MAP))
        clients.close()
    }

    /** 没有订阅者的租约不受停服/换车型影响，也不会凭空产生键。 */
    @Test fun retainFeedsLeavesUnsubscribedLeasesEmpty() {
        val clients = ClientRegistry {}
        val observer = Binder()
        val lease = clients.attach(observer, 10002, AutoContract.OBSERVER, callback)
        clients.retainFeeds(); clients.retainFeeds(setOf("displaySpeed"))
        assertTrue(lease.feed.isEmpty())
        clients.close()
    }
}
