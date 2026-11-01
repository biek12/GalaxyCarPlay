package com.shilapi.xcertplay

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.SignalStatus
import com.galaxy.auto.contract.VehicleSignal
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import java.util.Locale

/**
 * 本产品的车速/挡位来源：经 `AutoService` 的 v3 按需订阅取得 displaySpeed 与 gear，供 `$PASCD` 使用。
 *
 * 为什么不复用上游 ECARX 反射链：上游把“本机装了ECARX SDK”当成L6单位成立，并按另一车型假定无GPS。
 * 这里只消费本车车机自己采集并发布的有效值：单位必须是km/h，必须带挡位，新鲜度用字段自带
 * maxAgeMillis，不用“收到过就有效”或自编超时。位置仍由核心的 AndroidCarPlayLocationProvider 提供。
 *
 * 线程：start/stop 由 iAP2 循环线程调用，统一投递到主线程（AutoClient 要求主线程建链/关闭）；
 * acceptVehicleFeed 在主线程（Binder 回调经 AutoClient 投递）；drain/status 在 iAP2 线程，
 * 因此样本与挡位用 [lock] 保护；[lastKmh]/[lastState]/[lastError] 是给状态行看的快照，用 @Volatile。
 */
internal class GalaxyVehicleSpeed(
    context: Context,
    private val log: (String) -> Unit = {},
) : VehicleSpeedSource {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val samples = ArrayDeque<VehicleSpeedSample>()
    private var gear: VehicleGear? = null
    @Volatile private var lastKmh: Double? = null
    @Volatile private var lastState = "未启动"
    @Volatile private var lastError: String? = null
    @Volatile private var wanted = false
    /** 已经真正开始过订阅（或已收到数据）；否则 status() 无话可说，返回null。 */
    @Volatile private var everOpened = false
    /** 只在主线程读写；AutoClient 的建链与关闭都在这里串行。 */
    private var client: AutoClient? = null

    override fun start() {
        if (wanted) return
        wanted = true
        main.post { open() }
    }

    override fun stop() {
        wanted = false
        main.post { close() }
    }

    private fun open() {
        if (client != null) return
        val next = AutoClient(app, AutoContract.OBSERVER,
            onVehicleFeed = ::acceptVehicleFeed,
            onState = { state ->
                lastState = state
                log("AutoService 车辆订阅: $state")
            })
        client = next
        everOpened = true
        try {
            // 只订阅这两项：其它语义键由各自的消费者按需订阅，避免把无关数据搬进CarPlay进程。
            // 间隔取1s：$PASCD 由 iAP2 控制循环每秒取一次，按250ms订阅只会多读3倍车机。
            next.setVehicleFeed(FEED_KEYS, FEED_INTERVAL_MS)
            next.connect()
        } catch (error: Exception) {
            // 订阅键是常量，走到这里说明契约/装配被改坏；记录后让状态行可见，不静默假装已订阅。
            lastError = error.javaClass.simpleName
            log("AutoService 车辆订阅失败: ${error.javaClass.simpleName}")
        }
    }

    private fun close() {
        client?.close()
        client = null
        lastState = "已停止"
        synchronized(lock) { samples.clear(); gear = null }
    }

    /**
     * 单轮订阅值入口。可见性为 internal 是为了让测试直接投递 Bundle，不必起 Binder。
     * 返回前不修改调用方 Bundle。
     */
    internal fun acceptVehicleFeed(values: Bundle) {
        // 有数据到达就意味着状态行有话可说（含“全是无效值”这种情况）。
        everOpened = true
        val now = SystemClock.elapsedRealtime()
        // 键缺失＝本轮无值，不能填0；键出现但值不可用＝明确未知，挡位不沿用上一轮。
        val gearPresent = values.containsKey(AutoContract.SIGNAL_GEAR)
        if (gearPresent) {
            val mapped = gearOf(values.getBundle(AutoContract.SIGNAL_GEAR), now)
            synchronized(lock) { gear = mapped }
            if (mapped == null) return
        }
        val mps = metersPerSecond(values.getBundle(AutoContract.SIGNAL_DISPLAY_SPEED), now) ?: return
        val current = synchronized(lock) {
            val known = gear ?: return
            samples.addLast(VehicleSpeedSample(now, mps))
            // 有界队列：iAP2 读取端停顿时丢弃最旧样本，宁可少一段也不无限增长。
            while (samples.size > MAX_SAMPLES) samples.removeFirst()
            known
        }
        lastKmh = mps * 3.6
        lastState = "有效值 ${current.letter}"
    }

    override fun drain(): VehicleSpeedReading? = synchronized(lock) {
        val current = gear ?: return null
        if (samples.isEmpty()) return null
        val taken = samples.toList()
        samples.clear()
        VehicleSpeedReading(current, taken)
    }

    override fun status(): String? {
        if (!everOpened) return null
        val speed = lastKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "无"
        val current = synchronized(lock) { gear?.letter?.toString() ?: "未知" }
        val pending = synchronized(lock) { samples.size }
        return "subscription=$lastState gear=$current lastSpeed=$speed samples=$pending" +
            (lastError?.let { " error=$it" }.orEmpty())
    }

    /** 只有 VALID 且按字段自带 maxAgeMillis 仍新鲜的值才可用；STALE/UNAVAILABLE/REJECTED 一律不用。 */
    private fun usable(source: Bundle?, now: Long): VehicleSignal? {
        val signal = source?.let { VehicleSignal.parse(it) } ?: return null
        if (signal.status != SignalStatus.VALID) return null
        if (signal.effectiveStatus(now) != SignalStatus.VALID) return null
        return signal
    }

    /** 单位必须是km/h；要求有限且落在0..300，超量程按不可用丢弃，不截断成边界值。 */
    private fun metersPerSecond(source: Bundle?, now: Long): Double? {
        val signal = usable(source, now) ?: return null
        if (signal.unit != UNIT_KMH) return null
        val kmh = signal.value?.toDoubleOrNull() ?: return null
        if (!kmh.isFinite() || kmh < 0.0 || kmh > MAX_KMH) return null
        return kmh / 3.6
    }

    /** P/R/N/D 之外的挡位不猜：PASCD 必须带挡位，映射不出就整轮作废。 */
    private fun gearOf(source: Bundle?, now: Long): VehicleGear? = when (usable(source, now)?.value?.trim()) {
        "P" -> VehicleGear.PARK
        "R" -> VehicleGear.REVERSE
        "N" -> VehicleGear.NEUTRAL
        "D" -> VehicleGear.DRIVE
        else -> null
    }

    internal companion object {
        val FEED_KEYS: List<String> = listOf(AutoContract.SIGNAL_DISPLAY_SPEED, AutoContract.SIGNAL_GEAR)
        /** iAP2 位置控制循环是1Hz，订阅间隔与消费节拍对齐，避免4倍采集。 */
        const val FEED_INTERVAL_MS = 1000
        const val MAX_SAMPLES = 32
        const val MAX_KMH = 300.0
        const val UNIT_KMH = "km/h"
    }
}