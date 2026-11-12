package com.ecarx.autoservice.ui

import android.content.Context
import android.os.Bundle
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract

/**
 * 首页与诊断页共用的窄接口：只暴露这两页真正需要的 AutoClient 能力。
 *
 * 页面不直接持有 [AutoClient]，测试注入记录型 fixture 时仍驱动真实 Presenter/Controller
 * 与真实布局。接口刻意保持最小，不提供缓存、重连或新协议。
 */
internal interface ServicePageSession {
    /** 建立观察者连接；必须主线程调用。 */
    fun connect()

    /** 关闭观察者连接；必须主线程调用，重复调用安全。 */
    fun close()

    /** 通过服务执行一次 Shell；结果回到主线程。 */
    fun executeShell(transport: String, command: String, result: (Bundle) -> Unit)

    /** 开启/关闭全目录诊断扫描。 */
    fun setVehicleScan(enabled: Boolean)

    /** 读取一页车辆属性；结果回到主线程。 */
    fun vehiclePage(offset: Int, result: (Bundle) -> Unit)
}

/**
 * 生产实现：只委托一个 `AutoClient`，角色固定 OBSERVER，Shell 超时保持原 10000ms。
 *
 * 线程/所有者：实例由页面 owner 创建与关闭；`connect`/`close` 必须主线程。
 */
internal class AutoClientPageSession(
    context: Context,
    onSnapshot: (Bundle) -> Unit,
    onState: (String) -> Unit,
) : ServicePageSession {
    private val client =
        AutoClient(
            context,
            AutoContract.OBSERVER,
            onSnapshot = onSnapshot,
            onState = onState,
        )

    override fun connect() = client.connect()

    override fun close() = client.close()

    override fun executeShell(transport: String, command: String, result: (Bundle) -> Unit) =
        client.executeShell(transport, command, SHELL_TIMEOUT_MS, result)

    override fun setVehicleScan(enabled: Boolean) = client.setVehicleScan(enabled)

    override fun vehiclePage(offset: Int, result: (Bundle) -> Unit) = client.vehiclePage(offset, result = result)

    private companion object {
        /** 与原诊断页一致的 Shell 超时。 */
        const val SHELL_TIMEOUT_MS = 10000
    }
}
