package com.ecarx.autoservice.vehicle

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import com.ecarx.autoservice.ipc.ClientRegistry
import java.util.UUID

/**
 * 车辆目录、采集、订阅缓存和需求计算的唯一所有者。
 *
 * 借用同一份 [ClientRegistry]，不另建租约表。构造只装配采集器和 [VehicleFeedDispatcher]，
 * 不启动采集，也不调用发布回调。停服、换车型和功能切换仍由服务按原顺序分别调用下面的方法。
 */
internal class ServiceVehicleSession(
    private val context: Context,
    private val clients: ClientRegistry,
    private val main: Handler,
    post: (Runnable) -> Unit,
    isFeedEnabled: () -> Boolean,
    acceptValues: (Bundle) -> Unit,
    private val sentinelDemands: () -> Map<String, Int>,
    private val onPublish: () -> Unit,
    private val rootLost: () -> Unit,
    collectorFactory: ((CollectorCallbacks) -> CollectorPort)? = null,
) {
    /** 采集器交给会话的四个回调。线程与原 [VehicleCollector] 合同一致，工厂在构造期不得调用它们。 */
    interface CollectorCallbacks {
        /** 已在 main：写入发现状态和条数后发布。 */
        fun onMetadata(
            state: String,
            count: Int,
        )

        /** 已在 main。是否已经有平台由外层判断。 */
        fun onRootLost()

        /** 已在 main：推进方控序号后发布。 */
        fun onMenu()

        /** 采集线程：必须再投到 main 才写缓存。 */
        fun onFeed(
            values: Bundle,
            at: Long,
        )
    }

    /**
     * 真实采集器的调用边界。
     *
     * 默认实现转给 [VehicleCollector]。测试只记录这些调用，不另写一套需求状态机。
     */
    interface CollectorPort {
        /** 开始按当前需求采集。重复调用由采集器自己忽略。 */
        fun start()

        /** 停止采集。会在采集线程上清掉需求，所以重算必须发生在这次停止之后。 */
        fun stop()

        /** 关闭采集器。不关闭借用的 main 或回调 worker。 */
        fun close()

        /** 写入已经合并好的键和间隔。 */
        fun setDemand(next: Map<String, Int>)

        /** 显式诊断扫描。默认仍然关闭。 */
        fun setScan(enabled: Boolean)

        /** 当前是否正在做全目录扫描。 */
        fun scanning(): Boolean

        /** 采集量累计文本，只给诊断读取。 */
        fun stats(): String
    }

    /** 同一份库存。分页和单值仍走它的同步实现。 */
    val inventory = VehicleInventory()

    /** 每个服务实例生成一次，不随快照或换车型重建。 */
    val inputEpoch: String =
        UUID
            .randomUUID()
            .toString()

    /** 发现状态。只有采集元数据回调和停服/功能切换会改。 */
    var discovery: String = "not_connected"
        private set

    /** 最近一次元数据里的属性条数。停服和功能切换清零。 */
    var discoveredCount: Int = 0
        private set

    /** 方控菜单序号。只在原菜单回调里增加。 */
    var menuSequence: Long = 0L
        private set

    /** 最近一次菜单时刻，使用 [SystemClock.elapsedRealtime]。 */
    var menuReceivedAt: Long = 0L
        private set

    private val dispatcher =
        VehicleFeedDispatcher(
            post = post,
            isEnabled = isFeedEnabled,
            acceptValues = acceptValues,
            leases = { clients.all() },
        )
    private var catalogKeysModel = ""
    private var catalogKeys: Array<String> = emptyArray()
    private val collector: CollectorPort

    init {
        val callbacks =
            object : CollectorCallbacks {
                override fun onMetadata(
                    state: String,
                    count: Int,
                ) {
                    discovery = state
                    discoveredCount = count
                    onPublish()
                }

                override fun onRootLost() = rootLost()

                override fun onMenu() {
                    menuReceivedAt = SystemClock.elapsedRealtime()
                    ++menuSequence
                    onPublish()
                }

                override fun onFeed(
                    values: Bundle,
                    at: Long,
                ) = deliverFeed(values, at)
            }
        collector = collectorFactory?.invoke(callbacks) ?: ProductionCollector(context, inventory, callbacks)
    }

    /** 当前车型。每次读 platform/model，缺省 L6。 */
    fun model(): String = context.getSharedPreferences("platform", 0).getString("model", "L6") ?: "L6"

    /**
     * 目录键加旧公共别名。
     *
     * 只在车型变化时重建数组，避免每次快照都重新分配。
     */
    fun keys(): Array<String> {
        val model = model()
        if (model != catalogKeysModel) {
            catalogKeysModel = model
            catalogKeys =
                (VehicleCatalog.forModel(model).map { it.key } + VehicleCatalog.LEGACY_ALIASES.keys).toTypedArray()
        }
        return catalogKeys
    }

    /** 目录或属性分页。catalog 为真时不带值预览，与原 inventory.page 一致。 */
    fun page(
        offset: Int,
        limit: Int,
        catalog: Boolean,
    ): Bundle = inventory.page(offset, limit, catalog)

    /** 按属性、区域和字符偏移读一条缓存值。 */
    fun value(
        propertyId: Int,
        areaId: Int,
        characterOffset: Int,
    ): Bundle = inventory.value(propertyId, areaId, characterOffset)

    /**
     * 按键读订阅缓存。
     *
     * 先按当前车型校验。空集返回空 Bundle。校验失败也返回空 Bundle，不触发采集。
     */
    fun values(keys: Array<String>?): Bundle =
        runCatching {
            val accepted = VehicleFeed.validate(keys, model())
            if (accepted.isEmpty()) Bundle() else dispatcher.select(accepted)
        }.getOrElse { Bundle() }

    /**
     * 写入一份租约的订阅并立刻补发。
     *
     * 间隔变化后把 lastFeedAt 清零，再重算需求。feed 非空才用当前缓存补一次，不读车机。
     */
    fun setFeed(
        lease: ClientRegistry.Lease,
        keys: Array<String>?,
        minIntervalMillis: Int,
        now: Long,
    ) {
        lease.feed = VehicleFeed.validate(keys, model())
        lease.feedIntervalMillis = minIntervalMillis
        lease.lastFeedAt = 0L
        recalculateDemand()
        if (lease.feed.isNotEmpty()) {
            dispatcher.replay(lease, now)
        }
    }

    /** 只转给采集器的扫描开关，不在这里读车机。 */
    fun setScan(enabled: Boolean) = collector.setScan(enabled)

    /** 当前扫描开关。 */
    fun scanning(): Boolean = collector.scanning()

    /** 采集统计文本。 */
    fun stats(): String = collector.stats()

    /** 库存条数。 */
    fun sampledCount(): Int = inventory.count()

    /** 库存里的有效条数。 */
    fun validCount(): Int = inventory.validCount()

    /** 只读库存接线元数据，不输出原始载荷。 */
    fun diagnostics() = inventory.diagnostics()

    /** 订阅缓存的浅拷贝，只给诊断把车辆值补回报告。 */
    fun feedSnapshot(): Bundle = dispatcher.snapshot()

    /**
     * 采集线程交来的一轮订阅值。
     *
     * 只投到 main，不在采集线程写缓存。main 上交给已经装配的 [VehicleFeedDispatcher]。
     */
    fun deliverFeed(
        values: Bundle,
        at: Long,
    ) {
        main.post { dispatcher.publish(values, at) }
    }

    /**
     * 把全部租约和哨兵需求合并后交给采集器。
     *
     * 键取并集，别名先译成基础键，间隔取最紧。哨兵键固定按 500ms 并入。
     * 没人订阅且哨兵没有键时，合并结果为空，采集端据此停摆。
     */
    fun recalculateDemand() {
        collector.setDemand(
            VehicleDemand.merge(
                clients.all().map { lease ->
                    lease.feed.mapTo(HashSet()) { VehicleCatalog.baseKey(it) } to lease.feedIntervalMillis
                } + listOf(sentinelDemands().keys to 500),
            ),
        )
    }

    /** 开始采集。调用方必须在需要车辆数据时才调用，构造本身不调用。 */
    fun start() = collector.start()

    /** 停止采集。采集线程随后会清掉自己的需求，所以不能在这次调用之前把新需求算进去。 */
    fun stop() = collector.stop()

    /** 换成空的订阅缓存。序号留在 dispatcher 里，不在这里归零。 */
    fun clear() = dispatcher.clear()

    /**
     * 保留订阅意图。
     *
     * [known] 为 null 时键原样保留；换车型时传入新目录仍可读的键。两种情况都把推送节拍清零。
     */
    fun retainFeeds(known: Set<String>? = null) {
        clients.retainFeeds(known)
    }

    /** 停服后的发现状态。不停止采集；调用方已经先 retain、stop、clear。 */
    fun markStopped() {
        discovery = "stopped"
        discoveredCount = 0
    }

    /** 功能切换后的发现状态。车辆仍启用时为 idle，否则 disabled。 */
    fun markFeature(vehicleEnabled: Boolean) {
        discovery = if (vehicleEnabled) "idle" else "disabled"
        discoveredCount = 0
    }

    /** 关闭采集器。不关闭服务借用的 worker。 */
    fun close() = collector.close()

    /** 默认把端口接到真实 [VehicleCollector]，回调线程与原来一致。 */
    private class ProductionCollector(
        context: Context,
        inventory: VehicleInventory,
        callbacks: CollectorCallbacks,
    ) : CollectorPort {
        private val collector =
            VehicleCollector(
                context,
                inventory,
                { state, count -> callbacks.onMetadata(state, count) },
                { callbacks.onRootLost() },
                menuPressed = { callbacks.onMenu() },
                feed = { values, at -> callbacks.onFeed(values, at) },
            )

        override fun start() = collector.start()

        override fun stop() = collector.stop()

        override fun close() = collector.close()

        override fun setDemand(next: Map<String, Int>) = collector.setDemand(next)

        override fun setScan(enabled: Boolean) = collector.setScan(enabled)

        override fun scanning(): Boolean = collector.scanning()

        override fun stats(): String = collector.stats()
    }
}
