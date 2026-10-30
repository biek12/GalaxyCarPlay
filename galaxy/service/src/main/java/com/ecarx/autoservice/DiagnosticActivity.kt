package com.ecarx.autoservice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.*
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract
import com.ecarx.autoservice.platform.QnxCredentials
import com.ecarx.autoservice.ui.ServiceUi
import com.ecarx.autoservice.settings.ServiceFeature
import com.ecarx.autoservice.settings.ServiceFeatures
import com.ecarx.autoservice.startup.AutoStartup

/** 偶尔使用的诊断入口。所有连接/Shell仍由服务执行，页面不拥有第二个连接器。 */
class DiagnosticActivity : Activity() {
    private var client: AutoClient? = null
    private lateinit var result: TextView
    private var destroyed = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ui = ServiceUi(this)
        val root = ui.column(24).apply { setBackgroundColor(ui.background) }
        val header = ui.row()
        header.addView(ui.button("返回") { finish() })
        header.addView(ui.label("诊断与日志", 26f), LinearLayout.LayoutParams(-1, -2).apply { marginStart = ui.dp(20) })
        root.addView(header)
        root.addView(ui.label("高级操作遵守首页功能开关，运行日志仅保存在本机。", 14f, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(18); bottomMargin = ui.dp(18) })
        result = ui.label("等待诊断操作", 15f).apply { setTextIsSelectable(true) }
        root.addView(ui.card().apply { addView(result) })
        fun button(title: String, action: () -> Unit) {
            root.addView(ui.button(title, action = action), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        }
        button("重新初始化已开启的功能") {
            if (AutoStartup.enabled(this)) startService(Intent(this, AutoService::class.java).setAction("prepare"))
            else result.text = "后台已暂停，请先在首页开启自动运行"
        }
        button("查看运行日志与异常栈") {
            result.text = listOf("app.log", "root.log").joinToString("\n\n") { name ->
                val file = java.io.File(filesDir, "diagnostics/$name")
                name + "\n" + if (file.exists()) file.readText().takeLast(16000) else "尚无记录"
            }
        }
        val input = EditText(this).apply {
            hint = "诊断 Shell 命令"; maxLines = 4; textSize = 17f
            setTextColor(ui.foreground); setHintTextColor(ui.secondary)
            background = ui.rounded(ui.surface); setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
        }
        root.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        for (transport in listOf("ROOT", "ADB")) button("执行 $transport") {
            val feature = if (transport == "ROOT") ServiceFeature.ROOT else ServiceFeature.ADB
            if (!AutoStartup.enabled(this) || !ServiceFeatures.read(this).enabled(feature)) {
                result.text = "$transport 已关闭，请先在首页开启"
            } else if (input.text.isNotBlank()) client?.executeShell(transport, input.text.toString()) { value ->
                result.text = "exit=${value.getInt("exitCode", -1)} timeout=${value.getBoolean("timedOut")}\n${value.getString("error").orEmpty()}\n${value.getString("output").orEmpty()}"
            }
        }
        var offset = 0
        button("车辆属性：下一页") { client?.vehiclePage(offset) { value ->
            val items = value.getParcelableArrayList<Bundle>("items").orEmpty()
            result.text = "属性 $offset / ${value.getInt("total")}\n" + items.joinToString("\n") {
                "${it.getString("name")} [${it.getInt("propertyId")}/${it.getInt("areaId")}] ${it.getString("status")}\n${it.getString("value")}"
            }
            offset = if (offset + items.size >= value.getInt("total")) 0 else offset + items.size
        } }
        button("覆盖QNX认证（诊断）") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 71)
        }
        for (model in listOf("L6", "L7")) button("配置车型 $model") {
            getSharedPreferences("platform", 0).edit().putString("model", model).apply()
            startService(Intent(this, AutoService::class.java).setAction("model"))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(ui.background); isFillViewport = true; addView(root) })
        client = AutoClient(this, AutoContract.OBSERVER, onState = { result.text = it }).also { it.connect() }
    }
    @Deprecated("平台文件选择器")
    override fun onActivityResult(request: Int, code: Int, data: Intent?) {
        super.onActivityResult(request, code, data)
        if (request == 71 && code == RESULT_OK) data?.data?.let { uri ->
            Thread({
                val ok = runCatching { QnxCredentials.store(this, checkNotNull(contentResolver.openInputStream(uri))) }.isSuccess
                runOnUiThread { if (!destroyed) {
                    result.text = if (ok) "诊断覆盖已保存" else "诊断覆盖失败"
                    if (ok) startService(Intent(this, AutoService::class.java).setAction("prepare"))
                } }
            }, "AutoService-import").start()
        }
    }
    override fun onDestroy() { destroyed = true; client?.close(); client = null; super.onDestroy() }
}
