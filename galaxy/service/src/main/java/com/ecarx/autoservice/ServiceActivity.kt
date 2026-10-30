package com.ecarx.autoservice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.*
import com.ecarx.autoservice.startup.AutoStartup
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract

/** 日常仅观察后台状态。退出页面解除观察，不持有ADB/Root连接或停止后台。 */
class ServiceActivity : Activity() {
    private var client: AutoClient? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        val title = TextView(this).apply { text = "AutoService"; textSize = 26f }
        val status = TextView(this).apply { text = "正在读取后台状态"; textSize = 18f }
        val automatic = Switch(this).apply { text = "自动运行"; isChecked = AutoStartup.enabled(this@ServiceActivity) }
        automatic.setOnCheckedChangeListener { _, enabled ->
            AutoStartup.setEnabled(this, enabled)
            startForegroundService(Intent(this, AutoService::class.java).setAction(if (enabled) "start" else "stop"))
        }
        root.addView(title); root.addView(status); root.addView(automatic)
        root.addView(Button(this).apply { text = "诊断"; setOnClickListener { startActivity(Intent(this@ServiceActivity, DiagnosticActivity::class.java)) } })
        setContentView(root)
        client = AutoClient(this, AutoContract.OBSERVER, onSnapshot = { value ->
            status.text = "Root：${if (value.getBoolean("rootReady")) "就绪" else "准备中"}  QNX：${if (value.getBoolean("qnxReady")) "就绪" else "按需准备"}\n${value.getString("status")}\n车辆目录：${value.getInt("vehicleSampledCount")} / ${value.getInt("vehiclePropertyCount")}"
        }, onState = { status.text = it }).also { it.connect() }
    }
    override fun onDestroy() { client?.close(); client = null; super.onDestroy() }
}
