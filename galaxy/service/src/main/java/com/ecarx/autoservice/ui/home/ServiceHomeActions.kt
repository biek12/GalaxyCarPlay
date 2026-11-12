package com.ecarx.autoservice.ui.home

import android.app.Activity
import android.content.Intent
import com.ecarx.autoservice.AutoService
import com.ecarx.autoservice.DiagnosticActivity
import com.ecarx.autoservice.R
import com.ecarx.autoservice.ServiceTrace
import com.ecarx.autoservice.remote.RemoteSettings
import com.ecarx.autoservice.settings.NativeWifiSettings
import com.ecarx.autoservice.settings.ServiceFeature
import com.ecarx.autoservice.settings.ServiceFeatures
import com.ecarx.autoservice.settings.ServicePageCommands
import com.ecarx.autoservice.startup.AutoStartup
import com.galaxy.auto.client.vehicle.VehicleAccountActivity

/**
 * 首页用户操作：只有真实用户事件才产生副作用。
 *
 * 复用既有 [ServiceFeatures]/[AutoStartup]/[RemoteSettings]/[NativeWifiSettings]，
 * 保持“先持久化再通知服务”的顺序；命令发送失败时写 ServiceTrace 并提示资源化 Toast，
 * 不把失败吞成成功。每次动作结束后回调 [onChanged] 让 Presenter 重新读取真实配置。
 *
 * 线程：全部主线程；[activity] 只用于启动服务/页面与读取偏好。
 */
internal class ServiceHomeActions(
    private val activity: Activity,
    private val onChanged: () -> Unit,
    private val onFeedback: (Int) -> Unit,
) {
    /** 自动运行开关：先落盘，再按新状态发 START/STOP。 */
    fun setAutomatic(enabled: Boolean) {
        AutoStartup.setEnabled(activity, enabled)
        dispatch(if (enabled) ServicePageCommands.START else ServicePageCommands.STOP)
    }

    /** 单个功能开关：先落盘，再通知服务应用功能变化。 */
    fun setFeature(feature: ServiceFeature, enabled: Boolean) {
        ServiceFeatures.set(activity, feature, enabled)
        dispatch(ServiceFeatures.ACTION_CHANGED)
    }

    /** 全部关闭：顺序固定为远程禁用 → 功能全关 → 取消自动恢复 → STOP。 */
    fun disableAll() {
        RemoteSettings(activity).disable()
        ServiceFeatures.disableAll(activity)
        AutoStartup.cancel(activity)
        dispatch(ServicePageCommands.STOP)
    }

    /** 打开车辆与账号页；类型导航，不使用反射或字符串类名。 */
    fun openAccount() = activity.startActivity(Intent(activity, VehicleAccountActivity::class.java))

    /** 打开诊断页。 */
    fun openDiagnostics() = activity.startActivity(Intent(activity, DiagnosticActivity::class.java))

    /** 打开系统 Wi-Fi 页面；没有可用入口时给出资源化反馈。 */
    fun openWifi() {
        if (!NativeWifiSettings.open(activity)) onFeedback(R.string.service_wifi_unavailable)
    }

    /** 开启 12/24 小时远程授权；小时数进入既有 `hours` extra。 */
    fun authorizeRemote(hours: Int) {
        dispatchService(
            Intent(activity, AutoService::class.java)
                .setAction(ServicePageCommands.REMOTE_AUTHORIZE)
                .putExtra(ServicePageCommands.EXTRA_HOURS, hours),
        )
    }

    /** 关闭远程授权。 */
    fun disableRemote() {
        dispatchService(
            Intent(
                activity,
                AutoService::class.java,
            ).setAction(ServicePageCommands.REMOTE_DISABLE),
        )
    }

    /** 发送仅含 action 的前台服务命令。 */
    private fun dispatch(action: String) {
        dispatchService(Intent(activity, AutoService::class.java).setAction(action))
    }

    /** 统一发送前台服务命令；失败记录原因并提示，成功后刷新页面读数。 */
    private fun dispatchService(intent: Intent) {
        runCatching { activity.startForegroundService(intent) }
            .onFailure {
                ServiceTrace.event("settings_dispatch_failed", it)
                onFeedback(R.string.service_dispatch_failed)
            }
        onChanged()
    }
}
