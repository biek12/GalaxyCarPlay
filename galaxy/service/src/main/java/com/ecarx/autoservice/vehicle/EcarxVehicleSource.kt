package com.ecarx.autoservice.vehicle

import android.os.IBinder
import com.galaxy.auto.contract.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 只读源：运行时目录＋本机SDK信号定义＋已核验车型映射。拒绝/缺失保留状态，不猜ID或写属性。
 *
 * v4订阅驱动：原厂 `getProperty` 读的是“注册后才有刷新”的缓存（见 [connect] 内注释），
 * 因此**注册即订阅**。这里只注册调用方声明的键，未声明的属性不注册、不读取——
 * 这是把 172 次/s 的常驻读取降到订阅规模的关键，而不是把全量读取换个地方跑。
 */
internal class EcarxVehicleSource : Closeable {
    private data class Property(val id: Int, val area: Int, val name: String, val origin: String, val access: Int, val type: String, val mapped: Boolean = true)
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
    private var specs = emptyList<SignalSpec>()
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
        if (sample.epoch == ticket && epoch == ticket) {
            record(key, sample)
            if(key.first==KEY_EVENT_ID&&status=="VALID"){
                val values=when(value){is IntArray->value;is Array<*>->value.filterIsInstance<Int>().toIntArray();else->intArrayOf()}
                if(values.size>=2)synchronized(keyEvents){
                    if(epoch!=ticket)return@synchronized
                    if(keyEvents.size>=128){keyEvents.clear();keyEvents.add(JSONObject().put("reset",true).put("sequence",++keySequence))}
                    keyEvents.add(JSONObject().put("action",values[0]).put("key",values[1]).put("at",sample.receivedAt).put("sequence",++keySequence))
                }
            }
        }
    }
    private val dirty = java.util.concurrent.ConcurrentHashMap<Pair<Int, Int>, Sample>()
    private var propertiesByKey = emptyMap<Pair<Int, Int>, Property>()
    private val keyEvents=java.util.ArrayDeque<JSONObject>()
    private var keySequence=0L
    private var semanticProperties = emptyMap<Int, Property>()
    private val methods = java.util.concurrent.ConcurrentHashMap<Pair<Class<*>, String>, Method>()
    private val knownAreaNormalization = (0x21708998..0x217089A9).toSet() + setOf(0x21607A66, 0x21607A67)
    // 原厂CarSignalManager用area1读全局信号；CarPropertyUtils保留HAL的area0包装。
    // VehicleArea.GLOBAL/MASK来自目标服务自身声明，不将座椅/车门区域混成全局。
    private fun globalArea(id: Int) = id and 0x0f000000 == 0x01000000 || id in knownAreaNormalization
    private fun Any.call(name: String): Any? = methods.getOrPut(javaClass to name) { javaClass.getMethod(name) }.invoke(this)
    /** v4：订阅集合变化时增删注册。方控按键事件不是目录键，但必须常驻注册。 */
    private var activeIds: Set<Int> = emptySet()
    private var activeFilter: Any? = null
    private var manager: Any? = null
    private var filterType: Class<*>? = null
    private var callbackType: Class<*>? = null
    private fun connect(specs: List<SignalSpec>) {
        this.specs = specs
        if (binder?.isBinderAlive == true && proxy != null) return
        close()
        this.specs = specs
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
        for ((type, field) in listOf("SigPropertyIdString" to "sigIdToStringMap", "MgrPropertyIdString" to "mgrIdToStringMap")) {
            runCatching {
                val names = Class.forName("ecarx.car.hardware.property.$type", true, loader)
                    .getField(field).get(null) as Map<*, *>
                names.forEach { (id, name) -> if (id is Int && name is String)
                    properties[id to 1] = Property(id, 1, name, "firmware_declared", -1, "unknown") }
            }.onFailure { com.ecarx.autoservice.ServiceTrace.event("vehicle_symbols_$type", it) }
        }
        fun symbol(name: String) = name.replace("_", "").lowercase(java.util.Locale.ROOT)
        val declaredByName = properties.values.associateBy { symbol(it.name) }
        specs.forEach {
            val declared = declaredByName[symbol(it.name)]
            if (declared != null) {
                properties[declared.id to it.area] = declared.copy(area = it.area)
                if (it.area != 1) properties.remove(declared.id to 1)
            } else properties.putIfAbsent(it.propertyId to it.area, Property(it.propertyId, it.area, it.name, "model_mapping", -1, if(it.payload=="ecarx-pa-enum")"bytes"else"number"))
        }
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
        // 本车CarSignalService.sendLatestValueToListener会对filter每项调用getProperty，
        // 未映射ID会抛异常中断整次注册。读取当前固件自身的纯ID映射，不把声明表当支持表。
        val mappingLoader = dalvik.system.PathClassLoader("/system/priv-app/ECarXCarService/ECarXCarService.apk", loader)
        val mappingType = Class.forName("com.ecarx.car.hal.HalPropertyIdTransformation", true, mappingLoader)
        val mapping = mappingType.getDeclaredField("mHaltoMgrPropIdMap").apply { isAccessible = true }.get(null)
        val halId = mapping.javaClass.getDeclaredMethod("getHalPropId", Int::class.javaPrimitiveType).apply { isAccessible = true }
        inventory = properties.values.map { it.copy(mapped = (halId.invoke(mapping, it.id) as Int) != -1) }; cursor = 0
        propertiesByKey = inventory.associateBy { it.id to it.area }
        semanticProperties = specs.mapNotNull { spec ->
            val resolvedId = declaredByName[symbol(spec.name)]?.id ?: spec.propertyId
            inventory.firstOrNull { it.id == resolvedId && (it.access == -1 || it.access and 1 != 0) }?.let { spec.propertyId to it }
        }.toMap()
        val ticket = epoch
        // 原厂读取依赖信号注册后刷新缓存：先建Manager与回调，注册集合由[applyFilter]按订阅增删。
        runCatching {
            val thread = android.os.HandlerThread("AutoService-oem-events").apply { start() }
            try {
                val managerType = Class.forName("ecarx.car.hardware.property.ECarXCarPropertyManagerBase", true, loader)
                val created = managerType.getConstructor(IBinder::class.java, android.os.Handler::class.java,
                    Boolean::class.javaPrimitiveType, String::class.java)
                    .newInstance(signal, android.os.Handler(thread.looper), false, "AutoService")
                val types = managerType.declaredClasses.first { it.simpleName == "CarPropertyEventCallback" }
                val cb = java.lang.reflect.Proxy.newProxyInstance(types.classLoader, arrayOf(types)) { proxy, method, args ->
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
                                    else if (area == 0 && globalArea(id) && propertiesByKey.containsKey(id to 1)) id to 1 else return@runCatching
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
                val filterClass = Class.forName("ecarx.car.hardware.signal.SignalFilter", true, loader)
                managerType.getMethod("registerCallback", types).invoke(created, cb)
                manager = created; callbackType = types; filterType = filterClass
                subscription = AutoCloseable {
                    runCatching { activeFilter?.let { managerType.getMethod("unregisterSignals", filterClass).invoke(created, it) } }
                    runCatching { managerType.getMethod("unregisterCallback", types).invoke(created, cb) }
                    thread.quitSafely()
                }
            } catch (error: Exception) { thread.quitSafely(); throw error }
        }.onSuccess {
            com.ecarx.autoservice.ServiceTrace.event("vehicle_subscription_ready")
        }.onFailure {
            val cause = (it as? InvocationTargetException)?.targetException ?: it
            discovery += ":subscription_${cause.javaClass.simpleName}"
            com.ecarx.autoservice.ServiceTrace.event("vehicle_subscription_failed", it)
        }
    }

    /**
     * 按订阅键集合增删原厂注册。
     *
     * 先注册新集合再注销旧集合，避免中间出现“两边都没订阅”的空窗而让缓存停更。
     * 注册失败时保留旧集合并记事件；下一拍会带着同一集合重试，不做静默降级。
     */
    private fun applyFilter(ids: Set<Int>) {
        val current = manager ?: return
        val filterClass = filterType ?: return
        if (ids == activeIds) return
        val managerType = Class.forName("ecarx.car.hardware.property.ECarXCarPropertyManagerBase", true, javaClass.classLoader)
        val next = runCatching {
            filterClass.getConstructor().newInstance().also { filter ->
                val add = filterClass.getMethod("add", Int::class.javaObjectType)
                ids.forEach { add.invoke(filter, it) }
            }
        }.getOrNull() ?: return
        runCatching {
            managerType.getMethod("registerSignals", filterClass).invoke(current, next)
            activeFilter?.let { old -> runCatching { managerType.getMethod("unregisterSignals", filterClass).invoke(current, old) } }
            activeFilter = next; activeIds = ids
        }.onFailure { com.ecarx.autoservice.ServiceTrace.event("vehicle_filter_failed", it) }
    }

    /** 订阅键 -> 需要注册的属性ID；未映射/不可读的跳过（读取时会标 REJECTED/UNAVAILABLE）。 */
    private fun idsFor(wanted: Set<String>): Set<Int> {
        val out = HashSet<Int>(wanted.size + 1)
        for (spec in specs) {
            if (spec.key !in wanted) continue
            val property = semanticProperties[spec.propertyId] ?: continue
            if (property.mapped && (property.access == -1 || property.access and 1 != 0)) out += property.id
        }
        // 方控按键事件：别名目录里没有它，但菜单/方控整条链路都依赖它，必须常驻。
        if (propertiesByKey.keys.any { it.first == KEY_EVENT_ID }) out += KEY_EVENT_ID
        return out
    }

    /** 单属性读取；返回null表示本次无新样本（未映射/不可读会写入REJECTED）。 */
    private fun readProperty(property: Property, now: Long) {
        val key = property.id to property.area
        var value: Any? = null
        var status = "UNAVAILABLE"
        var error = ""
        var responseArea = -1
        try {
            if (!property.mapped) {
                status = "REJECTED"; error = "firmware_unmapped"
            } else if (property.access != -1 && property.access and 1 == 0) {
                status = "REJECTED"; error = "not_readable"
            } else {
                val wrapper = reader!!.invoke(proxy, property.id, property.area) ?: error("empty_value")
                check((wrapper.call("getPropertyId") as Number).toInt() == property.id) { "property_mismatch" }
                responseArea = (wrapper.call("getAreaId") as Number).toInt()
                value = wrapper.call("getValue")
                val areaValid = responseArea == property.area ||
                    (property.area == 1 && responseArea == 0 && property.mapped && globalArea(property.id))
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
            if (current != null && current.epoch == epoch) current
            else Sample(value, status, error, responseArea, now, epoch, sampleSequence.incrementAndGet())
        }!!
        record(key, effective)
    }

    private fun aliasJson(spec: SignalSpec, sample: Sample?, now: Long): JSONObject {
        val value = sample?.value
        val state = sample?.status ?: "UNAVAILABLE"
        val normalized = VehicleCatalog.convert(spec, value)
        val status = if (state == "VALID" && normalized == null) "UNAVAILABLE" else state
        val property = semanticProperties[spec.propertyId]
        return JSONObject().apply {
            put("key", spec.key); put("value", normalized ?: JSONObject.NULL); put("unit", spec.unit)
            put("property", property?.id ?: spec.propertyId); put("receivedAt", sample?.receivedAt ?: now); put("epoch", epoch); put("status", status)
        }
    }

    /**
     * 订阅驱动的一次采集。
     *
     * @param wanted 当前订阅键（决定注册集合与返回哪些别名）
     * @param due 本拍到期的键（由[VehicleDemand]判定）；**只有到期键才发起车机读取**
     * @return samples 只含 wanted；properties 只含本拍真正读过的属性；keyEvents 恒定排空
     */
    fun cycle(specs: List<SignalSpec>, now: Long, model: String, wanted: Set<String>, due: Set<String>): JSONObject {
        if (model != lastModel) { close(); lastModel = model }
        val aliases = JSONArray()
        val raw = JSONArray()
        try {
            connect(specs)
            applyFilter(idsFor(wanted))
            if (due.isNotEmpty()) {
                due.forEach { key ->
                    val spec = specs.firstOrNull { it.key == key } ?: return@forEach
                    semanticProperties[spec.propertyId]?.let { readProperty(it, now) }
                }
                due.forEach { key ->
                    val spec = specs.firstOrNull { it.key == key } ?: return@forEach
                    val property = semanticProperties[spec.propertyId] ?: return@forEach
                    val sample = samples[property.id to property.area]?.takeIf { it.epoch == epoch } ?: return@forEach
                    raw.put(JSONObject().apply {
                        put("propertyId", property.id); put("areaId", property.area); put("responseAreaId", sample.responseArea)
                        put("name", property.name); put("origin", property.origin); put("access", property.access)
                        put("firmwareMapped", property.mapped)
                        put("type", sample.value?.javaClass?.name ?: property.type); put("receivedAt", sample.receivedAt)
                        put("maxAgeMillis", maxOf(2000L, ((inventory.size + 31) / 32) * 500L))
                        put("status", sample.status); put("error", sample.error); put("value", encode(sample.value))
                    })
                    dirty.remove(property.id to property.area, sample)
                }
            }
            specs.forEach { spec ->
                if (spec.key !in wanted) return@forEach
                val property = semanticProperties[spec.propertyId]
                aliases.put(aliasJson(spec, property?.let { samples[it.id to it.area]?.takeIf { s -> s.epoch == epoch } }, now))
            }
        } catch (failure: Exception) {
            com.ecarx.autoservice.ServiceTrace.event("vehicle_cycle_failed", failure)
            discovery = "not_connected"
            specs.forEach { spec -> if (spec.key in wanted) aliases.put(aliasJson(spec, null, now)) }
        }
        return JSONObject().apply {
            put("keyEvents",synchronized(keyEvents){JSONArray().apply { while(keyEvents.isNotEmpty())put(keyEvents.removeFirst()) }})
            put("samples", aliases); put("properties", raw); put("discovery", discovery)
            put("total", inventory.size); put("cursor", cursor); put("session", "$model:$instance:$epoch")
        }
    }

    /**
     * 显式全目录诊断扫描：按32项/拍轮转推进，与订阅无关，用于保留“读尽读”能力。
     * 只在诊断扫描开关打开时由采集器调用；这是唯一会读未订阅属性的入口。
     */
    fun scanCycle(specs: List<SignalSpec>, now: Long, model: String): JSONObject {
        if (model != lastModel) { close(); lastModel = model }
        val raw = JSONArray()
        try {
            connect(specs)
            applyFilter(idsFor(specs.mapTo(HashSet()) { it.key }))
            repeat(minOf(32, inventory.size)) {
                val property = inventory[cursor]; cursor = (cursor + 1) % inventory.size
                readProperty(property, now)
            }
            dirty.keys.toList().take(128).forEach { key ->
                propertiesByKey[key]?.let { property ->
                    val sample = samples[key]?.takeIf { it.epoch == epoch } ?: return@forEach
                    raw.put(JSONObject().apply {
                        put("propertyId", property.id); put("areaId", property.area); put("responseAreaId", sample.responseArea)
                        put("name", property.name); put("origin", property.origin); put("access", property.access)
                        put("firmwareMapped", property.mapped)
                        put("type", sample.value?.javaClass?.name ?: property.type); put("receivedAt", sample.receivedAt)
                        put("maxAgeMillis", maxOf(2000L, ((inventory.size + 31) / 32) * 500L))
                        put("status", sample.status); put("error", sample.error); put("value", encode(sample.value))
                    })
                    dirty.remove(key, sample)
                }
            }
        } catch (failure: Exception) {
            com.ecarx.autoservice.ServiceTrace.event("vehicle_scan_failed", failure)
            discovery = "not_connected"
        }
        return JSONObject().apply {
            put("keyEvents",synchronized(keyEvents){JSONArray().apply { while(keyEvents.isNotEmpty())put(keyEvents.removeFirst()) }})
            put("scan", raw); put("discovery", discovery)
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
    override fun close() {
        ++epoch;synchronized(keyEvents){keyEvents.clear();keySequence=0}; samples.clear(); dirty.clear(); propertiesByKey = emptyMap(); semanticProperties = emptyMap()
        runCatching { subscription?.close() }; subscription = null
        binder = null; proxy = null; reader = null; inventory = emptyList(); cursor = 0
        activeIds = emptySet(); activeFilter = null; manager = null; filterType = null; callbackType = null
    }

    private companion object { const val KEY_EVENT_ID = 289475088 }
}