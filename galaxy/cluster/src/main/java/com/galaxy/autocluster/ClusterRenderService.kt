package com.galaxy.autocluster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.autocluster.runtime.ClusterDisplayStore
import com.galaxy.autocluster.runtime.VisualMusicSession
import java.io.File
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * 原厂元素增强的持久运行时，独立于 Activity。
 *
 * 旧整屏覆盖投屏已归档：服务不再创建主题库 / 内置主题 / `ClusterSceneOutput`，
 * 也不接受 apply/reload/sync-energy/panel
 * 等旧整屏命令。只有**已显式提交且 mode 为 OEM_ELEMENTS** 的方案才配置并连接
 * ELEMENTS 租约；旧 OVERLAY、未知模式、旧 `enabled=true`、以及空 Intent 恢复旧模式
 * 一律不建 client/输出，停止并返回 START_NOT_STICKY。
 *
 * 原厂音乐卡片所用 [VisualMusicSession] 与 ELEMENTS client 的配置/释放按原合同保留。
 * 线程/生命周期：全部主线程；前台通知在 onCreate 建立，onDestroy 按 client→music→
 * 前台通知顺序释放，不触碰任何旧整屏 Surface。
 */
class ClusterRenderService : Service() {
    private var music: VisualMusicSession? = null
    private var elements: AutoClient? = null
    private var elementConfiguration: ClusterDisplayConfiguration? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                "cluster",
                getString(R.string.cluster_service_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, ClusterActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        startForeground(
            72,
            Notification
                .Builder(this, "cluster")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(getString(R.string.cluster_app_name))
                .setContentText(getString(R.string.cluster_service_running))
                .setContentIntent(open)
                .build(),
        )
    }

    /**
     * 每次启动都重新读取持久方案：只有真实已启用的 OEM 元素方案才继续运行。
     * 撤销、旧模式与未知命令都只做停止/释放，不因 CARPLAY MAP 在线而接管旧地图输出。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = ClusterDisplayStore(this)
        val configuration = store.read()
        val oemActive =
            intent?.action != "stop" &&
                store.enabled &&
                configuration.mode == ClusterDisplayConfiguration.OEM_ELEMENTS
        if (!oemActive) {
            // 归档/撤销：先释放本 App 现有增强，再通知 AutoService 撤销元素租约，最后停服。
            elements?.close()
            elements = null
            elementConfiguration = null
            music?.close()
            music = null
            startForegroundService(
                Intent("theme-stop").setComponent(
                    ComponentName(AutoContract.SERVICE_PACKAGE, AutoContract.SERVICE_CLASS),
                ),
            )
            stopSelf()
            return START_NOT_STICKY
        }
        if (music == null) music = VisualMusicSession(this)
        val client = elements ?: AutoClient(this, AutoContract.ELEMENTS).also { elements = it }
        if (elementConfiguration != configuration) {
            client.configureClusterElements(
                configuration,
                if (configuration.background == ClusterDisplayConfiguration.IMAGE_BACKGROUND) {
                    File(filesDir, "display-backgrounds/${configuration.image}")
                } else {
                    null
                },
            )
            elementConfiguration = configuration
        }
        client.setActive(configuration.needsElements)
        client.connect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 只报告真实 OEM 元素运行状态，不再暴露旧整屏输出指标。 */
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        writer.println("AutoCluster OEM elements ${if (elements == null) "inactive" else "active"}")
    }

    override fun onDestroy() {
        elements?.close()
        elements = null
        music?.close()
        music = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
