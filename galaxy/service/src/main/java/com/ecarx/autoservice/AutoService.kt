package com.ecarx.autoservice

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.*
import android.view.Surface
import com.ecarx.autoservice.projection.GalaxyHabComposition
import com.ecarx.autoservice.vehicle.*
import com.ecarx.autoservice.platform.RootServiceClient
import com.ecarx.autoservice.platform.PlatformCoordinator
import com.galaxy.auto.contract.*
import com.ecarx.autoservice.startup.AutoStartup
import com.ecarx.autoservice.settings.ServiceFeatures

/** 唯一平台与仪表输出owner。Binder入口只鉴权/排队，车辆、封面和客户端回调各用worker。 */
class AutoService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = SerialWorker("AutoService-callbacks")
    private val artwork = SerialWorker("AutoService-art")
    private val shell = SerialWorker("AutoService-shell")
    private val shellBusy = java.util.concurrent.atomic.AtomicBoolean()
    private lateinit var shellAccess: com.ecarx.autoservice.platform.PlatformAccess
    private lateinit var collector: VehicleCollector
    private val inventory = VehicleInventory()
    private var discovery = "not_connected"
    private var discoveredCount = 0
    private val clients = ClientRegistry { owner -> main.post { detach(owner) } }
    private var composition: GalaxyHabComposition? = null
    private lateinit var platform: PlatformCoordinator
    @Volatile private var features = ServiceFeatures()
    private var platformGeneration = 0L
    private var projectionRequested = false
    private var platformReady = false
    private var rootReady = false
    private var preparing = false
    private val surfaces = mutableMapOf<String, Pair<String, Surface>>()
    private val scenes = mutableMapOf<String, SceneConfiguration>()
    private var previewTarget: Surface? = null
    private var projectionPolicy = ProjectionPolicy()
    private var media = Bundle()
    private var navigation = Bundle()
    private var signals = Bundle()
    private var sequence = 0L
    @Volatile private var closed = false
    private var status = "created"
    @Volatile private var paused = false
    private val inputEpoch=java.util.UUID.randomUUID().toString()
    private var menuSequence=0L
    private var menuReceivedAt=0L
    private var artworkVersion = 0L
    private var artworkGeneration = 0L
    private val notify = Runnable {
        if (closed) return@Runnable
        val state = synchronized(snapshotLock) { Bundle(latest) }
        val list = synchronized(snapshotLock) { recipients.toList() }
        list.forEach { callback -> runCatching { callback.onSnapshot(Bundle(state)) } }
    }
    private val snapshotLock = Any()
    private var latest = Bundle()
    private var recipients = emptyList<IAutoCallback>()
    private fun uid(): Int {
        val uid = Binder.getCallingUid()
        check(uid == Process.myUid() || packageManager.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH) {
            "client_signature_rejected"
        }
        return uid
    }
    private fun enqueue(block: () -> Unit) { main.post { if (!closed) runCatching(block).onFailure { state("request:${it.javaClass.simpleName}") } } }
    private val binder = object : IAutoService.Stub() {
        override fun getProtocolVersion(): Int { uid(); return AutoContract.VERSION }
        override fun getSnapshot(): Bundle { uid(); return synchronized(snapshotLock) { Bundle(latest) } }
        override fun getVehicleCatalog(offset: Int, limit: Int): Bundle { uid(); return inventory.page(offset, limit, true) }
        override fun getVehicleProperties(offset: Int, limit: Int): Bundle { uid(); return inventory.page(offset, limit, false) }
        override fun getVehicleValue(propertyId: Int, areaId: Int, characterOffset: Int): Bundle {
            uid(); return inventory.value(propertyId, areaId, characterOffset)
        }
        override fun attach(owner: IBinder, role: String, callback: IAutoCallback) {
            val calling = uid()
            require(AutoContract.roleValid(role))
            val packages = packageManager.getPackagesForUid(calling).orEmpty()
            if (role == AutoContract.MAP) check("com.ecarx.carplay" in packages)
            if (role == AutoContract.THEME || role == AutoContract.PREVIEW) check(AutoContract.CLUSTER_PACKAGE in packages)
            enqueue {
                if(role==AutoContract.PREVIEW)clients.role(AutoContract.PREVIEW)?.let { old->this@AutoService.detach(old.owner) }
                clients.attach(owner, calling, role, callback)
                surfaces[role]?.let { (kind,surface) -> callbacks.post { runCatching { callback.onSurface(kind, surface) } } }
                publish()
            }
        }
        override fun detach(owner: IBinder) { val calling = uid(); enqueue { clients.require(owner, calling); this@AutoService.detach(owner) } }
        override fun setActive(owner: IBinder, active: Boolean) {
            val calling = uid(); enqueue {
                val lease = clients.require(owner, calling)
                lease.active = active
                if (lease.role == AutoContract.THEME) getSharedPreferences("platform", 0).edit().putBoolean("themeEnabled", active).apply()
                updateProjection(); publish()
            }
        }
        override fun publishMedia(owner: IBinder, value: Bundle) {
            val calling = uid(); val safe = AutoContract.sanitizeMedia(value)
            enqueue {
                check(clients.require(owner, calling).role == AutoContract.MAP)
                if (media.getString("track") != safe.getString("track")) {
                    ++artworkGeneration
                    java.io.File(cacheDir, "artwork.png").delete()
                    ++artworkVersion
                }
                media = safe.apply {
                    val now = SystemClock.elapsedRealtime()
                    putLong("receivedAt", getLong("sampledAt").takeIf { it in 1..now } ?: now)
                }; publish()
            }
        }
        override fun publishNavigation(owner: IBinder, value: Bundle) {
            val calling = uid(); val safe = AutoContract.sanitizeNavigation(value)
            enqueue { check(clients.require(owner, calling).role == AutoContract.MAP); navigation = safe; publish() }
        }
        override fun publishArtwork(owner: IBinder, fd: ParcelFileDescriptor) {
            val calling = uid()
            val duplicate = try {
                check(android.system.OsConstants.S_ISREG(android.system.Os.fstat(fd.fileDescriptor).st_mode)) { "artwork_requires_regular_file" }
                ParcelFileDescriptor.dup(fd.fileDescriptor)
            } finally { fd.close() }
            main.post {
                if (closed || runCatching { clients.require(owner, calling).role == AutoContract.MAP }.getOrDefault(false).not()) {
                    duplicate.close(); return@post
                }
                val ticket = artworkGeneration
                artwork.post {
                    try {
                        val bytes = ParcelFileDescriptor.AutoCloseInputStream(duplicate).use { it.readBytesBounded(2 * 1024 * 1024) }
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        check(options.outWidth in 1..4096 && options.outHeight in 1..4096)
                        main.post {
                            if (!closed && ticket == artworkGeneration) {
                                val file = android.util.AtomicFile(java.io.File(cacheDir, "artwork.png"))
                                val output = file.startWrite()
                                try { output.write(bytes); file.finishWrite(output); ++artworkVersion; publish() }
                                catch (error: Exception) { file.failWrite(output) }
                            }
                        }
                    } catch (_: Exception) { runCatching { duplicate.close() } }
                }
            }
        }
        override fun executeShell(owner: IBinder, transport: String, command: String, timeoutMillis: Int, result: IAutoResult) {
            val calling = uid()
            check(checkPermission("com.ecarx.autoservice.permission.ADMIN", -1, calling) == PackageManager.PERMISSION_GRANTED)
            require(transport in setOf("ROOT", "ADB") && command.length in 1..8192 && '\u0000' !in command && timeoutMillis in 100..30000)
            enqueue {
                val lease = clients.require(owner, calling)
                if (!shellBusy.compareAndSet(false, true)) {
                    callbacks.post { runCatching { result.onResult(Bundle().apply { putString("error", "shell_busy") }) } }
                } else shell.post {
                    val response = runCatching {
                        check(!closed && !paused && AutoStartup.enabled(this@AutoService)) { "platform_stopped" }
                        shellAccess.executeShell(command, timeoutMillis, transport)
                    }
                    val output = Bundle().apply {
                        response.getOrNull()?.let { value ->
                            putString("output", value.optString("output")); putInt("exitCode", value.optInt("exitCode", -1))
                            putBoolean("timedOut", value.optBoolean("timedOut")); putBoolean("truncated", value.optBoolean("truncated"))
                            if (value.has("error")) putString("error", value.optString("error"))
                        }
                        response.exceptionOrNull()?.let { putString("error", it.javaClass.simpleName) }
                    }
                    shellBusy.set(false)
                    main.post { if (!closed && clients.all().any { it === lease })
                        callbacks.post { runCatching { result.onResult(output) } } }
                }
            }
        }
        override fun mediaCommand(owner: IBinder, command: Int) {
            val calling = uid(); require(command in setOf(0, 1, 2))
            enqueue {
                check(clients.require(owner, calling).role != AutoContract.MAP)
                clients.role(AutoContract.MAP)?.callback?.let { callback -> callbacks.post {
                    runCatching { callback.onSnapshot(Bundle().apply { putInt("command", command) }) }
                } }
            }
        }
        override fun configureScene(owner: IBinder, value: Bundle) {
            val calling=uid();val safe=SceneConfiguration.from(value)
            enqueue {
                val lease=clients.require(owner,calling)
                check(lease.role==AutoContract.THEME||lease.role==AutoContract.PREVIEW)
                scenes[lease.role]=safe
                val existing=composition
                ensureComposition(includePreview=lease.role==AutoContract.PREVIEW)
                if(existing!=null)composition?.configureScene(lease.role,safe)
            }
        }
        override fun setPreviewSurface(owner: IBinder, surface: Surface?) {
            val calling=uid()
            main.post {
                if(closed){surface?.release();return@post}
                try {
                    check(clients.require(owner,calling).role==AutoContract.PREVIEW)
                    val old=previewTarget
                    ensureComposition(includePreview=surface!=null)
                    composition?.setPreviewSurface(surface)
                    previewTarget=surface
                    if(surface==null){scenes.remove(AutoContract.PREVIEW);composition?.clearScene(AutoContract.PREVIEW)}
                    old?.release()
                    updateProjection()
                }catch(error: Exception){surface?.release();state("preview:${error.javaClass.simpleName}")}
            }
        }
        override fun setProjectionPolicy(owner: IBinder, value: Bundle) {
            val calling=uid();val safe=ProjectionPolicy.from(value)
            check(AutoContract.CLUSTER_PACKAGE in packageManager.getPackagesForUid(calling).orEmpty())
            enqueue {
                clients.require(owner,calling)
                projectionPolicy=safe
                getSharedPreferences("platform",0).edit().putString("projectionMode",safe.mode)
                    .putInt("projectionFps",safe.maximumFps).putInt("animationFps",safe.animationFps).apply()
                composition?.setPolicy(safe);publish()
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        ServiceTrace.configure(java.io.File(filesDir, "diagnostics/app.log"))
        ServiceTrace.event("service_created")
        val prefs=getSharedPreferences("platform",0)
        features = ServiceFeatures.read(this)
        prefs.edit().putString("vehicleMode", if (features.root) "root" else "app").apply()
        val max=prefs.getInt("projectionFps",30).coerceIn(1,60)
        projectionPolicy=ProjectionPolicy(prefs.getString("projectionMode","content-aware")!!,max,prefs.getInt("animationFps",30).coerceIn(1,max))
        shellAccess = com.ecarx.autoservice.platform.PlatformAccess(this)
        collector = VehicleCollector(this, inventory, { bundle, state, count ->
            signals = bundle; discovery = state; discoveredCount = count; publish()
        }, { if (::platform.isInitialized) platform.rootLost() },menuPressed={
            menuReceivedAt=SystemClock.elapsedRealtime();++menuSequence;publish()
        })
        paused = !AutoStartup.enabled(this)
        startPlatformForeground()
        if (!paused) { platform = newPlatform(); platform.start(); if (features.vehicle) collector.start(); AutoStartup.schedule(this) }
        else status = "stopped"
        publish()
    }
    private fun newPlatform(): PlatformCoordinator {
        val ticket = ++platformGeneration
        return PlatformCoordinator(this, report = { value -> main.post {
            if (!closed && !paused && ticket == platformGeneration) state(value)
        } }, foundationChanged = { ready ->
            if (!closed && !paused && ticket == platformGeneration) {
                rootReady = ready && features.root
                publish()
            }
            // 独立车辆/Root维护的结果不能关闭已由普通App握手成功的HAB会话。
        }, features = features)
    }
    private fun startPlatformForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("platform", "平台与仪表服务", NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(this, 0, Intent(this, ServiceActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(71, Notification.Builder(this, "platform").setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("AutoService").setContentText("车辆数据与仪表投射").setContentIntent(intent).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || (intent?.action != "start" && !AutoStartup.enabled(this))) {
            AutoStartup.setEnabled(this, false)
            features = ServiceFeatures.read(this)
            runCatching { stopService(Intent().setComponent(ComponentName(AutoContract.CLUSTER_PACKAGE, "com.galaxy.autocluster.ClusterRenderService"))) }
            paused = true; ++platformGeneration
            projectionRequested = false; platformReady = false; rootReady = false; preparing = false
            composition?.close(); composition = null; surfaces.clear()
            clients.all().forEach { lease -> callbacks.post { runCatching { lease.callback.onProjectionState(lease.role, "surface_closed") } } }
            collector.stop(); signals = Bundle(); discovery = "stopped"; discoveredCount = 0
            if (::platform.isInitialized) platform.close()
            shellAccess.cancel()
            status = "stopped"; publish(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == "theme-stop") {
            getSharedPreferences("platform", 0).edit().putBoolean("themeEnabled", false).apply()
            runCatching { stopService(Intent().setComponent(ComponentName(AutoContract.CLUSTER_PACKAGE, "com.galaxy.autocluster.ClusterRenderService"))) }
        }
        if (intent?.action == "start") AutoStartup.setEnabled(this, true)
        applyFeatureSettings()
        if (paused) {
            paused = false; platform = newPlatform(); platform.start(); if (features.vehicle) collector.start(); startPlatformForeground()
            if (clients.all().any { it.role != AutoContract.OBSERVER }) { ensureComposition(); updateProjection() }
        }
        if (intent?.action == "prepare") { shellAccess.cancel(); platform.retry() }
        if (intent?.action == "model") {
            intent.getStringExtra("model")?.takeIf { it in setOf("L6", "L7") }?.let {
                getSharedPreferences("platform", 0).edit().putString("model", it).commit()
            }
            composition?.setSessionActive(false)
            collector.stop(); ++platformGeneration; platform.close(); platform = newPlatform(); platform.start(); if (features.vehicle) collector.start()
            projectionRequested = false; platformReady = false; updateProjection()
        }
        if (features.projection && (intent?.action == "cluster" || getSharedPreferences("platform", 0).getBoolean("themeEnabled", false))) runCatching {
            startForegroundService(Intent().setComponent(ComponentName(AutoContract.CLUSTER_PACKAGE,
                "com.galaxy.autocluster.ClusterRenderService")))
        }.onFailure { state("cluster:${it.javaClass.simpleName}") }
        publish()
        return START_STICKY
    }
    /** 主线程应用用户选择。连接配置换代，投屏和车辆各自释放；外部客户端不能覆盖这些总开关。 */
    private fun applyFeatureSettings() {
        val next = ServiceFeatures.read(this)
        val previous = features
        if (next == previous) return
        features = next
        val connectionsChanged = previous.adb != next.adb || previous.root != next.root
        if (connectionsChanged) {
            ++platformGeneration
            shellAccess.cancel()
            rootReady = false
            if (::platform.isInitialized) platform.close(releaseRoot = !next.root)
            getSharedPreferences("platform", 0).edit().putString("vehicleMode", if (next.root) "root" else "app").apply()
            if (!paused) { platform = newPlatform(); platform.start() }
            composition?.rootPolicyChanged()
        }
        if (previous.vehicle != next.vehicle || connectionsChanged) {
            collector.stop()
            signals = Bundle(); discovery = if (next.vehicle) "not_connected" else "disabled"; discoveredCount = 0
            if (!paused && next.vehicle) collector.start()
        }
        if (previous.projection != next.projection) updateProjection()
        status = "settings_applied"
        publish()
    }
    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == AutoContract.ACTION) binder else null
    private fun ensureComposition(includePreview: Boolean = false) {
        if (composition != null || paused || (!includePreview && (!features.projection || clients.all().none { AutoContract.producesOutput(it.role) && it.active }))) return
        composition = GalaxyHabComposition(this, { kind, surface ->
            val role=kind.substringBefore('@')
            if (surface == null) surfaces.remove(role) else surfaces[role] = kind to surface
            clients.role(role)?.callback?.let { callback -> callbacks.post {
                runCatching { if (surface != null) callback.onSurface(kind, surface) else callback.onProjectionState(role, "surface_closed") }
            } }
        }, ::state, prepareReceiver = { complete ->
            if (!features.root || paused) { complete(false); return@GalaxyHabComposition }
            preparing = true
            platform.prepare(true) { ready ->
                preparing = !ready
                complete(ready)
            }
        }, connectionChanged = { ready -> platformReady = ready; if (ready) preparing = false; publish() })
        composition?.setPolicy(projectionPolicy)
        scenes.forEach { (role,scene) -> composition?.configureScene(role,scene) }
        composition?.setPreviewSurface(previewTarget)
    }
    private fun updateProjection() {
        val map = clients.role(AutoContract.MAP)?.active == true
        val active = !paused && features.projection && clients.all().any { AutoContract.producesOutput(it.role) && it.active }
        if (active) ensureComposition()
        composition?.setMapActive(map)
        if (active != projectionRequested) {
            projectionRequested = active
            preparing = active
            composition?.setSessionActive(active)
            if (!active) { platformReady = false; preparing = false }
            if (!active) platform.prepare(false) {}
        }
        if (!active && previewTarget==null) { composition?.close(); composition = null; surfaces.clear() }
    }
    private fun detach(owner: IBinder) {
        val old = clients.remove(owner) ?: return
        scenes.remove(old.role)
        if(old.role==AutoContract.THEME||old.role==AutoContract.PREVIEW)composition?.clearScene(old.role)
        if(old.role==AutoContract.PREVIEW){previewTarget?.release();previewTarget=null;composition?.setPreviewSurface(null)}
        if (old.role == AutoContract.MAP) { media = Bundle(); navigation = Bundle(); ++artworkGeneration
            java.io.File(cacheDir, "artwork.png").delete(); ++artworkVersion }
        updateProjection()
        if (clients.all().none { AutoContract.producesOutput(it.role) } && previewTarget==null) {
            composition?.close(); composition = null; surfaces.clear()
        }
        publish()
    }
    private fun state(value: String) {
        main.post { if (!closed) { status = value; android.util.Log.i("AutoService", value); publish() } }
    }
    private fun publish() {
        val snapshot = Bundle().apply {
            putString("vehicleDiscovery", discovery); putInt("vehiclePropertyCount", discoveredCount); putInt("vehicleSampledCount", inventory.count()); putInt("vehicleValidCount", inventory.validCount())
            putInt("version", AutoContract.VERSION); putLong("sequence", ++sequence); putLong("receivedAt", SystemClock.elapsedRealtime())
            putBoolean("rootReady", rootReady); putBoolean("qnxReady", platformReady)
            putBoolean("automatic", AutoStartup.enabled(this@AutoService))
            putBundle("features", features.bundle())
            putBoolean("projectionRequested", projectionRequested)
            putString("foundation", foundationEvidence())
            putString("status", status); putBundle("vehicle", Bundle(signals)); putBundle("media", Bundle(media)); putBundle("navigation", Bundle(navigation))
            putString("inputEpoch",inputEpoch);putLong("menuSequence",menuSequence);putLong("menuReceivedAt",menuReceivedAt)
            putBundle("projectionPolicy",projectionPolicy.bundle())
            putString("vehicleMode", getSharedPreferences("platform", 0).getString("vehicleMode", "app"))
            putString("model", getSharedPreferences("platform", 0).getString("model", "L6"))
            putLong("artworkVersion", artworkVersion)
            putString("artworkUri", "content://com.ecarx.autoservice.artwork/current")
        }
        synchronized(snapshotLock) { latest = snapshot; recipients = clients.all().map { it.callback } }
        callbacks.removeCallbacks(notify); if (recipients.isNotEmpty()) callbacks.post(notify)
    }
    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>) {
        if ("--foundation-check" in args) {
            writer.println("FOUNDATION_CHECK=" + foundationEvidence())
            return
        }
        val snapshot = synchronized(snapshotLock) { Bundle(latest) }
        if ("--cluster-fields" in args) {
            writer.println("CLUSTER_FIELDS=" + ClusterFieldDiagnostics.collect(snapshot, android.os.SystemClock.elapsedRealtime()))
            return
        }
        writer.println("AUTOSERVICE_DIAGNOSTIC version=${AutoContract.VERSION}")
        for (key in listOf("status", "model", "vehicleMode", "vehicleDiscovery")) writer.println("$key=${snapshot.getString(key)}")
        for (key in listOf("vehiclePropertyCount", "vehicleSampledCount", "vehicleValidCount")) writer.println("$key=${snapshot.getInt(key)}")
        writer.println("projectionRequested=$projectionRequested platformReady=$platformReady preparing=$preparing paused=$paused")
        writer.println("clients=" + clients.all().joinToString { "${it.role}:${it.active}" })
        writer.println("hab=" + (composition?.metrics() ?: "inactive"))
        writer.println("automatic=" + AutoStartup.enabled(this))
        writer.println("features=$features")
        writer.println("adbIdentityConfigured=" + java.io.File(noBackupFilesDir, "adb/client.pem").exists())
        writer.println("qnxCredentialsConfigured=" + java.io.File(noBackupFilesDir, "qnx-login.json").exists())
    }
    private fun foundationEvidence(): String = when {
        paused -> "{\"state\":\"stopped\"}"
        !features.adb && !features.root -> "{\"state\":\"disabled\"}"
        ::platform.isInitialized -> platform.foundationEvidence()
        else -> "{}"
    }
    override fun onDestroy() {
        closed = true; ++platformGeneration
        composition?.close(); composition = null
        if (::platform.isInitialized) platform.close()
        shellAccess.close(); shell.close()
        clients.close(); surfaces.clear()
        previewTarget?.release();previewTarget=null;scenes.clear()
        collector.close()
        callbacks.close()
        artwork.close()
        super.onDestroy()
    }
    private fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val bytes = ByteArray(8192)
        while (true) { val count = read(bytes); if (count < 0) break; check(output.size() + count <= max); output.write(bytes, 0, count) }
        return output.toByteArray()
    }
}
