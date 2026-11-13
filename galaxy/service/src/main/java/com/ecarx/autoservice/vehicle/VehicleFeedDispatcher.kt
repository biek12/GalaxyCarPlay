package com.ecarx.autoservice.vehicle

import android.os.Bundle
import com.ecarx.autoservice.ipc.ClientRegistry

/**
 * 车辆值缓存、feed 序号和按租约扇出的唯一所有者。
 *
 * [post]、[isEnabled]、[acceptValues]、[leases] 只保存，构造时不调用。
 * 调用方提供已经存在的投递边界；本类不创建线程，不开采集连接，也不打开新的 Binder 通道。
 * 缓存和序号只在这里：main 写入，Binder 只读 [select] 与 [snapshot]。
 * 租约生命周期、订阅键和节流时刻仍归 [ClientRegistry.Lease]，这里不复制另一份。
 * 没有 close，也不关闭调用方传入的回调 worker。
 */
internal class VehicleFeedDispatcher(
    private val post: (Runnable) -> Unit,
    private val isEnabled: () -> Boolean,
    private val acceptValues: (Bundle) -> Unit,
    private val leases: () -> List<ClientRegistry.Lease>,
) {
    /** 订阅驱动采集的最新快照。推送、补发和缓存拉取共用这一份引用。 */
    @Volatile private var signals = Bundle()

    /**
     * 按需订阅的轮次序号。
     *
     * 有目标的整轮，或一次补发，各加一。同一轮的多个租约共用这次的值。
     * [clear] 不清零，停服和换车型之后继续累加。
     */
    private var feedSequence = 0L

    /**
     * 发布一轮采集结果。由 main 调用。
     *
     * [isEnabled] 不通过时直接返回：不改缓存、不调用 [acceptValues]、不取租约、不投递。
     * 通过后先保存 [values] 原引用，再交给 [acceptValues]，然后只读一次 [leases]。
     * 只留下 feed 非空的租约，不按 active 或 role 再筛。
     * 目标为空则序号不变；非空则整轮只加一次，即使随后全部被节流或没有可发的值。
     * lastFeedAt 非 0 且距 [at] 小于该租约自己的间隔才跳过；到期先写 lastFeedAt，再发送。
     */
    fun publish(
        values: Bundle,
        at: Long,
    ) {
        if (!isEnabled()) return
        signals = values
        acceptValues(values)
        val targets = leases().filter { it.feed.isNotEmpty() }
        if (targets.isEmpty()) return
        val round = ++feedSequence
        targets.forEach { lease ->
            if (lease.lastFeedAt != 0L && at - lease.lastFeedAt < lease.feedIntervalMillis) return@forEach
            lease.lastFeedAt = at
            send(lease, values, at, round)
        }
    }

    /**
     * 用当前缓存给一份租约即时补发。
     *
     * 不读 [isEnabled]，不按间隔跳过，也不改 [ClientRegistry.Lease.lastFeedAt]。
     * 独占加一次序号。选取结果为空、没有发出回调时，这次序号仍然保留。
     */
    fun replay(
        lease: ClientRegistry.Lease,
        at: Long,
    ) {
        send(lease, signals, at, ++feedSequence)
    }

    /**
     * 按请求键从当前缓存取出子 Bundle。
     *
     * 只委托 [VehicleFeed.select]。不触发采集，不加序号，不写 at 或 sequence。
     * 键校验和错误边界留在调用方。Binder 线程可以同步调用。
     */
    fun select(keys: Set<String>): Bundle = VehicleFeed.select(signals, keys)

    /**
     * 当前缓存的顶层浅拷贝，只给已有诊断使用。
     *
     * 与原来的 `Bundle(signals)` 相同，子 Bundle 仍是缓存里的引用。
     * 调用方改返回值的顶层字段，不会写回缓存，也不能把这份副本当成下一轮推送缓存。
     */
    fun snapshot(): Bundle = Bundle(signals)

    /**
     * 换成一份空缓存。
     *
     * 不重置序号，不删除订阅，不调用 [acceptValues]，也不增加新的关闭路径。
     */
    fun clear() {
        signals = Bundle()
    }

    /**
     * 在调用线程先生成完整输出，再交给 [post]。
     *
     * [VehicleFeed.select] 已经深拷贝子 Bundle，所以异步执行时读到的是入队前的值。
     * 空结果不发送、不补 0、不补默认值。非空才写入原来的 at 与 sequence。
     * 每个回调自己捕获异常；不重试、不记日志，也不再检查服务关闭或租约是否仍在。
     */
    private fun send(
        lease: ClientRegistry.Lease,
        values: Bundle,
        at: Long,
        round: Long,
    ) {
        val out = VehicleFeed.select(values, lease.feed)
        if (out.isEmpty) return
        out.putLong("at", at)
        out.putLong("sequence", round)
        post { runCatching { lease.callback.onVehicleFeed(out) } }
    }
}
