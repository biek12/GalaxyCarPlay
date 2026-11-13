package com.ecarx.autoservice.sentinel

import android.os.Bundle
import com.galaxy.auto.contract.IAutoResult

/**
 * 哨兵配置和空闲回报。
 *
 * 复用已经创建的 [SentinelCoordinator]，不另存电源、流或布防状态。
 * 开启仍受平台条件约束；关闭不被这些条件挡住。
 */
internal class ServiceSentinelRequests(
    private val postCallback: (Runnable) -> Unit,
    private val sentinel: () -> SentinelCoordinator?,
    private val paused: () -> Boolean,
    private val rootEnabled: () -> Boolean,
    private val vehicleEnabled: () -> Boolean,
    private val automatic: () -> Boolean,
) {
    /** 把电源序号交给当前协调器。协调器还没建立时不创建第二份。 */
    fun idle(powerSequence: Long) {
        sentinel()?.idle(powerSequence)
    }

    /**
     * 配置哨兵并组装原来的 accepted/error。
     *
     * enabled 为真时要求未暂停、Root、车辆和自动运行都可用。关闭不看这些条件。
     */
    fun configure(
        enabled: Boolean,
        result: IAutoResult,
    ) {
        val response = Bundle()
        runCatching {
            check(!enabled || !paused() && rootEnabled() && vehicleEnabled() && automatic()) { "platform_disabled" }
            checkNotNull(sentinel()).configure(enabled)
            response.putBoolean("accepted", true)
        }.onFailure {
            response.putBoolean("accepted", false)
            response.putString("error", it.message)
        }
        postCallback(Runnable { runCatching { result.onResult(response) } })
    }
}
