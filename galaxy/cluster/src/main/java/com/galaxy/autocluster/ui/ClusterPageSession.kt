package com.galaxy.autocluster.ui

import android.content.Context
import android.os.Bundle
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract

/**
 * Cluster 页面与 AutoService 之间的最窄观察接口：只暴露建立/关闭观察连接两个动作。
 *
 * 页面与 Presenter 不直接持有 [AutoClient]；测试在 `ClusterActivity.sessionFactory`
 * 处注入记录型替身时仍驱动真实 Presenter 与真实布局。接口刻意保持最小，
 * 不提供缓存、重连、Shell 或车辆订阅等本页用不到的能力。
 */
internal interface ClusterPageSession {
    /** 建立观察者连接；必须主线程调用。 */
    fun connect()

    /** 关闭观察者连接；必须主线程调用，重复调用安全。 */
    fun close()
}

/**
 * 生产实现：只委托一个角色固定为 [AutoContract.OBSERVER] 的 [AutoClient]，
 * 快照与连接状态回调沿 AutoClient 既有的主线程交付语义，不做二次包装。
 *
 * 线程/所有者：实例由 Activity 装配并经 Presenter 在销毁时关闭；`connect`/`close` 必须主线程。
 */
internal class AutoClientClusterPageSession(
    context: Context,
    onSnapshot: (Bundle) -> Unit,
    onState: (String) -> Unit,
) : ClusterPageSession {
    private val client = AutoClient(context, AutoContract.OBSERVER, onSnapshot = onSnapshot, onState = onState)

    override fun connect() = client.connect()

    override fun close() = client.close()
}
