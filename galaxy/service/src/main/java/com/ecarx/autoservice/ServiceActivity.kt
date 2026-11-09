package com.ecarx.autoservice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import com.ecarx.autoservice.settings.ServiceFeature
import com.ecarx.autoservice.settings.ServiceFeatures
import com.ecarx.autoservice.startup.AutoStartup
import com.ecarx.autoservice.ui.FeatureRow
import com.ecarx.autoservice.ui.ServiceUi
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract
import org.json.JSONObject

/** 页面只修改持久设置、观察后台；离开界面不改变连接或输出所有权。 */
class ServiceActivity : Activity() {
    private var client: AutoClient? = null
    private lateinit var ui: ServiceUi
    private lateinit var headline: TextView
    private lateinit var summary: TextView
    private lateinit var badge: TextView
    private lateinit var automatic: FeatureRow
    private val rows = linkedMapOf<ServiceFeature, FeatureRow>()
    private val metrics = mutableListOf<TextView>()
    private var snapshot = Bundle()
    private var connected = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ui = ServiceUi(this)
        buildPage()
        render()
        client = AutoClient(this, AutoContract.OBSERVER, onSnapshot = {
            snapshot = it; connected = true; render()
        }, onState = {
            if (it.startsWith("bind:") || it.startsWith("connect:") || it in setOf("service_disconnected", "binding_died", "service_unavailable")) {
                connected = false; render()
            }
        }).also { it.connect() }
    }

    private fun buildPage() {
        val compact = resources.configuration.screenWidthDp < 600
        val root = ui.column().apply { setBackgroundColor(ui.background) }
        val header = ui.column(if (compact) 24 else 32).apply { background = ui.gradient() }
        header.addView(ui.label("车辆基础服务", 14f, true))
        val title = ui.row()
        title.addView(ui.label("AutoService", 32f), LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(ui.button("全部关闭") { disableAll() })
        header.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        header.addView(ui.label("管理连接、车辆数据与仪表输出", 16f, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        root.addView(header)

        val content = ui.column(if (compact) 20 else 32)
        content.addView(ui.button("车辆与账号") {
            startActivity(Intent(this,com.galaxy.auto.client.vehicle.VehicleAccountActivity::class.java))
        })
        val overview = ui.card(24)
        val heading = ui.row()
        headline = ui.label("后台运行中", 25f)
        heading.addView(headline, LinearLayout.LayoutParams(0, -2, 1f))
        badge = ui.label("正在连接", 13f).apply {
            setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7)); gravity = Gravity.CENTER
        }
        heading.addView(badge)
        overview.addView(heading)
        summary = ui.label("", 15f, true)
        overview.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        val metricRow = ui.row()
        for ((index, name) in listOf("仪表投屏", "本机 ADB", "Root").withIndex()) {
            val metric = ui.column(14).apply { background = ui.rounded(ui.navigation) }
            metric.addView(ui.label(name, 13f, true))
            val value = ui.label("待连接", 18f)
            metric.addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
            metrics.add(value)
            metricRow.addView(metric, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = ui.dp(10) })
        }
        overview.addView(metricRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(22) })
        content.addView(overview)

        section(content, "后台运行")
        automatic = FeatureRow(ui, "自动运行", "开机启动，异常退出后自动恢复", "automatic") { enabled ->
            AutoStartup.setEnabled(this, enabled)
            dispatch(if (enabled) "start" else "stop")
        }
        content.addView(ui.card(0).apply { addView(automatic) })

        section(content, "功能开关")
        val features = ui.card(0)
        val definitions = listOf(
            Triple(ServiceFeature.PROJECTION, "仪表投屏", "将地图、音乐与主题显示到仪表"),
            Triple(ServiceFeature.VEHICLE, "车辆数据", "只读采集车辆状态，供仪表等应用使用"),
            Triple(ServiceFeature.ADB, "本机 ADB", "管理 AutoService 自身的本机调试连接"),
            Triple(ServiceFeature.ROOT, "Root 服务", "为车辆读取与仪表接收端准备提供权限"),
        )
        for ((index, definition) in definitions.withIndex()) {
            if (index > 0) features.addView(ui.divider(), LinearLayout.LayoutParams(-1, ui.dp(1)).apply { marginStart = ui.dp(84); marginEnd = ui.dp(20) })
            val (feature, title, description) = definition
            val row = FeatureRow(ui, title, description, feature.name.lowercase()) { enabled ->
                ServiceFeatures.set(this, feature, enabled)
                dispatch(ServiceFeatures.ACTION_CHANGED)
            }
            rows[feature] = row; features.addView(row)
        }
        content.addView(features)
        content.addView(ui.label("关闭后保存选择，后台恢复时继续遵守设置。", 13f, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(14); marginStart = ui.dp(4) })

        section(content, "更多")
        val diagnostics = ui.card(20).apply {
            val row = ui.row()
            val text = ui.column()
            text.addView(ui.label("诊断与日志", 21f))
            text.addView(ui.label("连接详情、运行日志与高级诊断", 14f, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(7) })
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ui.label("›", 30f, true)); addView(row)
            setOnClickListener { startActivity(Intent(this@ServiceActivity, DiagnosticActivity::class.java)) }
            isFocusable = true
        }
        content.addView(diagnostics)
        section(content,"远程连接")
        val remoteCard=ui.card(20)
        remoteCard.addView(ui.label("验证码连接 · 免重复验证",21f))
        remoteCard.addView(ui.label("车机主动联网，在授权时间内可诊断和更新应用",14f,true))
        val remoteStatus=ui.label("",17f)
        remoteCard.addView(remoteStatus)
        val remoteButtons=ui.row()
        for(hours in listOf(12,24))remoteButtons.addView(ui.button("开启${hours}小时"){
            startForegroundService(Intent(this,AutoService::class.java).setAction("remote-authorize").putExtra("hours",hours))
        },LinearLayout.LayoutParams(0,-2,1f))
        remoteButtons.addView(ui.button("关闭"){
            startForegroundService(Intent(this,AutoService::class.java).setAction("remote-disable"))
        },LinearLayout.LayoutParams(0,-2,1f))
        remoteCard.addView(remoteButtons);content.addView(remoteCard)
        remoteStatus.post(object:Runnable{
            override fun run(){
                if(isFinishing||isDestroyed)return
                val settings=com.ecarx.autoservice.remote.RemoteSettings(this@ServiceActivity)
                val connection = com.ecarx.autoservice.remote.RemoteConnectionStatus.label(snapshot.getString("remoteState"))
                remoteStatus.text=when{
                    !settings.enabled->"远程连接已关闭"
                    settings.paired->"已配对 · $connection · 免验证至 ${android.text.format.DateFormat.format("MM-dd HH:mm",settings.until*1000)}"
                    snapshot.getString("remoteState") in setOf("tls_initialization_failed", "tls_failed", "reconnecting")->connection
                    settings.code.isNotBlank()&&settings.codeUntil<System.currentTimeMillis()/1000->"验证码已过期，请重新开启授权"
                    settings.code.isNotBlank()->"验证码：${settings.code}（5分钟内有效）"
                    else->connection
                }
                remoteStatus.postDelayed(this,1000)
            }
        })
        content.addView(ui.label("AutoService · GalaxyPlay", 12f, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(24) })
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(ui.label(title, 15f, true), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = ui.dp(28); bottomMargin = ui.dp(12); marginStart = ui.dp(4)
        })
    }
    private fun disableAll() {
        com.ecarx.autoservice.remote.RemoteSettings(this).disable()
        ServiceFeatures.disableAll(this)
        AutoStartup.cancel(this)
        dispatch("stop")
    }
    private fun dispatch(action: String) {
        runCatching { startForegroundService(Intent(this, AutoService::class.java).setAction(action)) }
            .onFailure {
                ServiceTrace.event("settings_dispatch_failed", it)
                Toast.makeText(this, "设置已保存，后台暂未响应，请查看诊断", Toast.LENGTH_LONG).show()
            }
        render()
    }

    private fun render() {
        val enabled = AutoStartup.enabled(this)
        val features = ServiceFeatures.read(this)
        val foundation = runCatching { JSONObject(snapshot.getString("foundation") ?: "{}") }.getOrElse { JSONObject() }
        val adbReady = features.adb && connected && foundation.optString("state") in setOf("verified", "adb_connected")
        val rootReady = features.root && connected && snapshot.getBoolean("rootReady")
        val projectionReady = features.projection && connected && snapshot.getBoolean("qnxReady")
        headline.text = when { !enabled -> "后台已暂停"; !connected -> "等待后台连接"; else -> "后台运行中" }
        summary.text = when {
            !enabled -> "功能选择已保留，开启自动运行后生效"
            !connected -> "正在读取服务状态"
            else -> "${snapshot.getString("model", "L6")} · 车辆数据与仪表服务按需运行"
        }
        val ready = enabled && connected
        badge.text = when { !enabled -> "已暂停"; ready -> "服务已连接"; else -> "连接中" }
        badge.setTextColor(if (ready) ui.success else ui.secondary)
        badge.background = ui.rounded(if (ready) ui.accentSoft else ui.navigation)
        fun state(requested: Boolean, ready: Boolean, waiting: String) = when {
            !enabled || !requested -> "已关闭"
            ready -> "已连接"
            else -> waiting
        }
        metrics[0].text = state(features.projection, projectionReady, if (snapshot.getBoolean("projectionRequested")) "连接中" else "待投屏")
        metrics[1].text = state(features.adb, adbReady, "待连接")
        metrics[2].text = state(features.root, rootReady, "待就绪")
        automatic.bind(enabled, "开机启动，异常退出后自动恢复；关闭即暂停全部后台工作")
        val pending = "已开启，后台暂停时不运行"
        rows[ServiceFeature.PROJECTION]?.bind(features.projection, when {
            !features.projection -> "已关闭，停止仪表输出；本地主题预览仍可使用"
            !enabled -> pending
            projectionReady -> "仪表通道已连接，正在输出画面"
            !features.root -> "优先直接连接；Root 关闭时不自动准备接收端"
            else -> "按需输出地图、音乐与主题，自动准备接收端"
        })
        rows[ServiceFeature.VEHICLE]?.bind(features.vehicle, when {
            !features.vehicle -> "已关闭，停止采集与车辆订阅"
            !enabled -> pending
            connected && snapshot.getInt("vehiclePropertyCount") > 0 -> "${snapshot.getInt("vehicleValidCount")} 项有效 · ${snapshot.getInt("vehicleSampledCount")} / ${snapshot.getInt("vehiclePropertyCount")} 已读取"
            !features.root -> "只读采集普通应用身份可取得的数据"
            else -> "只读采集车辆状态，等待数据返回"
        })
        rows[ServiceFeature.ADB]?.bind(features.adb, when {
            !features.adb -> "已关闭本 App 的 ADB 连接与自动重连"
            !enabled -> pending
            adbReady -> "本机连接已核验 · UID ${foundation.optInt("adbUid", -1)} · TLS ${foundation.optInt("tlsPort")}"
            foundation.optString("state") == "failed" -> "连接尚未完成，可在诊断中查看原因"
            else -> "使用本 App 身份连接本机调试通道"
        })
        rows[ServiceFeature.ROOT]?.bind(features.root, when {
            !features.root -> "已关闭自有 Root 服务，不再自动获取或恢复"
            !enabled -> pending
            rootReady -> "自有服务身份已核验 · UID 0"
            !features.adb -> "通过已核验的本机入口准备，不打开 ADB"
            else -> "为车辆读取与仪表接收端准备提供权限"
        })
    }
    override fun onResume() { super.onResume(); if (::ui.isInitialized) render() }
    override fun onDestroy() { client?.close(); client = null; super.onDestroy() }
}
