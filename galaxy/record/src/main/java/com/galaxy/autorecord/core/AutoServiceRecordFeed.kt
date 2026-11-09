package com.galaxy.autorecord.core

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract

/** 一个 observer 租约承接授权和车辆订阅；授权 READY 前 feed 为空，不产生投屏需求。 */
class AutoServiceRecordFeed(
    context: Context,
    private val settings: () -> RecordSettings,
    private val onReady: () -> Unit,
    private val onLost: (String) -> Unit,
    private val onChanged: () -> Unit = {},
    private val recordUpdates: Boolean = false,
    private val requestedKeys: (() -> List<String>)? = null,
) : VehicleFeed {
    private val model = AutoVehicleFeed { SystemClock.elapsedRealtime() }
    private var known = emptySet<String>()
    @Volatile private var authorized = false
    @Volatile private var foreground = false
    @Volatile private var enabled = true
    private var serviceMinimumMs = 250
    private val updates = java.util.ArrayDeque<RecordedVehicleUpdate>()
    @Volatile private var historyOverflow = false
    var protocolVersion = 0
        private set
    var connected = false
        private set
    private var sentKeys = emptyList<String>()
    private var sentInterval = 0
    private var statsAt = 0L
    private var received = 0
    private var ageMax = 0L
    private var uiMax = 0L
    private val client = AutoClient(context, AutoContract.OBSERVER,
        onSnapshot = { snapshot ->
            protocolVersion = snapshot.getInt("version", 0)
            known = snapshot.getStringArray("vehicleKeys").orEmpty().toSet()
            serviceMinimumMs = snapshot.getInt("minVehicleFeedIntervalMs", 250).coerceAtLeast(AutoContract.MIN_FEED_INTERVAL_MS)
            enabled = snapshot.getBundle("features")?.getBoolean("vehicleEnabled", true) != false && snapshot.getBoolean("automatic", true)
            subscribe()
        }, onState = { state ->
            if (!linkFailure(state)) return@AutoClient
            protocolVersion = 0; connected = false; authorized = false; sentKeys = emptyList(); sentInterval = 0
            serviceMinimumMs = 250
            model.connected(false, "AutoService：$state")
            onLost(state)
        }, onVehicleFeed = { if (authorized && foreground && enabled) accept(it) },
        onReady = { connected = true; onReady() }, replayVehicleFeedOnConnect = false, vehicleFeedOnWorker = recordUpdates)

    fun connect() = client.connect()
    fun requestCamera(result: (Boolean, String) -> Unit) = client.requestClientPermissions(listOf(Manifest.permission.CAMERA)) {
        result(it.getBoolean("granted"), it.getString("error").orEmpty())
    }
    fun requestAssistPermissions(result:(Boolean,String)->Unit)=client.requestClientPermissions(listOf(Manifest.permission.CAMERA,Manifest.permission.SYSTEM_ALERT_WINDOW)) {
        result(it.getBoolean("granted"),it.getString("error").orEmpty())
    }
    fun authorize(value: Boolean) { authorized = value; subscribe() }
    fun foreground(value: Boolean) { foreground = value; subscribe() }
    fun configure() = subscribe()
    private fun subscribe() {
        val ready = connected && authorized && foreground && enabled
        val keys = if (ready) (requestedKeys?.invoke() ?: RecordVehicleAdapter.selected(settings()).flatMap(RecordVehicleAdapter::keys)).distinct().filter { it in known }.sorted() else emptyList()
        val requested = settings().vehicleIntervalMs
        val interval = maxOf(requested, serviceMinimumMs)
        model.setInterval(interval)
        model.connected(ready, when { !connected -> "等待 AutoService 连接"; !enabled -> "AutoService 车辆数据已关闭"; !authorized -> "等待权限准备"; !foreground -> "后台已暂停订阅"; interval > requested -> "请求${requested}ms，当前服务最小${interval}ms（更新 AutoService 后生效）"; else -> "" })
        if (connected && (keys != sentKeys || interval != sentInterval)) {
            client.setVehicleFeed(keys, interval)
            sentKeys = keys; sentInterval = interval
            if (keys.isNotEmpty()) client.getVehicleValues(keys) { if (authorized && foreground && enabled) accept(it) }
        }
    }
    @Synchronized private fun accept(values: Bundle) {
        val at = SystemClock.elapsedRealtime()
        if (statsAt == 0L) statsAt = at
        ageMax = maxOf(ageMax, (at-values.getLong("at", at)).coerceAtLeast(0))
        model.accept(values)
        if (recordUpdates) synchronized(updates) {
            if (updates.size >= 512) historyOverflow = true
            else updates.addLast(RecordedVehicleUpdate(SystemClock.elapsedRealtimeNanos(), model.snapshot()))
        }
        onChanged()
        uiMax = maxOf(uiMax, SystemClock.elapsedRealtime()-at)
        received++
        if (at-statsAt >= 5000) {
            android.util.Log.i("AutoRecordFeed", "推送 ${received}包/${at-statsAt}ms 采集至回调最大${ageMax}ms 回调处理最大${uiMax}ms")
            statsAt = at; received = 0; ageMax = 0; uiMax = 0
        }
    }
    override fun snapshot() = model.snapshot()
    override fun drainRecordedUpdates(): List<RecordedVehicleUpdate>? = if (!recordUpdates) null else synchronized(updates) {
        check(!historyOverflow) { "vehicle_record_queue_exhausted" }
        updates.toList().also { updates.clear() }
    }
    override fun setInterval(intervalMs: Int) { model.setInterval(intervalMs); subscribe() }
    override fun release() { authorized = false; model.release(); client.close() }
    companion object {
        /** 投屏关闭不是车辆连接断开；observer 不能被其他 App 的 surface_closed 连带停用。 */
        fun linkFailure(state: String): Boolean = state in setOf("service_disconnected", "binding_rejected", "binding_died", "service_unavailable") ||
            state.startsWith("connect:") || state.startsWith("bind:") || state == "ipc:DeadObjectException"
    }
}
