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
    private var projectionRequested = false
    private var platformReady = false
    private var rootReady = false
    private var preparing = false
    private val surfaces = mutableMapOf<String, Surface>()
    private var media = Bundle()
    private var navigation = Bundle()
    private var signals = Bundle()
    private var sequence = 0L
    @Volatile private var closed = false
    private var status = "created"
    @Volatile private var paused = false
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
            if (role == AutoContract.THEME) check(AutoContract.CLUSTER_PACKAGE in packages)
            enqueue {
                clients.attach(owner, calling, role, callback)
                surfaces[role]?.let { surface -> callbacks.post { runCatching { callback.onSurface(role, surface) } } }
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
                        check(!closed && !paused) { "platform_stopped" }
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
    }
    override fun onCreate() {
        super.onCreate()
        shellAccess = com.ecarx.autoservice.platform.PlatformAccess(this)
        collector = VehicleCollector(this, inventory, { bundle, state, count ->
            signals = bundle; discovery = state; discoveredCount = count; publish()
        }, { if (::platform.isInitialized) platform.rootLost() })
        paused = !AutoStartup.enabled(this)
        startPlatformForeground()
        if (!paused) { platform = newPlatform(); platform.start(); collector.start(); AutoStartup.schedule(this) }
        else status = "stopped"
        publish()
    }
    private fun newPlatform() = PlatformCoordinator(this, ::state) { ready ->
        rootReady = ready
        publish()
        if (!ready) { platformReady = false; composition?.setSessionActive(false) }
    }
    private fun startPlatformForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("platform", "平台与仪表服务", NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(this, 0, Intent(this, ServiceActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(71, Notification.Builder(this, "platform").setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("AutoService").setContentText("车辆数据与仪表投射").setContentIntent(intent).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            AutoStartup.setEnabled(this, false)
            runCatching { stopService(Intent().setComponent(ComponentName(AutoContract.CLUSTER_PACKAGE, "com.galaxy.autocluster.ClusterRenderService"))) }
            paused = true; projectionRequested = false; platformReady = false; rootReady = false; preparing = false
            composition?.close(); composition = null; surfaces.clear()
            clients.all().forEach { lease -> callbacks.post { runCatching { lease.callback.onProjectionState(lease.role, "surface_closed") } } }
            collector.stop(); if (::platform.isInitialized) platform.close(); shellAccess.cancel()
            status = "stopped"; publish(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == "theme-stop") {
            getSharedPreferences("platform", 0).edit().putBoolean("themeEnabled", false).apply()
            runCatching { stopService(Intent().setComponent(ComponentName(AutoContract.CLUSTER_PACKAGE, "com.galaxy.autocluster.ClusterRenderService"))) }
        }
        if (intent?.action == "start") AutoStartup.setEnabled(this, true)
        if (!AutoStartup.enabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        if (paused) {
            paused = false; platform = newPlatform(); platform.start(); collector.start(); startPlatformForeground()
            if (clients.all().any { it.role != AutoContract.OBSERVER }) { ensureComposition(); updateProjection() }
        }
        if (intent?.action == "prepare") platform.retry()
        if (intent?.action == "model") {
            composition?.setSessionActive(false)
            collector.stop(); platform.close(); platform = newPlatform(); platform.start(); collector.start()
            projectionRequested = false; platformReady = false; updateProjection()
        }
        if (intent?.action == "cluster" || getSharedPreferences("platform", 0).getBoolean("themeEnabled", false)) runCatching {
            startForegroundService(Intent().setComponent(ComponentName(AutoContract.CLUSTER_PACKAGE,
                "com.galaxy.autocluster.ClusterRenderService")))
        }.onFailure { state("cluster:${it.javaClass.simpleName}") }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == AutoContract.ACTION) binder else null
    private fun ensureComposition() {
        if (composition != null || paused || clients.all().none { it.role != AutoContract.OBSERVER && it.active }) return
        composition = GalaxyHabComposition(this, { role, surface ->
            if (surface == null) surfaces.remove(role) else surfaces[role] = surface
            clients.role(role)?.callback?.let { callback -> callbacks.post {
                runCatching { if (surface != null) callback.onSurface(role, surface) else callback.onProjectionState(role, "surface_closed") }
            } }
        }, ::state)
    }
    private fun updateProjection() {
        val map = clients.role(AutoContract.MAP)?.active == true
        val active = !paused && clients.all().any { it.role != AutoContract.OBSERVER && it.active }
        if (active) ensureComposition()
        composition?.setMapActive(map)
        if (active && platformReady) composition?.setSessionActive(true)
        if (active != projectionRequested) {
            projectionRequested = active
            if (!active) composition?.setSessionActive(false)
            preparing = active
            platform.prepare(active) { ready -> preparing = false; platformReady = ready; if (projectionRequested && !paused) composition?.setSessionActive(ready) }
        }
        if (!active) { composition?.close(); composition = null; surfaces.clear() }
    }
    private fun detach(owner: IBinder) {
        val old = clients.remove(owner) ?: return
        if (old.role == AutoContract.MAP) { media = Bundle(); navigation = Bundle(); ++artworkGeneration
            java.io.File(cacheDir, "artwork.png").delete(); ++artworkVersion }
        updateProjection()
        if (clients.all().none { it.role != AutoContract.OBSERVER }) {
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
            putString("status", status); putBundle("vehicle", Bundle(signals)); putBundle("media", Bundle(media)); putBundle("navigation", Bundle(navigation))
            putString("vehicleMode", getSharedPreferences("platform", 0).getString("vehicleMode", "app"))
            putString("model", getSharedPreferences("platform", 0).getString("model", "L6"))
            putLong("artworkVersion", artworkVersion)
            putString("artworkUri", "content://com.ecarx.autoservice.artwork/current")
        }
        synchronized(snapshotLock) { latest = snapshot; recipients = clients.all().map { it.callback } }
        callbacks.removeCallbacks(notify); if (recipients.isNotEmpty()) callbacks.post(notify)
    }
    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>) {
        val snapshot = synchronized(snapshotLock) { Bundle(latest) }
        writer.println("AUTOSERVICE_DIAGNOSTIC version=${AutoContract.VERSION}")
        for (key in listOf("status", "model", "vehicleMode", "vehicleDiscovery")) writer.println("$key=${snapshot.getString(key)}")
        for (key in listOf("vehiclePropertyCount", "vehicleSampledCount", "vehicleValidCount")) writer.println("$key=${snapshot.getInt(key)}")
        writer.println("projectionRequested=$projectionRequested platformReady=$platformReady preparing=$preparing paused=$paused")
        writer.println("clients=" + clients.all().joinToString { "${it.role}:${it.active}" })
        writer.println("hab=" + (composition?.metrics() ?: "inactive"))
        writer.println("automatic=" + AutoStartup.enabled(this))
        writer.println("adbIdentityConfigured=" + java.io.File(noBackupFilesDir, "adb/client.pem").exists())
        writer.println("qnxCredentialsConfigured=" + java.io.File(noBackupFilesDir, "qnx-login.json").exists())
    }
    override fun onDestroy() {
        closed = true
        composition?.close(); composition = null
        if (::platform.isInitialized) platform.close()
        shellAccess.close(); shell.close()
        clients.close(); surfaces.clear()
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
