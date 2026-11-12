package com.ecarx.autoservice.ui.home

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import com.ecarx.autoservice.R
import com.ecarx.autoservice.databinding.ActivityServiceBinding
import com.ecarx.autoservice.remote.RemoteSettings
import com.ecarx.autoservice.settings.ServiceFeature
import com.ecarx.autoservice.settings.ServiceFeatures
import com.ecarx.autoservice.startup.AutoStartup
import com.ecarx.autoservice.ui.ServicePageSession
import com.galaxy.auto.ui.GalaxySettingRow
import com.galaxy.auto.ui.GalaxyStyle
import com.galaxy.auto.ui.GalaxyViews

/**
 * 首页唯一 owner：持有观察者、远程状态 ticker 与页面刷新。
 *
 * 生命周期：`start` 建一个 observer/连接并绑定一次事件，`stop` 移除 ticker 并关闭连接，
 * 两者的代次使迟到回调失效；`restart` 只重新观察，不启动任何后台输出。
 * 事件只在此绑定一次，避免重建页面时重复注册监听。
 *
 * 线程：全部主线程。ticker 仅在前台每 1000ms 读一次显示状态并刷新远程文案，
 * 不重建整页、不发授权或网络动作。
 */
internal class ServiceHomePresenter(
    private val activity: Activity,
    private val binding: ActivityServiceBinding,
    private val sessionFactory: (Context, (Bundle) -> Unit, (String) -> Unit) -> ServicePageSession,
    private val now: () -> Long = System::currentTimeMillis,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val context: Context = activity
    private val actions = ServiceHomeActions(activity, ::refresh, ::showFeedback)

    // 旧整屏投屏开关已归档：首页只保留车辆/ADB/Root 三个现行功能开关。
    private val featureRows: Map<ServiceFeature, GalaxySettingRow> =
        linkedMapOf(
            ServiceFeature.VEHICLE to binding.serviceFeatures.serviceVehicle,
            ServiceFeature.ADB to binding.serviceFeatures.serviceAdb,
            ServiceFeature.ROOT to binding.serviceFeatures.serviceRoot,
        )
    private var session: ServicePageSession? = null
    private var snapshot = Bundle()
    private var connected = false
    private var started = false
    private var destroyed = false
    private var generation = 0

    /** 前台节拍：只刷新远程状态文案。 */
    private val ticker =
        object : Runnable {
            override fun run() {
                if (!started || destroyed) return
                updateRemoteStatus()
                handler.postDelayed(this, REMOTE_TICK_MS)
            }
        }

    init {
        bindActions()
    }

    /** 开始观察：创建连接、读取既有设置并展示等待态；重复调用无副作用。 */
    fun start() {
        if (started || destroyed) return
        started = true
        val ticket = ++generation
        val next =
            sessionFactory(
                context,
                { value ->
                    if (started && !destroyed && ticket == generation) {
                        snapshot = value
                        connected = true
                        refresh()
                    }
                },
                { state ->
                    if (started && !destroyed && ticket == generation && state.isDisconnected()) {
                        connected = false
                        refresh()
                    }
                },
            )
        session = next
        next.connect()
        refresh()
        handler.postDelayed(ticker, REMOTE_TICK_MS)
    }

    /** 停止观察：移除 ticker、关闭连接并失效代次；不等于停止后台服务。 */
    fun stop() {
        if (!started) return
        started = false
        ++generation
        handler.removeCallbacks(ticker)
        session?.close()
        session = null
        connected = false
    }

    /** 页面销毁：先停止观察，再拒绝一切迟到回调。 */
    fun destroy() {
        stop()
        destroyed = true
    }

    /** 页面恢复可见时重读设置；返回车辆账号页后开关与就绪状态需要重新计算。 */
    fun refresh() {
        if (destroyed) return
        val automatic = AutoStartup.enabled(context)
        val features = ServiceFeatures.read(context)
        val wifiEnabled =
            runCatching {
                context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled
            }.getOrNull()
        val state =
            ServiceHomeState.from(
                context.resources,
                automatic,
                features,
                snapshot,
                connected,
                wifiEnabled,
                currentRemote(),
                now(),
            )
        apply(state)
    }

    /** 把状态写入真实控件；开关回填不触发业务回调。 */
    private fun apply(state: ServiceHomeState) {
        binding.serviceOverview.serviceHeadline.setText(state.headline)
        binding.serviceOverview.serviceBadge.setText(state.badge)
        binding.serviceOverview.serviceBadge.setBackgroundResource(
            if (state.badgeReady) R.drawable.service_badge_ready else R.drawable.service_badge_waiting,
        )
        binding.serviceOverview.serviceBadge.setTextColor(
            context.getColor(if (state.badgeReady) R.color.service_success else R.color.service_secondary),
        )
        binding.serviceOverview.serviceSummary.text = state.summary
        // 首项由 XML 固定为“整屏投屏已归档”只读文案；不回填任何准备/就绪状态。
        binding.serviceOverview.serviceAdbState.setText(state.adbState)
        binding.serviceOverview.serviceRootState.setText(state.rootState)
        binding.serviceWifiHelp.root.visibility = if (state.wifiHelpVisible) View.VISIBLE else View.GONE
        binding.serviceWifiHelp.serviceWifiMessage.setText(state.wifiMessage)
        binding.serviceRemote.serviceRemoteStatus.text = state.remoteStatus
        binding.serviceFeatures.serviceAutomatic.setSwitchChecked(state.automatic)
        binding.serviceFeatures.serviceAutomatic.setDescription(context.getString(state.automaticDescription))
        featureRows.forEach { (feature, row) ->
            row.setSwitchChecked(state.features.enabled(feature))
            row.setDescription(descriptionFor(state, feature).toString())
        }
    }

    /** 各功能行的说明文案：车辆与 ADB 行带运行计数，其余为固定资源。 */
    private fun descriptionFor(state: ServiceHomeState, feature: ServiceFeature): CharSequence =
        when (feature) {
            ServiceFeature.VEHICLE -> state.vehicleDescription
            ServiceFeature.ADB -> state.adbDescription
            ServiceFeature.ROOT -> context.getString(state.rootDescription)
            else -> error("retired_projection_feature")
        }

    /** ticker 只更新远程文案，页面其他区域保持不动。 */
    private fun updateRemoteStatus() {
        if (destroyed) return
        binding.serviceRemote.serviceRemoteStatus.text = remoteStatusText(context.resources, currentRemote(), now())
    }

    /** 读取远程显示字段；不含 secret/grant/origin，也不触发网络。 */
    private fun currentRemote(): ServiceRemoteDisplay {
        val settings = RemoteSettings(context)
        return ServiceRemoteDisplay(
            enabled = settings.enabled,
            paired = settings.paired,
            untilSeconds = settings.until,
            code = settings.code,
            codeUntil = settings.codeUntil,
            state = snapshot.getString("remoteState"),
        )
    }

    /** 绑定一次点击与开关插入；整行点击由公共组件转发，禁用不响应。 */
    private fun bindActions() {
        binding.serviceHeader.serviceDisableAll.setOnClickListener { actions.disableAll() }
        binding.serviceAccount.setOnClickListener { actions.openAccount() }
        binding.serviceWifiHelp.serviceOpenWifi.setOnClickListener { actions.openWifi() }
        binding.serviceRemote.serviceRemote12h.setOnClickListener { actions.authorizeRemote(12) }
        binding.serviceRemote.serviceRemote24h.setOnClickListener { actions.authorizeRemote(24) }
        binding.serviceRemote.serviceRemoteDisable.setOnClickListener { actions.disableRemote() }
        binding.serviceFeatures.serviceDiagnostics.apply {
            setAccessory(
                ImageView(context).apply { setImageResource(R.drawable.ic_service_arrow) },
                width = GalaxyViews.px(context, R.dimen.service_arrow_size),
                height = GalaxyViews.px(context, R.dimen.service_arrow_size),
                leading = false,
            )
            isFocusable = true
            GalaxyStyle.rowFeedback(this)
            setOnClickListener { actions.openDiagnostics() }
        }
        val slotWidth = GalaxyViews.px(binding.root.context, R.dimen.service_switch_slot)
        featureRows.forEach { (feature, row) ->
            GalaxyViews.attachSwitch(
                row,
                row.titleView.text.toString(),
                false,
                leading = false,
                slotWidth = slotWidth,
            ) { enabled -> actions.setFeature(feature, enabled) }
        }
        GalaxyViews.attachSwitch(
            binding.serviceFeatures.serviceAutomatic,
            binding.serviceFeatures.serviceAutomatic.titleView.text
                .toString(),
            false,
            leading = false,
            slotWidth = slotWidth,
        ) { enabled -> actions.setAutomatic(enabled) }
    }

    /** 资源缺失或系统入口不可用时给用户可见反馈。 */
    private fun showFeedback(message: Int) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    /** 观察者状态里属于“连接已失效”的固定取值。 */
    private fun String?.isDisconnected(): Boolean =
        this != null &&
            (
                startsWith("bind:") ||
                    startsWith("connect:") ||
                    this in setOf("service_disconnected", "binding_died", "service_unavailable")
            )

    private companion object {
        /** 远程状态轮询间隔；仅前台运行。 */
        const val REMOTE_TICK_MS = 1000L
    }
}
