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
    }
}
