package com.galaxy.autocluster

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.os.*
import android.view.View
import android.widget.*
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract

/** 独立App的主题预览与页选择；预览使用真实服务状态，未知读数保持--。 */
class ClusterActivity : Activity() {
    private var client: AutoClient? = null
    private var snapshot = Bundle()
    private var panel = "MUSIC"
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 20) }
        val status = TextView(this).apply { textSize = 22f; text = "AutoCluster · 等待AutoService" }
        val renderer = ClusterRenderer()
        val preview = object : View(this) {
            override fun onDraw(canvas: Canvas) { renderer.render(canvas, snapshot, SystemClock.elapsedRealtime(), panel, null) }
        }
        root.addView(status)
        root.addView(preview, LinearLayout.LayoutParams(-1, resources.displayMetrics.widthPixels * 532 / 1920))
        for ((title, value) in listOf("音乐与歌词" to "MUSIC", "四轮胎压" to "TIRES", "油门 / 刹车 / 方向盘" to "PEDALS")) {
            root.addView(Button(this).apply { text = title; setOnClickListener {
                panel = value
                startForegroundService(Intent(this@ClusterActivity, ClusterRenderService::class.java).putExtra("panel", value))
                preview.invalidate()
            } })
        }
        root.addView(Button(this).apply { text = "停止独立仪表"; setOnClickListener {
            startForegroundService(Intent("theme-stop").setComponent(android.content.ComponentName(AutoContract.SERVICE_PACKAGE, AutoContract.SERVICE_CLASS)))
            stopService(Intent(this@ClusterActivity, ClusterRenderService::class.java))
        } })
        setContentView(root)
        startForegroundService(Intent(this, ClusterRenderService::class.java))
        client = AutoClient(this, AutoContract.OBSERVER, onSnapshot = {
            snapshot = it; status.text = "AutoCluster · ${it.getString("model")}\n${it.getString("status")}"; preview.invalidate()
        }, onState = { status.text = "AutoCluster · $it" }).also { it.connect() }
    }
    override fun onDestroy() { client?.close(); super.onDestroy() }
}
