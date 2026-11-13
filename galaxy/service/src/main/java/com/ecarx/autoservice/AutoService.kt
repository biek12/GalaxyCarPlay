package com.ecarx.autoservice

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import com.ecarx.autoservice.diagnostics.DiagnosticUploader
import com.ecarx.autoservice.diagnostics.ServiceDiagnostics
import com.ecarx.autoservice.ipc.AutoServiceBinder
import com.ecarx.autoservice.ipc.ClientRegistry
import com.ecarx.autoservice.ipc.ServiceCallerIdentity
import com.ecarx.autoservice.ipc.ServiceClientSessions
import com.ecarx.autoservice.ipc.ServicePublication
import com.ecarx.autoservice.ipc.ServiceRequestQueue
import com.ecarx.autoservice.ipc.ServiceSnapshotComposer
import com.ecarx.autoservice.ipc.ServiceSnapshots
import com.ecarx.autoservice.media.ServiceMediaSession
import com.ecarx.autoservice.oem.ClusterElementsSession
import com.ecarx.autoservice.platform.foundation.ClientPermissionRequests
import com.ecarx.autoservice.platform.foundation.PlatformAccess
import com.ecarx.autoservice.platform.foundation.PlatformCoordinator
import com.ecarx.autoservice.platform.foundation.ServiceShellRequests
import com.ecarx.autoservice.remote.ServiceRemoteSession
import com.ecarx.autoservice.sentinel.SentinelCoordinator
import com.ecarx.autoservice.sentinel.ServiceSentinelRequests
import com.ecarx.autoservice.settings.ServiceFeatures
import com.ecarx.autoservice.startup.AutoStartup
import com.ecarx.autoservice.vehicle.ServiceVehicleSession
import com.galaxy.auto.client.diagnostics.DiagnosticEvents
import com.galaxy.auto.client.recovery.ProcessRecovery
import com.galaxy.auto.contract.AutoContract
import java.io.File
import java.io.FileDescriptor
import java.io.PrintWriter
import android.R as AndroidR

