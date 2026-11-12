package com.ecarx.autoservice.ipc

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.auto.contract.IAutoCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ClientRegistryTest {
    private val callback =
        object : IAutoCallback.Stub() {
            override fun onSnapshot(snapshot: Bundle) = Unit

            override fun onSurface(
                role: String,
                surface: Surface,
            ) = Unit

            override fun onProjectionState(
                role: String,
                state: String,
            ) = Unit

            // v3按需订阅回调：AIDL新增方法，Stub保持抽象，必须显式实现。
            override fun onVehicleFeed(values: Bundle) = Unit
        }

    @Test
    fun oneMapOwnerAndIndependentObserversUnlinkIdempotently() {
        val clients = ClientRegistry {}
        val map = Binder()
        val observer = Binder()
        clients.attach(map, 10001, AutoContract.MAP, callback)
        clients.attach(observer, 10002, AutoContract.OBSERVER, callback)
        assertThrows(IllegalStateException::class.java) {
            clients.attach(Binder(), 10003, AutoContract.MAP, callback)
        }
        assertThrows(IllegalStateException::class.java) { clients.require(map, 10002) }
        clients.remove(observer)
        clients.remove(observer)
        assertNotNull(clients.role(AutoContract.MAP))
        clients.close()
        assertTrue(clients.all().isEmpty())
    }

    @Test
    fun elementsHaveIndependentLeaseAndDemandDisappearsOnStopOrDetach() {
        val clients = ClientRegistry {}
        val owner = Binder()
        val lease = clients.attach(owner, 10004, AutoContract.ELEMENTS, callback)
        lease.elements = ClusterDisplayConfiguration(mode = ClusterDisplayConfiguration.OEM_ELEMENTS)
        assertNull(clients.elementDemand())
        lease.active = true
        assertNotNull(clients.elementDemand())
        assertFalse(AutoContract.producesOutput(lease.role))
        clients.attach(Binder(), 10004, AutoContract.THEME, callback)
        assertThrows(IllegalStateException::class.java) {
            clients.attach(Binder(), 10004, AutoContract.ELEMENTS, callback)
        }
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
    @Test
    fun retainFeedsKeepsIntentAndDropsKeysUnknownToNewModel() {
        val clients = ClientRegistry {}
        val map = Binder()
        val lease = clients.attach(map, 10001, AutoContract.MAP, callback)
        lease.feed = setOf("displaySpeed", "oldModelOnly")
        lease.lastFeedAt = 123L
        // 停服：键原样保留（车型没变），只把节流时刻归零，恢复后第一拍不被节流。
        clients.retainFeeds()
        assertEquals(setOf("displaySpeed", "oldModelOnly"), lease.feed)
        assertEquals(0L, lease.lastFeedAt)
        // 换车型：摘掉新目录里不存在的键，仍有效的继续订阅，客户端不必重发。
        lease.feed = setOf("displaySpeed", "oldModelOnly")
        lease.lastFeedAt = 456L
        clients.retainFeeds(setOf("displaySpeed", "gear"))
        assertEquals(setOf("displaySpeed"), lease.feed)
        assertEquals(0L, lease.lastFeedAt)
        assertNotNull(clients.role(AutoContract.MAP))
        clients.close()
    }

    /** 没有订阅者的租约不受停服/换车型影响，也不会凭空产生键。 */
    @Test
    fun retainFeedsLeavesUnsubscribedLeasesEmpty() {
        val clients = ClientRegistry {}
        val observer = Binder()
        val lease = clients.attach(observer, 10002, AutoContract.OBSERVER, callback)
        clients.retainFeeds()
        clients.retainFeeds(setOf("displaySpeed"))
        assertTrue(lease.feed.isEmpty())
        clients.close()
    }

    @Test
    fun `旧死亡通知在重新登记后不能删除新租约`() {
        val notified = mutableListOf<IBinder>()
        val clients = ClientRegistry { owner -> notified += owner }
        val owner = RecordingBinder()
        var presentAtLink = false
        owner.onLink = { presentAtLink = clients.role(AutoContract.MAP) != null }
        clients.attach(owner, 10001, AutoContract.MAP, callback)
        assertFalse(presentAtLink)
        val stale = owner.recipients.single()
        clients.remove(owner)
        assertTrue(owner.recipients.isEmpty())
        val renewed = clients.attach(owner, 10001, AutoContract.MAP, callback)
        assertFalse(presentAtLink)
        val current = owner.recipients.single()
        assertTrue(stale !== current)

        stale.binderDied()
        assertTrue(notified.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(notified.isEmpty())
        assertSame(renewed, clients.require(owner, 10001))

        current.binderDied()
        assertTrue(notified.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf<IBinder>(owner), notified)
        assertSame(renewed, clients.require(owner, 10001))
    }

    /** 只记下 linkToDeath 交给内核的 recipient，测试自己触发 binderDied。 */
    private class RecordingBinder : Binder() {
        val recipients = mutableListOf<IBinder.DeathRecipient>()
        var onLink: (() -> Unit)? = null

        override fun linkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ) {
            onLink?.invoke()
            recipients += recipient
        }

        override fun unlinkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ): Boolean = recipients.remove(recipient)
    }
}
