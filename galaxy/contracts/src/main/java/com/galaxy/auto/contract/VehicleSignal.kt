package com.galaxy.auto.contract

import android.os.Bundle

enum class SignalStatus { VALID, UNAVAILABLE, STALE, REJECTED }

/** 车辆原始时钟与本机接收时钟分开；每字段新鲜度独立，不把缺失值填成0。 */
data class VehicleSignal(
    val key: String,
    val value: String?,
    val unit: String,
    val source: String,
    val propertyId: Int,
    val receivedAt: Long,
    val sourceTimestamp: Long?,
    val epoch: Long,
    val status: SignalStatus,
    val maxAgeMillis: Long = 2000,
) {
    fun effectiveStatus(now: Long): SignalStatus = if (status != SignalStatus.VALID) status
        else if (now < receivedAt || now - receivedAt > maxAgeMillis) SignalStatus.STALE else status
    fun bundle(now: Long) = Bundle().apply {
        putString("key", key); putString("value", value); putString("unit", unit); putString("source", source)
        putInt("propertyId", propertyId); putLong("receivedAt", receivedAt); putLong("epoch", epoch)
        sourceTimestamp?.let { putLong("sourceTimestamp", it) }
        putString("status", effectiveStatus(now).name)
        // 消费者晚于本机收到时仍要能按同一上限重算新鲜度，不能只信一次判定的status。
        putLong("maxAgeMillis", maxAgeMillis)
    }

    companion object {
        /** 读回[bundle]；缺key或状态非法返回null，不补默认值，避免把缺字段当有效读数。 */
        fun parse(source: Bundle): VehicleSignal? {
            val key = source.getString("key") ?: return null
            val status = source.getString("status")?.let { name ->
                SignalStatus.entries.firstOrNull { it.name == name }
            } ?: return null
            return VehicleSignal(key, source.getString("value"), source.getString("unit").orEmpty(),
                source.getString("source").orEmpty(), source.getInt("propertyId", 0),
                source.getLong("receivedAt", 0),
                if (source.containsKey("sourceTimestamp")) source.getLong("sourceTimestamp") else null,
                source.getLong("epoch", 0), status, source.getLong("maxAgeMillis", 2000))
        }
    }
}
