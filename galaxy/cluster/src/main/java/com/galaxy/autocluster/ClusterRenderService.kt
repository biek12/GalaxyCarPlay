package com.galaxy.autocluster

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import android.view.Surface
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract

/** 仪表主题生产者；数据、地图和最终HAB输出均在AutoService，此进程只绘独立主题Surface。 */
class ClusterRenderService : Service() {
    private val thread = HandlerThread("AutoCluster-render").apply { start() }
    private val worker = Handler(thread.looper)
    private val renderer = ClusterRenderer()
    private var snapshot = Bundle()
    private var target: Surface? = null
    private var art: Bitmap? = null
    private var artVersion = -1L
    private var client: AutoClient? = null
    @Volatile private var closed = false
    private var drawPending = false
    private val draw = object : Runnable {
        override fun run() {
            drawPending = false
            if (closed) return
            val surface = target
            if (surface?.isValid == true) runCatching {
                val canvas = surface.lockCanvas(null)
                try { renderer.render(canvas, snapshot, SystemClock.elapsedRealtime(), getSharedPreferences("theme", 0).getString("panel", "MUSIC")!!, art) }
                finally { surface.unlockCanvasAndPost(canvas) }
            }
            if (surface?.isValid == true && snapshot.getBundle("media")?.getBoolean("playing") == true) requestDraw(100)
        }
    }
    private fun requestDraw(delay: Long = 0) {
        if (closed || target?.isValid != true || drawPending) return
        drawPending = true; worker.postDelayed(draw, delay)
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("cluster", "仪表显示", NotificationManager.IMPORTANCE_LOW))
        startForeground(72, Notification.Builder(this, "cluster").setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("AutoCluster").setContentText("独立仪表主题运行中").build())
        client = AutoClient(this, AutoContract.THEME,
            onSurface = { surface ->
                val copy = surface?.let { original ->
                    val parcel = Parcel.obtain()
                    try { original.writeToParcel(parcel, 0); parcel.setDataPosition(0); Surface.CREATOR.createFromParcel(parcel) }
                    finally { parcel.recycle() }
                }
                worker.post { target?.release(); target = copy; worker.removeCallbacks(draw); drawPending = false; requestDraw() }
            },
            onSnapshot = { state -> worker.post {
                snapshot = state
                requestDraw()
                val version = state.getLong("artworkVersion")
                if (version != artVersion) {
                    artVersion = version
                    val next = runCatching {
                        val uri = Uri.parse(state.getString("artworkUri"))
                        val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    }.getOrNull()
                    art?.recycle(); art = next
                }
            } }, onState = { state -> android.util.Log.i("AutoCluster", state) })
            .also { it.connect(); it.setActive(true) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            startForegroundService(Intent("theme-stop").setComponent(android.content.ComponentName(AutoContract.SERVICE_PACKAGE, AutoContract.SERVICE_CLASS)))
            stopSelf(); return START_NOT_STICKY
        }
        intent?.getStringExtra("panel")?.takeIf { it in setOf("MUSIC", "TIRES", "PEDALS") }?.let {
            getSharedPreferences("theme", 0).edit().putString("panel", it).apply()
            worker.post { requestDraw() }
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        closed = true; client?.close(); client = null
        worker.removeCallbacksAndMessages(null)
        worker.post { target?.release(); target = null; art?.recycle(); art = null; thread.quitSafely() }
        super.onDestroy()
    }
}
