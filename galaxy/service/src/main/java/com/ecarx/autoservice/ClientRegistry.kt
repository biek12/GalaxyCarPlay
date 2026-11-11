package com.ecarx.autoservice

import android.os.IBinder
import android.os.Handler
import android.os.Looper
import com.ecarx.autoservice.vehicle.VehicleFeed
import com.galaxy.auto.contract.*

/** 主线程持有租约。身份由入口固定，死亡与迟到解绑不能删除下一代owner。 */
internal class ClientRegistry(private val died: (IBinder) -> Unit) {
    /**
     * feed为v4按需订阅键；feedIntervalMillis是租约自己的最小推送间隔（同时是采集周期），
     * lastFeedAt是上次推送时刻，用于按租约分别节流。三者随租约一起消失，
     * detach/死亡/close不需要额外清理。
     */
    data class Lease(val owner: IBinder, val uid: Int, val role: String, val callback: IAutoCallback,
        val death: IBinder.DeathRecipient, var active: Boolean = false, var feed: Set<String> = emptySet(),
        var feedIntervalMillis: Int = AutoContract.DEFAULT_FEED_INTERVAL_MS, var lastFeedAt: Long = 0L,
        var elements: ClusterDisplayConfiguration? = null)
    private val clients = linkedMapOf<IBinder, Lease>()
    fun attach(owner: IBinder, uid: Int, role: String, callback: IAutoCallback): Lease {
        require(AutoContract.roleValid(role)); check(clients.size < 16)
        check(clients[owner] == null) { "owner_already_attached" }
        if (role != AutoContract.OBSERVER) check(clients.values.none { it.role == role }) { "role_already_owned" }
        lateinit var death: IBinder.DeathRecipient
        death = IBinder.DeathRecipient {
            Handler(Looper.getMainLooper()).post { if (clients[owner]?.death === death) died(owner) }
        }
        owner.linkToDeath(death, 0)
        return Lease(owner, uid, role, callback, death).also { clients[owner] = it }
    }
    fun require(owner: IBinder, uid: Int): Lease = checkNotNull(clients[owner]).also { check(it.uid == uid) }
    fun remove(owner: IBinder): Lease? = clients.remove(owner)?.also { runCatching { owner.unlinkToDeath(it.death, 0) } }
    fun all() = clients.values.toList()

    /**
     * 停服/换车型时保留订阅意图，只重置每个租约的推送节流时刻。
     *
     * **不能整清 `feed`**：客户端的本地订阅状态不会因为服务端清空而改变，也就不会重发
     * `setVehicleFeed`（`AutoClient` 只在重连时重放，`ClusterSceneOutput` 键集相同就返回），
     * 于是“停止→启动”或“换车型”之后会永久没有车辆数据。
     * 停服期间也不会误推：`publishFeed` 对 paused/closed 直接返回，`signals` 也已清空。
     *
     * @param known 换车型时传新目录的键集合（目录键＋旧别名），只保留仍可读的键；
     *   传null表示车型未变（停服），订阅键原样保留。
     */
    fun retainFeeds(known: Set<String>? = null) {
        clients.values.forEach { lease ->
            if (known != null) lease.feed = VehicleFeed.retainKnown(lease.feed, known)
            lease.lastFeedAt = 0L
        }
    }
    fun role(role: String) = clients.values.firstOrNull { it.role == role }
    fun elementDemand() = role(AutoContract.ELEMENTS)?.takeIf { it.active }?.elements
        ?.takeIf { it.needsElements }
    fun close() { clients.keys.toList().forEach(::remove) }
}
