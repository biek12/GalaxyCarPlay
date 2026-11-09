package com.ecarx.autoservice.vehicle

import dalvik.system.PathClassLoader
import java.io.Closeable
import java.lang.reflect.Proxy

/** 本车原厂VHAL的按需数值读取；只提供get/config，不含set或全目录值扫描。 */
internal class VhalNumericSource : Closeable {
    data class Reading(val value: Number?, val status: String, val error: String = "", val sourceTimestamp: Long? = null)
    private var vehicle: Any? = null
    private var api: Class<*>? = null
    private var valueType: Class<*>? = null
    private var callbackType: Class<*>? = null
    private var loader: ClassLoader? = null
    private var readableAreas = emptyMap<Int, Set<Int>>()
    private var bounds = emptyMap<Pair<Int, Int>, ClosedFloatingPointRange<Double>>()

    private fun connect() {
        if (vehicle != null) return
        check(android.os.Process.myUid() == 0) { "vhal_requires_service_root" }
        val classes = PathClassLoader("/system/priv-app/ECarXCarService/ECarXCarService.apk", javaClass.classLoader)
        val type = Class.forName("android.hardware.automotive.vehicle.V2_0.IVehicle", true, classes)
        val current = type.getMethod("getService", String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, "default", false) ?: error("vhal_service_unavailable")
        val configs = type.getMethod("getAllPropConfigs").invoke(current) as List<*>
        val areas = mutableMapOf<Int, Set<Int>>()
        val ranges = mutableMapOf<Pair<Int, Int>, ClosedFloatingPointRange<Double>>()
        configs.filterNotNull().forEach { config ->
            val id = config.javaClass.getField("prop").getInt(config)
            if (config.javaClass.getField("access").getInt(config) and 1 == 0) return@forEach
            val regions = config.javaClass.getField("areaConfigs").get(config) as List<*>
            areas[id] = regions.filterNotNull().map { it.javaClass.getField("areaId").getInt(it) }
                .toSet().ifEmpty { setOf(0) }
            regions.filterNotNull().forEach { region ->
                val area = region.javaClass.getField("areaId").getInt(region)
                val float = id and 0x00ff0000 == 0x00600000
                val min = (region.javaClass.getField(if (float) "minFloatValue" else "minInt32Value").get(region) as Number).toDouble()
                val max = (region.javaClass.getField(if (float) "maxFloatValue" else "maxInt32Value").get(region) as Number).toDouble()
                // 相等的上下界在VHAL中表示未声明约束；不自行推断物理量程或缩放系数。
                if (min.isFinite() && max.isFinite() && min < max) ranges[id to area] = min..max
            }
        }
        val values = Class.forName("android.hardware.automotive.vehicle.V2_0.VehiclePropValue", true, classes)
        val callback = Class.forName("android.hardware.automotive.vehicle.V2_0.IVehicle\$getCallback", true, classes)
        loader = classes; api = type; valueType = values; callbackType = callback
        readableAreas = areas; bounds = ranges; vehicle = current
    }

    fun read(propertyId: Int, area: Int, applyEngineeringBounds: Boolean = true): Reading = try {
        connect()
        if (area !in readableAreas[propertyId].orEmpty()) Reading(null, "REJECTED", "vhal_not_readable")
        else {
            val type = checkNotNull(valueType)
            val callback = checkNotNull(callbackType)
            val request = type.getConstructor().newInstance()
            type.getField("prop").setInt(request, propertyId)
            type.getField("areaId").setInt(request, area)
            var reply = Reading(null, "UNAVAILABLE", "vhal_missing_reply")
            // HIDL get回调在本次同步transact返回前交付；没有注册常驻回调或新建线程。
            val receiver = Proxy.newProxyInstance(loader, arrayOf(callback)) { _, method, args ->
                if (method.name == "onValues") {
                    val result = args[0] as Int
                    val value = args[1]
                    val matches = type.getField("prop").getInt(value) == propertyId &&
                        type.getField("areaId").getInt(value) == area
                    val status = type.getField("status").getInt(value)
                    val raw = type.getField("value").get(value)
                    val floats = raw.javaClass.getField("floatValues").get(raw) as List<*>
                    val ints = raw.javaClass.getField("int32Values").get(raw) as List<*>
                    val bytes = raw.javaClass.getField("bytes").get(raw) as List<*>
                    val number = scalar(propertyId, floats, ints, bytes.size)
                    reply = when {
                        !matches -> Reading(null, "REJECTED", "vhal_identity_mismatch")
                        result != 0 || status != 0 -> Reading(null, "UNAVAILABLE", "vhal_result_${result}_status_$status")
                        number == null -> Reading(null, "UNAVAILABLE", "vhal_scalar_invalid")
                        applyEngineeringBounds && !withinBounds(number, bounds[propertyId to area]) -> Reading(number, "UNAVAILABLE", "vhal_raw_outside_config")
                        else -> Reading(number, "VALID", sourceTimestamp = type.getField("timestamp").getLong(value)
                            .takeIf { it > 0 }?.div(1_000_000))
                    }
                }
                null
            }
            checkNotNull(api).getMethod("get", type, callback).invoke(vehicle, request, receiver)
            reply
        }
    } catch (error: Exception) {
        close()
        Reading(null, "UNAVAILABLE", (error.cause ?: error).javaClass.simpleName)
    }

    override fun close() {
        vehicle = null; api = null; valueType = null; callbackType = null; loader = null; readableAreas = emptyMap(); bounds = emptyMap()
    }

    companion object {
        fun withinBounds(value: Number, range: ClosedFloatingPointRange<Double>?) =
            value.toDouble().isFinite() && (range == null || value.toDouble() in range)
        /** 不把空向量的默认0当数据，不按数值大小猜另一种类型或解码未核实的字节载荷。 */
        fun scalar(propertyId: Int, floats: List<*>, ints: List<*>, bytes: Int): Number? {
            if (bytes != 0) return null
            val value = when (propertyId and 0x00ff0000) {
                0x00600000 -> if (floats.size == 1 && ints.isEmpty()) floats.single() as? Number else null
                0x00400000 -> if (ints.size == 1 && floats.isEmpty()) ints.single() as? Number else null
                else -> null
            }
            return value?.takeIf { it.toDouble().isFinite() }
        }
    }
}