/** 平台基础服务 owner。Binder 入口只鉴权、排队和按原顺序装配；业务状态在各 owner。 */
class AutoService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = SerialWorker("AutoService-callbacks")
    private val artwork = SerialWorker("AutoService-art")
    private val shell = SerialWorker("AutoService-shell")
    private lateinit var shellAccess: PlatformAccess
    private lateinit var shellRequests: ServiceShellRequests
    private lateinit var permissionRequests: ClientPermissionRequests
    private lateinit var vehicle: ServiceVehicleSession
    private lateinit var sessions: ServiceClientSessions
    private lateinit var mediaSession: ServiceMediaSession
    private lateinit var elements: ClusterElementsSession
    private lateinit var remoteSession: ServiceRemoteSession
    private lateinit var sentinelRequests: ServiceSentinelRequests
    private lateinit var publication: ServicePublication
    private lateinit var diagnostics: ServiceDiagnostics
    private lateinit var binder: AutoServiceBinder
    private var sentinel: SentinelCoordinator? = null
    private val clients = ClientRegistry { owner -> main.post { sessions.detach(owner) } }
    private lateinit var platform: PlatformCoordinator

    @Volatile private var features = ServiceFeatures()
    private var platformGeneration = 0L
    private var rootReady = false

    // 旧整屏覆盖投屏已归档：服务不再持有 composition、旧 scene/preview Surface、
    // viewport 协商与投影策略。CarPlay 主屏尺寸沿用 MapStreamSize.DEFAULT（1920×536）。

    @Volatile private var closed = false
    private var status = "created"

    @Volatile private var paused = false
    private val backgroundImages = SerialWorker("AutoService-background-images")

    /**
     * 快照缓存、接收者表和合并通知只放在这里。
     * 借用已经创建的 callbacks 投递；构造时不读 closed，也不另开线程。
     */
    private val snapshots =
        ServiceSnapshots(
            post = callbacks::post,
            remove = callbacks::removeCallbacks,
            isClosed = { closed },
        )

    /**
     * 调用身份只在 Binder 线程读取。签名和包名查询延迟到真正调用时，
     * 构造服务时不取 packageManager，也不保存它的方法引用。
     */
    private val callerIdentity =
        ServiceCallerIdentity(
            callingUid = Binder::getCallingUid,
            processUid = Process::myUid,
            checkSignatures = { caller, own -> packageManager.checkSignatures(caller, own) },
            packagesForUid = { uid -> packageManager.getPackagesForUid(uid) },
        )

    /**
     * 复用本服务的 main 和 closed。失败时仍按原顺序报告，不在这一层再吞一次异常。
     */
    private val requestQueue =
        ServiceRequestQueue(
            main = main,
            isClosed = { closed },
            onFailure = { source, error ->
                DiagnosticEvents.error("binder_request", error, mapOf("caller" to source))
                ServiceTrace.event("binder_request", error)
                state("request:${error.javaClass.simpleName}")
            },
        )

    /**
     * 按原顺序装配各 owner，并在任何业务 start 之前接好发布。
     *
     * 自动运行关闭时保持暂停，不创建平台、不启动采集。工厂到这次调用才执行。
     */
    override fun onCreate() {
        super.onCreate()
        ServiceTrace.configure(File(filesDir, "diagnostics/app.log"))
        DiagnosticUploader.kick(this)
        ServiceTrace.event("service_created")
        val prefs = getSharedPreferences("platform", 0)
        features = ServiceFeatures.read(this)
        sentinel =
            SentinelCoordinator(
                this,
                { shellAccess.ensureRoot() },
                { main.post { if (!closed) publish() } },
                { main.post { if (!closed) vehicle.recalculateDemand() } },
            )
        prefs.edit().putString("vehicleMode", if (features.root) "root" else "app").apply()
        // 旧投屏帧率/策略持久配置不再读取：MapStreamSize 固定发布 DEFAULT（1920×536）。
        shellAccess = PlatformAccess(this)
        shellRequests =
            ServiceShellRequests(
                postShell = shell::post,
                postMain = { action -> main.post(action) },
                postCallback = callbacks::post,
                executeShell = shellAccess::executeShell,
                closed = { closed },
                paused = { paused },
                automatic = { AutoStartup.enabled(this) },
                leaseAlive = { lease -> clients.all().any { it === lease } },
            )
        permissionRequests =
            ClientPermissionRequests(
                postShell = shell::post,
                postMain = { action -> main.post(action) },
                postCallback = callbacks::post,
                executeShell = shellAccess::executeShell,
                ensureRoot = shellAccess::ensureRoot,
                closed = { closed },
                paused = { paused },
                automatic = { AutoStartup.enabled(this) },
                rootEnabled = { ServiceFeatures.read(this).root },
                overlayAllowed = { calling, target ->
                    getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
                        "android:system_alert_window",
                        calling,
                        target,
                    ) == AppOpsManager.MODE_ALLOWED
                },
                permissionGranted = { permission, calling ->
                    checkPermission(permission, -1, calling) == PackageManager.PERMISSION_GRANTED
                },
                leaseAlive = { lease -> clients.all().any { it === lease } },
            )
        vehicle =
            ServiceVehicleSession(
                context = this,
                clients = clients,
                main = main,
                post = callbacks::post,
                isFeedEnabled = { !closed && !paused && features.vehicle },
                acceptValues = { sentinel?.accept(it) },
                sentinelDemands = { sentinel?.demands.orEmpty() },
                onPublish = { publish() },
                rootLost = { if (::platform.isInitialized) platform.rootLost() },
            )
        mediaSession =
            ServiceMediaSession(
                context = this,
                main = main,
                postArtwork = artwork::post,
                postCallback = callbacks::post,
                clients = clients,
                closed = { closed },
                paused = { paused },
                featuresRoot = { features.root },
                automatic = { AutoStartup.enabled(this) },
                onPublish = { publish() },
                onKanziPublished = { generation, flags -> elements.published(generation, flags) },
            )
        elements =
            ClusterElementsSession(
                context = this,
                main = main,
                postBackground = backgroundImages::post,
                postCallback = callbacks::post,
                clients = clients,
                closed = { closed },
                onPublish = { publish() },
            )
        remoteSession =
            ServiceRemoteSession(
                context = this,
                main = main,
                postCallback = callbacks::post,
                closed = { closed },
                paused = { paused },
                onPublish = { publish() },
                clients = clients,
            )
        sentinelRequests =
            ServiceSentinelRequests(
                postCallback = callbacks::post,
                sentinel = { sentinel },
                paused = { paused },
                rootEnabled = { features.root },
                vehicleEnabled = { features.vehicle },
                automatic = { AutoStartup.enabled(this) },
            )
        sessions =
            ServiceClientSessions(
                clients = clients,
                main = main,
                postCallback = callbacks::post,
                closed = { closed },
                recalculateDemand = { vehicle.recalculateDemand() },
                clearRole = { role -> mediaSession.clearRole(role) },
                onElementsActive = { lease, active -> elements.applyActive(lease, active) },
                onPublish = { publish() },
            )
        publication =
            ServicePublication(
                media = mediaSession,
                elements = elements,
                remote = remoteSession,
                vehicle = vehicle,
                clients = clients,
                snapshots = snapshots,
                composer = ServiceSnapshotComposer(),
                sentinel = { sentinel },
                closed = { closed },
                paused = { paused },
                rootReady = { rootReady },
                featuresRoot = { features.root },
                featuresVehicle = { features.vehicle },
                automatic = { AutoStartup.enabled(this) },
                vehicleWebEnabled = {
                    !paused && getSharedPreferences("vehicle-web", 0).getBoolean("enabled", false)
                },
                features = { features.bundle() },
                foundation = { foundationEvidence() },
                status = { status },
                vehicleMode = { getSharedPreferences("platform", 0).getString("vehicleMode", "app") ?: "app" },
            )
        diagnostics =
            ServiceDiagnostics(
                context = this,
                clients = clients,
                snapshots = snapshots,
                vehicle = { vehicle },
                paused = { paused },
                rootReady = { rootReady },
                features = { features },
                foundation = { foundationEvidence() },
            )
        binder =
            AutoServiceBinder(
                callerIdentity = callerIdentity,
                requestQueue = requestQueue,
                packagesForUid = { uid -> packageManager.getPackagesForUid(uid).orEmpty().toList() },
                hasAdmin = { uid ->
                    checkPermission("com.ecarx.autoservice.permission.ADMIN", -1, uid) ==
                        PackageManager.PERMISSION_GRANTED
                },
                processUid = Process::myUid,
                clients = clients,
                sessions = sessions,
                elements = elements,
                media = mediaSession,
                vehicle = vehicle,
                shellRequests = shellRequests,
                permissionRequests = permissionRequests,
                sentinelRequests = sentinelRequests,
                remote = remoteSession,
                snapshots = snapshots,
                publish = ::publish,
            )
        paused = !AutoStartup.enabled(this)
        startPlatformForeground()
        if (!paused) {
            platform = newPlatform()
            platform.start()
            if (features.vehicle) vehicle.start()
            AutoStartup.schedule(this)
        } else {
            status = "stopped"
        }
        remoteSession.update()
        publish()
    }

    private fun newPlatform(): PlatformCoordinator {
        val ticket = ++platformGeneration
        return PlatformCoordinator(this, report = { value ->
            main.post {
                if (!closed && !paused && ticket == platformGeneration) state(value)
            }
        }, foundationChanged = { ready ->
            if (!closed && !paused && ticket == platformGeneration) {
                rootReady = ready && features.root
                publish()
            }
            // 独立车辆/Root维护的结果不能关闭已由普通App握手成功的HAB会话。
        }, features = features)
    }

    private fun startPlatformForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "platform",
                getString(R.string.service_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val intent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, ServiceActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        startForeground(
            71,
            Notification
                .Builder(this, "platform")
                .setSmallIcon(AndroidR.drawable.ic_menu_compass)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(getString(R.string.service_notification_text))
                .setContentIntent(intent)
                .build(),
        )
    }

    /**
     * 处理恢复、远程授权、停服、换车型和功能开关。
     *
     * 停服先保留订阅再停采集、清缓存。换车型必须先停采集，再按新目录重算需求。
     * 恢复意图被拒绝时不进入这些动作。
     */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!ProcessRecovery.accepts(this, intent)) {
            return if (AutoStartup.enabled(this)) {
                START_STICKY
            } else {
                START_NOT_STICKY
            }
        }
        if (intent?.action == "remote-authorize") {
            remoteSession.authorize(intent.getIntExtra("hours", 12))
        }
        if (intent?.action == "remote-disable") {
            remoteSession.disable()
        }
        if (intent?.action == "stop" || (intent?.action != "start" && !AutoStartup.enabled(this))) {
            sentinel?.preparePlatformStop()
            AutoStartup.setEnabled(this, false)
            features = ServiceFeatures.read(this)
            runCatching {
                stopService(
                    Intent().setComponent(
                        ComponentName(AutoContract.CLUSTER_PACKAGE, "com.galaxy.autocluster.ClusterRenderService"),
                    ),
                )
            }
            paused = true
            ++platformGeneration
            remoteSession.update()
            rootReady = false
            // 旧 composition/Surface 所有权已归档；停服只撤销本 App 现有增强并通知客户端。
            clients.all().forEach { lease ->
                callbacks.post { runCatching { lease.callback.onProjectionState(lease.role, "surface_closed") } }
            }
            // 停服只暂停采集，不清空订阅意图（否则客户端不会重发，恢复后永久没有车辆数据）。
            vehicle.retainFeeds()
            vehicle.stop()
            vehicle.clear()
            vehicle.markStopped()
            if (::platform.isInitialized) platform.close()
            shellAccess.cancel()
            status = "stopped"
            publish()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == "theme-stop") {
            // 只撤销本 App 现有原厂增强；不写旧 themeEnabled 意图、不驱动任何旧输出，
            // 也不会因 MAP 仍在线而触发整屏地图接管。
            getSharedPreferences("platform", 0).edit().putBoolean("clusterElementsEnabled", false).apply()
            clients.all().filter { it.role == AutoContract.ELEMENTS }.forEach { it.active = false }
            runCatching {
                stopService(
                    Intent().setComponent(
                        ComponentName(AutoContract.CLUSTER_PACKAGE, "com.galaxy.autocluster.ClusterRenderService"),
                    ),
                )
            }
        }
        if (intent?.action == "start") {
            AutoStartup.setEnabled(this, true)
        }
        applyFeatureSettings()
        if (paused) {
            paused = false
            platform = newPlatform()
            platform.start()
            // 恢复时按当前租约重算订阅：停服期间客户端可能已重新订阅，而 stop() 清过 lease.feed。
            // 基础服务恢复不唤起旧接收端、不重建任何整屏输出。
            if (features.vehicle) {
                vehicle.start()
                vehicle.recalculateDemand()
            }
            startPlatformForeground()
        }
        if (intent?.action == "prepare") {
            shellAccess.cancel()
            platform.retry()
        }
        if (intent?.action == "model") {
            intent.getStringExtra("model")?.takeIf { it in setOf("L6", "L7") }?.let {
                getSharedPreferences("platform", 0).edit().putString("model", it).commit()
            }
            // 换车型：订阅意图保留，但旧键未必属于新目录——按新目录摘掉无效键，有效键继续采集。
            // 整清会让客户端因“本地状态没变”不重发，换车型后永久没有车辆数据。
            vehicle.retainFeeds(vehicle.keys().toSet())
            // 换车型还要丢掉上一个车型的缓存：订阅当下的 replay 不看 paused，
            // 留着旧快照会在下一次 setVehicleFeed 时补发上一个车型的值。
            vehicle.clear()
            vehicle.stop()
            ++platformGeneration
            platform.close()
            platform = newPlatform()
            platform.start()
            // 需求必须在采集重启之后重算：stop() 的清理块会清空 demand，先算再停等于没设。
            if (features.vehicle) {
                vehicle.start()
                vehicle.recalculateDemand()
            }
        }
        // 旧整屏投屏已归档：cluster action 与旧 themeEnabled=true 都不再启动任何输出服务。
        // 只保留真实已启用的 OEM 元素意图，按 Root/paused/自身持久选择恢复。
        if (!paused &&
            features.root &&
            getSharedPreferences("platform", 0).getBoolean("clusterElementsEnabled", false)
        ) {
            runCatching {
                startForegroundService(
                    Intent().setComponent(
                        ComponentName(
                            AutoContract.CLUSTER_PACKAGE,
                            "com.galaxy.autocluster.ClusterRenderService",
                        ),
                    ),
                )
            }.onFailure { state("cluster:${it.javaClass.simpleName}") }
        }
        publish()
        if (AutoStartup.enabled(this)) {
            ProcessRecovery.arm(
                this,
                Intent(this, AutoService::class.java).setAction("recovery"),
                intent?.hasExtra(ProcessRecovery.EXTRA_GENERATION) == true,
            )
        }
        return START_STICKY
    }

    /** 主线程应用用户选择。连接配置换代，投屏和车辆各自释放；外部客户端不能覆盖这些总开关。 */
    private fun applyFeatureSettings() {
        val next = ServiceFeatures.read(this)
        val previous = features
        if (next == previous) return
        if ((previous.root && !next.root) || (previous.vehicle && !next.vehicle)) {
            sentinel?.preparePlatformStop()
        }
        features = next
        val connectionsChanged = previous.adb != next.adb || previous.root != next.root
        if (connectionsChanged) {
            ++platformGeneration
            shellAccess.cancel()
            rootReady = false
            if (::platform.isInitialized) platform.close(releaseRoot = !next.root)
            getSharedPreferences(
                "platform",
                0,
            ).edit().putString("vehicleMode", if (next.root) "root" else "app").apply()
            if (!paused) {
                platform = newPlatform()
                platform.start()
            }
        }
        if (previous.vehicle != next.vehicle || connectionsChanged) {
            vehicle.stop()
            vehicle.clear()
            vehicle.markFeature(next.vehicle)
            if (!paused && next.vehicle) {
                vehicle.start()
                vehicle.recalculateDemand()
            }
        }
        // projectionEnabled 只是历史兼容记录，不再驱动任何输出或组合重建。
        status = "settings_applied"
        publish()
    }

    /** 只有契约 action 返回 Binder。其他 action 不绑定。 */
    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == AutoContract.ACTION) binder else null

    /** 回到 main 后，服务已关闭就不再改状态或发布。 */
    private fun state(value: String) {
        main.post {
            if (!closed) {
                status = value
                Log.i("AutoService", value)
                publish()
            }
        }
    }

    private fun publish() = publication.publish()

    /** 诊断文本仍由诊断 owner 按原分支写出。这里不另做一份缓存。 */
    override fun dump(
        fd: FileDescriptor,
        writer: PrintWriter,
        args: Array<out String>,
    ) {
        diagnostics.dump(writer, args)
    }

    private fun foundationEvidence(): String =
        when {
            paused -> "{\"state\":\"stopped\"}"
            !features.adb && !features.root -> "{\"state\":\"disabled\"}"
            ::platform.isInitialized -> platform.foundationEvidence()
            else -> "{}"
        }

    /**
     * 按原 owner 顺序关闭。借用的 worker 留到各自原来的时点。
     *
     * 已经接收的任务可以走完 finally。关闭之后新的排队不再执行。
     */
    override fun onDestroy() {
        sentinel?.close()
        sentinel = null
        remoteSession.closeWeb()
        remoteSession.closeRemote()
        ProcessRecovery.release(this, AutoStartup.enabled(this))
        closed = true
        ++platformGeneration
        if (::platform.isInitialized) platform.close()
        shellAccess.close()
        shell.close()
        clients.close()
        vehicle.close()
        callbacks.close()
        mediaSession.closeOemArtwork()
        mediaSession.closeNativeSource()
        elements.closeHook()
        backgroundImages.close()
        mediaSession.closeKanzi()
        artwork.close()
        super.onDestroy()
    }
}
