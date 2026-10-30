package com.ecarx.autoservice.vehicle

import android.os.IBinder
import com.galaxy.auto.contract.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** 只读源：运行时目录＋本机SDK信号定义＋已核验车型映射。拒绝/缺失保留状态，不猜ID或写属性。 */
internal class EcarxVehicleSource : Closeable {
    private data class Property(val id: Int, val area: Int, val name: String, val origin: String, val access: Int, val type: String)
    private var subscription: AutoCloseable? = null
    private var binder: IBinder? = null
    private var proxy: Any? = null
    private var reader: Method? = null
    @Volatile private var epoch = 0L
    private val instance = java.util.UUID.randomUUID().toString()
    private var inventory = emptyList<Property>()
    private var cursor = 0
    private var discovery = "not_connected"
    private var lastModel = ""
    private data class Sample(val value: Any?, val status: String, val error: String, val responseArea: Int, val receivedAt: Long, val epoch: Long, val sequence: Long)
    private val sampleSequence = java.util.concurrent.atomic.AtomicLong()
    private fun record(key: Pair<Int, Int>, sample: Sample) {
        dirty.merge(key, sample) { old, next -> if (old.sequence > next.sequence) old else next }
    }
    private val samples = java.util.concurrent.ConcurrentHashMap<Pair<Int, Int>, Sample>()
    private fun acceptEvent(key: Pair<Int, Int>, value: Any?, status: String, error: String, area: Int, ticket: Long) {
        val sample = samples.compute(key) { _, previous ->
            if (epoch != ticket) previous else Sample(value, status, error, area, android.os.SystemClock.elapsedRealtime(), ticket, sampleSequence.incrementAndGet())
        } ?: return
        if (sample.epoch == ticket) record(key, sample)
    }
    private val dirty = java.util.concurrent.ConcurrentHashMap<Pair<Int, Int>, Sample>()
    private var propertiesByKey = emptyMap<Pair<Int, Int>, Property>()
    private var semanticProperties = emptyMap<Int, Property>()
    private val methods = java.util.concurrent.ConcurrentHashMap<Pair<Class<*>, String>, Method>()
    private val knownAreaNormalization = (0x21708998..0x217089A9).toSet() + setOf(0x21607A66, 0x21607A67)
    private fun Any.call(name: String): Any? = methods.getOrPut(javaClass to name) { javaClass.getMethod(name) }.invoke(this)
    private fun connect(specs: List<SignalSpec>) {
        if (binder?.isBinderAlive == true && proxy != null) return
        close()
        val loader = javaClass.classLoader
        val root = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "ecarxcar_service") as? IBinder ?: error("ecarxcar_service_unavailable")
        check(root.interfaceDescriptor == "ecarx.car.IECarXCar")
        val car = Class.forName("ecarx.car.IECarXCar\$Stub", true, loader)
            .getMethod("asInterface", IBinder::class.java).invoke(null, root)
        val signal = Class.forName("ecarx.car.IECarXCar", true, loader).getMethod("getCarService", String::class.java)
            .invoke(car, "car_signal") as? IBinder ?: error("car_signal_unavailable")
        val descriptor = "ecarx.car.hardware.property.IECarXCarProperty"
        check(signal.interfaceDescriptor == descriptor) { "car_signal_descriptor_mismatch" }
        val type = Class.forName(descriptor, true, loader)
        val current = Class.forName("$descriptor\$Stub", true, loader).getMethod("asInterface", IBinder::class.java)
            .invoke(null, signal) ?: error("car_signal_proxy")
        reader = type.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        proxy = current; binder = signal; ++epoch
        val properties = linkedMapOf<Pair<Int, Int>, Property>()
        // 读取本车已安装SDK的符号表；它是来源声明，绝不标成运行时已支持。
        runCatching {
            val names = Class.forName("ecarx.car.hardware.property.SigPropertyIdString", true, loader)
                .getField("sigIdToStringMap").get(null) as Map<*, *>
            names.forEach { (id, name) -> if (id is Int && name is String)
                properties[id to 1] = Property(id, 1, name, "firmware_declared", -1, "unknown") }
        }
        specs.forEach { properties[it.propertyId to 1] = Property(it.propertyId, 1, it.name, "model_mapping", -1, "number") }
        val listed = runCatching { type.getMethod("getPropertyList").invoke(current) as? List<*> }
        discovery = when { listed.isFailure -> "catalog_rejected"; listed.getOrNull() == null -> "catalog_null"; else -> "runtime_catalog" }
        listed.getOrNull()?.filterNotNull()?.forEach { config ->
            val id = (config.call("getPropertyId") as Number).toInt()
            properties.keys.filter { it.first == id }.toList().forEach(properties::remove)
            val access = (config.call("getAccess") as Number).toInt()
            val areas = (config.call("getAreaIds") as IntArray).let { if (it.isEmpty()) intArrayOf(0) else it }
            val name = specs.firstOrNull { it.propertyId == id }?.name ?: "property.$id"
            val valueType = (config.call("getPropertyType") as Class<*>).name
            areas.forEach { area -> properties[id to area] = Property(id, area, name, "runtime_catalog", access, valueType) }
        }
        inventory = properties.values.toList(); cursor = 0
        propertiesByKey = properties.toMap()
        semanticProperties = specs.mapNotNull { spec -> inventory.firstOrNull { it.id == spec.propertyId && (it.access == -1 || it.access and 1 != 0) }?.let { spec.propertyId to it } }.toMap()
        val ticket = epoch
        // 原厂读取依赖信号注册后刷新缓存；按已核验Manager调用顺序订阅，并配套注销。
        runCatching {
            val thread = android.os.HandlerThread("AutoService-oem-events").apply { start() }
            try {
                val managerType = Class.forName("ecarx.car.hardware.property.ECarXCarPropertyManagerBase", true, loader)
                val manager = managerType.getConstructor(IBinder::class.java, android.os.Handler::class.java,
                    Boolean::class.javaPrimitiveType, String::class.java)
                    .newInstance(signal, android.os.Handler(thread.looper), false, "AutoService")
                val callbackType = managerType.declaredClasses.first { it.simpleName == "CarPropertyEventCallback" }
                val callback = java.lang.reflect.Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "AutoServiceVehicleCallback"
                        "onChangeEvent" -> {
                            if (epoch == ticket) runCatching {
                                val wrapper = args?.firstOrNull() ?: return@runCatching
                                val id = (wrapper.call("getPropertyId") as Number).toInt()
                                val area = (wrapper.call("getAreaId") as Number).toInt()
                                val key = if (propertiesByKey.containsKey(id to area)) id to area
                                    else if (area == 0 && id in knownAreaNormalization && propertiesByKey.containsKey(id to 1)) id to 1 else return@runCatching
                                val value = wrapper.call("getValue")
                                if (epoch == ticket) {
                                    acceptEvent(key, value, if (value == null) "UNAVAILABLE" else "VALID", "", area, ticket)
                                }
                            }
                            null
                        }
                        "onErrorEvent" -> {
                            if (epoch == ticket && args?.size == 2) {
                                val key = (args[0] as Number).toInt() to (args[1] as Number).toInt()
                                if (propertiesByKey.containsKey(key)) {
                                    acceptEvent(key, null, "UNAVAILABLE", "oem_event_error", key.second, ticket)
                                }
                            }
                            null
                        }
                        else -> null
                    }
                }
                val filterType = Class.forName("ecarx.car.hardware.signal.SignalFilter", true, loader)
                val filter = filterType.getConstructor().newInstance()
                val add = filterType.getMethod("add", Int::class.javaObjectType)
                inventory.filter { it.access == -1 || it.access and 1 != 0 }.map { it.id }.distinct().forEach { add.invoke(filter, it) }
                managerType.getMethod("registerCallback", callbackType).invoke(manager, callback)
                try { managerType.getMethod("registerSignals", filterType).invoke(manager, filter) }
                catch (error: Exception) { runCatching { managerType.getMethod("unregisterCallback", callbackType).invoke(manager, callback) }; throw error }
                subscription = AutoCloseable {
                    runCatching { managerType.getMethod("unregisterSignals", filterType).invoke(manager, filter) }
                    runCatching { managerType.getMethod("unregisterCallback", callbackType).invoke(manager, callback) }
                    thread.quitSafely()
                }
            } catch (error: Exception) { thread.quitSafely(); throw error }
        }.onFailure { discovery += ":subscription_rejected" }
    }
    fun cycle(specs: List<SignalSpec>, now: Long, model: String): JSONObject {
        if (model != lastModel) { close(); lastModel = model }
        val aliases = JSONArray()
        val raw = JSONArray()
        try {
            connect(specs)
            val selected = linkedMapOf<Pair<Int, Int>, Property>()
            semanticProperties.values.forEach { selected[it.id to it.area] = it }
            // 每轮最多推进32个通用属性，避免把数千个信号一次阻塞在主线程/IPC里。
            repeat(minOf(32, inventory.size)) {
                val property = inventory[cursor]; cursor = (cursor + 1) % inventory.size
                selected[property.id to property.area] = property
            }
            dirty.keys.toList().take(128).forEach { key -> propertiesByKey[key]?.let { selected[key] = it } }
            selected.values.forEach { property ->
                val key = property.id to property.area
                val maxAge = if (semanticProperties.containsKey(property.id)) 500L else maxOf(2000L, ((inventory.size + 31) / 32) * 500L)
                val cached = samples[key]?.takeIf { it.epoch == epoch }
                if (cached != null && now - cached.receivedAt in 0 until maxAge) return@forEach
                var value: Any? = null
                var status = "UNAVAILABLE"
                var error = ""
                var responseArea = -1
                try {
                    if (property.access != -1 && property.access and 1 == 0) {
                        status = "REJECTED"; error = "not_readable"
                    } else {
                        val wrapper = reader!!.invoke(proxy, property.id, property.area) ?: error("empty_value")
                        check((wrapper.call("getPropertyId") as Number).toInt() == property.id) { "property_mismatch" }
                        responseArea = (wrapper.call("getAreaId") as Number).toInt()
                        value = wrapper.call("getValue")
                        val areaValid = responseArea == property.area ||
                            (property.area == 1 && responseArea == 0 && property.id in knownAreaNormalization)
                        status = if (!areaValid) "REJECTED" else if (value != null) "VALID" else "UNAVAILABLE"
                        if (!areaValid) error = "area_mismatch"
                    }
                } catch (failure: Exception) {
                    val cause = (failure as? InvocationTargetException)?.targetException ?: failure
                    status = if (cause is SecurityException) "REJECTED" else "UNAVAILABLE"
                    error = cause.javaClass.simpleName // 不把原始载荷、车辆标识或异常message写入日志/诊断。
                }
                // Binder读取期间到达的事件更新必须保留，旧读取结果不能覆盖较新的回调值。
                val effective = samples.compute(key) { _, current ->
                    if (current != null && current.epoch == epoch && current !== cached) current
                    else Sample(value, status, error, responseArea, now, epoch, sampleSequence.incrementAndGet())
                }!!
                record(key, effective)
            }
            selected.values.forEach { property ->
                val key = property.id to property.area
                val sample = samples[key]?.takeIf { it.epoch == epoch } ?: return@forEach
                val value = sample.value
                raw.put(JSONObject().apply {
                    put("propertyId", property.id); put("areaId", property.area); put("responseAreaId", sample.responseArea)
                    put("name", property.name); put("origin", property.origin); put("access", property.access)
                    put("type", value?.javaClass?.name ?: property.type); put("receivedAt", sample.receivedAt)
                    put("maxAgeMillis", maxOf(2000L, ((inventory.size + 31) / 32) * 500L))
                    put("status", sample.status); put("error", sample.error); put("value", encode(value))
                })
                dirty.remove(key, sample)
            }
            specs.forEach { spec ->
                val property = semanticProperties[spec.propertyId]
                val sample = property?.let { samples[it.id to it.area]?.takeIf { sample -> sample.epoch == epoch } }
                val value = sample?.value
                val state = sample?.status ?: "UNAVAILABLE"
                val normalized = VehicleCatalog.convert(spec, value)
                val status = if (state == "VALID" && normalized == null) "UNAVAILABLE" else state
                aliases.put(JSONObject().apply {
                    put("key", spec.key); put("value", normalized ?: JSONObject.NULL); put("unit", spec.unit)
                    put("property", spec.propertyId); put("receivedAt", sample?.receivedAt ?: now); put("epoch", epoch); put("status", status)
                })
            }
        } catch (_: Exception) {
            discovery = "not_connected"
            specs.forEach { spec -> aliases.put(JSONObject().apply {
                put("key", spec.key); put("value", JSONObject.NULL); put("unit", spec.unit); put("property", spec.propertyId)
                put("receivedAt", now); put("epoch", epoch); put("status", "UNAVAILABLE")
            }) }
        }
        return JSONObject().apply {
            put("samples", aliases); put("properties", raw); put("discovery", discovery)
            put("total", inventory.size); put("cursor", cursor); put("session", "$model:$instance:$epoch")
        }
    }
    private fun encode(value: Any?): String = when {
        value == null -> ""
        value.javaClass.isArray -> JSONArray().apply {
            repeat(java.lang.reflect.Array.getLength(value)) { put(java.lang.reflect.Array.get(value, it)) }
        }.toString()
        value is Number || value is Boolean || value is String -> value.toString()
        else -> "unsupported:${value.javaClass.name}"
    }
    override fun close() { ++epoch; samples.clear(); dirty.clear(); propertiesByKey = emptyMap(); semanticProperties = emptyMap(); runCatching { subscription?.close() }; subscription = null; binder = null; proxy = null; reader = null; inventory = emptyList(); cursor = 0 }
}
