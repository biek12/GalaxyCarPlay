package com.ecarx.autoservice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.*
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract
import com.ecarx.autoservice.platform.QnxCredentials

/** 偶尔使用的诊断入口。所有连接/Shell仍由服务执行，页面不拥有第二个连接器。 */
class DiagnosticActivity : Activity() {
    private var client: AutoClient? = null
    private lateinit var result: TextView
    private var destroyed = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 28, 28, 28) }
        result = TextView(this).apply { textSize = 16f }
        root.addView(result)
        fun button(title: String, action: () -> Unit) { root.addView(Button(this).apply { text = title; setOnClickListener { action() } }) }
        button("重新初始化") { startService(Intent(this, AutoService::class.java).setAction("prepare")) }
        val input = EditText(this).apply { hint = "诊断Shell命令"; maxLines = 4 }
        root.addView(input)
        for (transport in listOf("ROOT", "ADB")) button("执行 $transport") {
            if (input.text.isNotBlank()) client?.executeShell(transport, input.text.toString()) { value ->
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
        setContentView(ScrollView(this).apply { addView(root) })
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
