package com.ecarx.autoservice.vehicle

import android.content.Context
import android.os.*
import com.ecarx.autoservice.platform.RootServiceClient
import com.galaxy.auto.contract.*
import org.json.JSONObject
import java.io.Closeable

/** 串行采集/索引/生命周期；服务只接收已整理快照。停止不再250ms空转。 */
internal class VehicleCollector(
    private val context: Context,
    val inventory: VehicleInventory,
    private val deliver: (Bundle, String, Int) -> Unit,
    private val rootLost: () -> Unit,
    private val menuPressed: ()->Unit = {},
) : Closeable {
    private val thread = HandlerThread("AutoService-vehicle").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val menu=com.ecarx.autoservice.input.ClusterMenuInput()
    private val source = EcarxVehicleSource()
    @Volatile private var generation = 0
    private var modeGeneration = ""
    private var running = false
    private var lastSignature = ""
    private var lastDeliveredAt = Long.MIN_VALUE
    private val prefs = context.getSharedPreferences("platform", 0)
    fun start() {
        if (running) return
        running = true
        val ticket = ++generation
        worker.post { poll(ticket) }
    }
    private fun poll(ticket: Int) {
        if (ticket != generation) return
        val now = SystemClock.elapsedRealtime()
        val model = prefs.getString("model", "L6")!!
        val specs = VehicleCatalog.forModel(model)
        val mode = prefs.getString("vehicleMode", "app")
        val nextMode = "$mode:$model"
        if (modeGeneration != nextMode) { source.close(); inventory.clear(); modeGeneration = nextMode }
        val batch = if (mode == "root") runCatching { RootServiceClient.read(context) }.getOrElse {
            com.ecarx.autoservice.ServiceTrace.event("vehicle_root_read_failed", it)
            JSONObject().put("session", "$nextMode:disconnected").put("discovery", "root_disconnected")
        } else source.cycle(specs, now, model)
        if (ticket != generation) return
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
        val signature = StringBuilder().append(batch.optString("discovery")).append(inventory.count()).append(inventory.validCount())
        specs.forEach { spec ->
            val item = byKey[spec.key]
            val signal = VehicleSignal(spec.key, item?.takeUnless { it.isNull("value") }?.optString("value"), spec.unit,
                if (mode == "root") "ROOT.ECARX.car_signal" else "ECARX.car_signal", item?.optInt("property", spec.propertyId) ?: spec.propertyId,
                item?.optLong("receivedAt") ?: now, null, item?.optLong("epoch") ?: 0,
                item?.optString("status")?.let { SignalStatus.valueOf(it) } ?: SignalStatus.UNAVAILABLE)
            val value = signal.bundle(SystemClock.elapsedRealtime())
            bundle.putBundle(spec.key, value)
            signature.append('|').append(spec.key).append(':').append(signal.value).append(':').append(value.getString("status")).append(':').append(signal.epoch)
        }
        // 对旧公共键保持兼容，新组件只使用语义别名；不增加第二次属性读取。
        for((old,next)in mapOf("property.10061" to "fuelConsumption","property.10062" to "electricConsumption","property.10040" to "parkingBrake","property.10041" to "positionLights"))
            bundle.getBundle(old)?.let { bundle.putBundle(next,Bundle(it).apply {putString("key",next)}) }
        bundle.getBundle("property.10056")?.let { raw->
            val next=Bundle(raw);next.putString("key","driverSeatbeltWarning")
            next.putString("value",when(raw.getString("value")?.toFloatOrNull()){0f->"1";1f->"0";else->null})
            if(next.getString("value")==null)next.putString("status","UNAVAILABLE")
            bundle.putBundle("driverSeatbeltWarning",next)
        }
        val nextSignature = signature.toString()
        val changed = nextSignature != lastSignature || now - lastDeliveredAt >= 1000 || lastDeliveredAt == Long.MIN_VALUE
        if (changed) { lastSignature = nextSignature; lastDeliveredAt = now }
        if (changed) main.post {
            if (ticket == generation && model == prefs.getString("model", "L6") && mode == prefs.getString("vehicleMode", "app")) {
                val discovery = batch.optString("discovery")
                if (discovery == "root_disconnected") rootLost()
                deliver(bundle, discovery, batch.optInt("total"))
            }
        }
        worker.postDelayed({ poll(ticket) }, if (batch.optString("discovery") in setOf("root_disconnected", "not_connected")) 3000 else 250)
    }
    fun stop() {
        running = false; ++generation; lastSignature = ""; lastDeliveredAt = Long.MIN_VALUE; RootServiceClient.closeVehicle()
        worker.removeCallbacksAndMessages(null)
        worker.post {
            menu.reset(); source.close(); inventory.clear()
            // 车辆采集关闭时也注销helper中的原厂订阅；Root还可独立服务Shell与QNX。
            if (com.ecarx.autoservice.settings.ServiceFeatures.read(context).root)
                runCatching { RootServiceClient.request(context, 8, JSONObject(), 5000) }
        }
    }
    override fun close() { stop(); worker.post { thread.quitSafely() } }
}
