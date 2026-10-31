package com.ecarx.autoservice.vehicle

import android.content.Context
import android.os.*
import com.ecarx.autoservice.platform.RootServiceClient
import com.galaxy.auto.contract.*
import org.json.JSONObject
import java.io.Closeable

/**
 * v4订阅驱动的串行采集器：只采当前订阅键，节拍取订阅里最紧的间隔。
 *
 * 三条硬规则：
 * 1. **没人订阅就不采集**——`demand`为空且诊断扫描关闭时，一台一拍都不读车机，只保留线程等待；
 * 2. 每拍只读到期键（[VehicleDemand.due]），未到期的键直接复用缓存；订阅键变化立即重算集合；
 * 3. 全目录“读尽读”能力保留在显式诊断扫描（[setScan]）里，默认关闭，不改原车、不写属性。
 *
 * 采集线程只把整理好的快照交出去：值走[feed]，元数据（发现状态/条数）走[deliver]。
 */
internal class VehicleCollector(
    private val context: Context,
    val inventory: VehicleInventory,
    /** 元数据（发现状态, 已采条数）；车辆值已改由[feed]单独下发，这里不再带整包。 */
    private val deliver: (String, Int) -> Unit,
    private val rootLost: () -> Unit,
    private val menuPressed: ()->Unit = {},
    /** 订阅键的最新快照（只含订阅键）与采集时刻；订阅为空时不调用。 */
    private val feed: (Bundle, Long) -> Unit = { _, _ -> },
) : Closeable {
    // 线程按需创建：车辆功能关闭或长期无人订阅时不该留一条常驻Looper线程。
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private fun startWorker(): Handler {
        worker?.let { return it }
        val created = HandlerThread("AutoService-vehicle").apply { start() }
        thread = created
        return Handler(created.looper).also { worker = it }
    }
    private fun post(block: () -> Unit) { startWorker().post(block) }
    private val main = Handler(Looper.getMainLooper())
    private val menu=com.ecarx.autoservice.input.ClusterMenuInput()
    private val source = EcarxVehicleSource()
    private val demand = VehicleDemand()
    @Volatile private var generation = 0
    private var modeGeneration = ""
    private var enabled = false
    @Volatile private var scanEnabled = false
    private var ticking = false
    private var discovery = "idle"
    private var lastMetaSignature = ""
    private var lastDeliveredAt = Long.MIN_VALUE
    // 采集量计数：只累加总量，由dump读取，用来和整改前的读数做前后对比，不做自采样。
    @Volatile private var tickTotal = 0L
    @Volatile private var readTotal = 0L
    @Volatile private var bundleTotal = 0L
    @Volatile private var feedTotal = 0L
    private val prefs = context.getSharedPreferences("platform", 0)
    fun start() {
        if (enabled) return
        enabled = true
        tick()
    }

    /**
     * 订阅集合变化（主线程调用）。空Map表示所有人退订：采集立刻停摆，不再空转读车机。
     * 传入的是**已经按租约合并好**的“键->最小间隔”表。
     */
    fun setDemand(next: Map<String, Int>) {
        post {
            demand.assign(next)
            if (next.isNotEmpty()) demand.reset()
            if (enabled && (demand.active || scanEnabled)) tick() else idle()
        }
    }
    /** 显式全目录诊断扫描；默认关。开启时按250ms推进32项/拍，与订阅无关。 */
    fun setScan(value: Boolean) {
        post {
            if (scanEnabled == value) return@post
            scanEnabled = value
            if (!enabled) return@post
            if (demand.active || scanEnabled) { if (scanEnabled) demand.reset(); tick() } else idle()
        }
    }
    fun scanning(): Boolean = scanEnabled

    /** 采集量累计值（诊断用，只增不减）。 */
    fun stats(): String =
        "ticks=$tickTotal reads=$readTotal bundles=$bundleTotal feeds=$feedTotal scan=$scanEnabled active=${demand.active} keys=${demand.keys().size}"

    /** 没有订阅也没有扫描：停掉自排队，只留一次元数据刷新告诉UI现在是空闲。 */
    private fun idle() {
        ticking = false
        worker?.removeCallbacksAndMessages(null)
        if (discovery == "idle") return
        discovery = "idle"
        publishMeta()
    }
    private fun tick() {
        if (ticking || !enabled) return
        if (!demand.active && !scanEnabled) { idle(); return }
        ticking = true
        val ticket = generation
        post { poll(ticket) }
    }
    private fun poll(ticket: Int) {
        if (ticket != generation) return
        if (!enabled || (!demand.active && !scanEnabled)) { ticking = false; return }
        val now = SystemClock.elapsedRealtime()
        val model = prefs.getString("model", "L6")!!
        val specs = VehicleCatalog.forModel(model)
        val mode = prefs.getString("vehicleMode", "app")
        val nextMode = "$mode:$model"
        if (modeGeneration != nextMode) {
            source.close(); inventory.clear(); modeGeneration = nextMode; demand.reset()
        }
        val wanted = demand.keys()
        val due = if (wanted.isEmpty()) emptySet() else demand.due(now)
        ++tickTotal; readTotal += due.size
        val batch = if (mode == "root") runCatching {
            RootServiceClient.read(context, wanted, due, scanEnabled)
        }.getOrElse {
            com.ecarx.autoservice.ServiceTrace.event("vehicle_root_read_failed", it)
            JSONObject().put("session", "$nextMode:disconnected").put("discovery", "root_disconnected")
        } else runCatching {
            source.cycle(specs, now, model, wanted, due).also {
                // 诊断扫描与订阅采集共用同一代source：先补给订阅键，再推进全目录扫描。
                if (scanEnabled) source.scanCycle(specs, now, model)
            }
        }.getOrElse {
            com.ecarx.autoservice.ServiceTrace.event("vehicle_cycle_failed", it)
            JSONObject().put("session", "$nextMode:failed").put("discovery", "not_connected")
        }
        if (ticket != generation) return
        discovery = batch.optString("discovery", discovery)
        // 断连/失败时不计“已读”，否则要等满一个订阅周期才会重试，恢复延迟被放大。
        if (discovery !in RETRY_STATES) demand.markRead(due, now)
        inventory.accept(batch)
        batch.optJSONArray("keyEvents")?.let { events ->
            val identity=batch.optString("session")
            for(index in 0 until events.length()){
                val event=events.getJSONObject(index)
                if(event.optBoolean("reset"))menu.reset()
                else if(menu.accept(identity,event.optLong("sequence"),event.optInt("action",-1),event.optInt("key",-1),event.optLong("at"))){
                    main.post { if(ticket==generation)menuPressed() }
                }
            }
        }
        val array = batch.optJSONArray("samples")
        val byKey = HashMap<String, JSONObject>(array?.length() ?: 0)
        for (index in 0 until (array?.length() ?: 0)) array!!.getJSONObject(index).let { byKey[it.optString("key")] = it }
        val bundle = Bundle()
        for (spec in specs) {
            // 只把订阅键组装成Bundle：未订阅的键不进快照，也不参与签名比较。
            if (spec.key !in wanted) continue
            val item = byKey[spec.key]
            val signal = VehicleSignal(spec.key, item?.takeUnless { it.isNull("value") }?.optString("value"), spec.unit,
                if (mode == "root") "ROOT.ECARX.car_signal" else "ECARX.car_signal", item?.optInt("property", spec.propertyId) ?: spec.propertyId,
                item?.optLong("receivedAt") ?: now, null, item?.optLong("epoch") ?: 0,
                item?.optString("status")?.let { SignalStatus.valueOf(it) } ?: SignalStatus.UNAVAILABLE)
            bundle.putBundle(spec.key, signal.bundle(SystemClock.elapsedRealtime()))
        }
        // 对旧公共键保持兼容：只在底层键被订阅时才附带别名，未订阅不额外读取。
        for((old,next)in COMPAT_ALIASES)
            bundle.getBundle(old)?.let { bundle.putBundle(next, Bundle(it).apply { putString("key",next) }) }
        bundle.getBundle("property.10056")?.let { raw->
            val next=Bundle(raw);next.putString("key","driverSeatbeltWarning")
            next.putString("value",when(raw.getString("value")?.toFloatOrNull()){0f->"1";1f->"0";else->null})
            if(next.getString("value")==null)next.putString("status","UNAVAILABLE")
            bundle.putBundle("driverSeatbeltWarning",next)
        }
        if (wanted.isNotEmpty()) { ++bundleTotal; ++feedTotal; feed(bundle, now) }
        publishMeta()
        val period = if (scanEnabled) SCAN_PERIOD_MS else demand.periodMillis
        if (period <= 0L) { ticking = false; return }
        worker?.postDelayed({ tick() }, if (discovery in RETRY_STATES) 3000 else period)
        ticking = false
    }

    /** 元数据只在“发现状态/条数”变化或≥1秒时投递一次；逐值变化不再触发整包快照。 */
    private fun publishMeta() {
        val now = SystemClock.elapsedRealtime()
        val signature = "$discovery|${inventory.count()}|${inventory.validCount()}|$scanEnabled"
        if (signature == lastMetaSignature && now - lastDeliveredAt < 1000) return
        lastMetaSignature = signature; lastDeliveredAt = now
        val ticket = generation
        main.post {
            if (ticket != generation) return@post
            if (discovery == "root_disconnected") rootLost()
            deliver(discovery, inventory.count())
        }
    }
    fun stop() {
        enabled = false; ++generation; ticking = false; lastMetaSignature = ""; lastDeliveredAt = Long.MIN_VALUE
        RootServiceClient.closeVehicle()
        worker?.removeCallbacksAndMessages(null)
        val current = worker ?: return
        current.post {
            menu.reset(); source.close(); inventory.clear(); demand.reset(); scanEnabled = false
            // 车辆采集关闭时也注销helper中的原厂订阅；Root还可独立服务Shell与QNX。
            if (com.ecarx.autoservice.settings.ServiceFeatures.read(context).root)
                runCatching { RootServiceClient.request(context, 8, JSONObject(), 5000) }
        }
    }
    override fun close() {
        val current = worker
        stop()
        current?.post { thread?.quitSafely(); thread = null; worker = null }
    }

    private companion object {
        const val SCAN_PERIOD_MS = 250L
        val RETRY_STATES = setOf("root_disconnected", "not_connected")
        // 由目录别名表派生（基础键 ← 别名），避免两处各写一份而漂移；
        // driverSeatbeltWarning 需要取反换算，单独在下面处理。
        val COMPAT_ALIASES = VehicleCatalog.LEGACY_ALIASES.entries
            .filter { it.key != "driverSeatbeltWarning" }
            .map { it.value to it.key }
    }
}