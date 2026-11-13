package com.ecarx.autoservice.ipc

import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.ecarx.autoservice.vehicle.VehicleFeed
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.auto.contract.IAutoCallback

/**
 * 主线程持有的客户端租约表。
 *
 * 调用方把身份在入口固定后传入。同一 [Lease] 对象贯穿 active、订阅和元素配置。
 * 死亡通知先挂到 Binder，再登记；回调里只投递 main，并核对仍是这一代 death。
 * 旧 recipient 迟到时不能删掉已经重新登记的 owner。本类不合并服务外侧的另一次 main 投递。
 */
internal class ClientRegistry(
    private val died: (IBinder) -> Unit,
) {
    /**
     * 一份客户端租约。
     *
     * [feed] 是 v4 按需订阅键。[feedIntervalMillis] 是这份租约自己的最小推送间隔，
     * 同时也是采集周期。[lastFeedAt] 是上次推送时刻，用来按租约节流。
     * 三者跟着租约一起消失，detach、死亡和 [close] 不用再单独清理。
     */
    data class Lease(
        val owner: IBinder,
        val uid: Int,
        val role: String,
        val callback: IAutoCallback,
        val death: IBinder.DeathRecipient,
        var active: Boolean = false,
        var feed: Set<String> = emptySet(),
        var feedIntervalMillis: Int = AutoContract.DEFAULT_FEED_INTERVAL_MS,
        var lastFeedAt: Long = 0L,
        var elements: ClusterDisplayConfiguration? = null,
    )

    private val clients = linkedMapOf<IBinder, Lease>()

    /**
     * 登记一份租约并返回同一个 [Lease] 对象。
     *
     * 最多 16 份。owner 不可重复；除 OBSERVER 外，同一角色只能有一个 owner。
     * uid 在这里写定，之后 [require] 只接受这个 uid。
     * 先 [IBinder.linkToDeath]，成功后才放入表。死亡发生在 Binder 线程，
     * 回调只把核对和 [died] 投到 main；对不上当前 death 的迟到通知直接丢掉。
     */
    fun attach(
        owner: IBinder,
        uid: Int,
        role: String,
        callback: IAutoCallback,
    ): Lease {
        require(AutoContract.roleValid(role))
        check(clients.size < 16)
        check(clients[owner] == null) { "owner_already_attached" }
        if (role != AutoContract.OBSERVER) {
            check(clients.values.none { it.role == role }) { "role_already_owned" }
        }
        lateinit var death: IBinder.DeathRecipient
        death =
            IBinder.DeathRecipient {
                Handler(Looper.getMainLooper()).post {
                    if (clients[owner]?.death === death) died(owner)
                }
            }
        owner.linkToDeath(death, 0)
        return Lease(owner, uid, role, callback, death).also { clients[owner] = it }
    }

    /** 按 owner 取出原租约对象，并确认调用 uid 仍是登记时的 uid。 */
    fun require(
        owner: IBinder,
        uid: Int,
    ): Lease = checkNotNull(clients[owner]).also { check(it.uid == uid) }

    /**
     * 移除 owner，并解除它登记时的死亡通知。
     *
     * 重复移除返回 null，不再次 unlink。unlink 失败被吞掉，租约已经从表里拿走。
     */
    fun remove(owner: IBinder): Lease? =
        clients.remove(owner)?.also { lease ->
            runCatching { owner.unlinkToDeath(lease.death, 0) }
        }

    /** 当前全部租约的快照。调用方随后改表，不会改到这份列表。 */
    fun all(): List<Lease> = clients.values.toList()

    /**
     * 停服或换车型时保留订阅意图，只把每份租约的推送节拍清零。
     *
     * 不能整份清空 [Lease.feed]。客户端本地订阅没有变化时不会重发 setVehicleFeed
     * （AutoClient 只在重连时重放）。清空之后，停止再启动或换车型会一直没有车辆数据。
     * 停服期间不会误推：发布路径对 paused/closed 直接返回，当时的信号也已清空。
     *
     * @param known 换车型时传入新目录仍可读的键；传 null 表示车型未变，订阅键原样保留。
     */
    fun retainFeeds(known: Set<String>? = null) {
        clients.values.forEach { lease ->
            if (known != null) lease.feed = VehicleFeed.retainKnown(lease.feed, known)
            lease.lastFeedAt = 0L
        }
    }

    /** 按角色找到当前那一份租约；没有时返回 null。 */
    fun role(role: String): Lease? = clients.values.firstOrNull { it.role == role }

    /**
     * 当前仍在请求原厂元素的配置。
     *
     * 只有 ELEMENTS 租约处于 active，且配置自己标明需要元素时才返回；停止或移除后消失。
     */
    fun elementDemand(): ClusterDisplayConfiguration? =
        role(AutoContract.ELEMENTS)
            ?.takeIf { it.active }
            ?.elements
            ?.takeIf { it.needsElements }

    /** 按当前 owner 逐个 [remove]。重复调用不再接触已经解绑的租约。 */
    fun close() {
        clients.keys.toList().forEach(::remove)
    }
}
