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
    private var sentinel:com.ecarx.autoservice.sentinel.SentinelCoordinator?=null
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
    private var mapStreamSize = MapStreamSize.DEFAULT
    private val settleMapSize = Runnable {
        if (!closed) {
            val viewport = ((if(previewTarget!=null)scenes[AutoContract.PREVIEW]else null)
                ?: scenes[AutoContract.THEME])?.map
            val next = MapStreamSize.forViewport(viewport)
            if (next != mapStreamSize) {
                mapStreamSize = next
                composition?.setMapStreamSize(next)
                publish()
            }
        }
    }
    private fun scheduleMapSize() {
        main.removeCallbacks(settleMapSize)
        main.postDelayed(settleMapSize, 750)
    }
    private var media = Bundle()
    private var navigation = Bundle()
    /** 订阅驱动采集的最新快照（只含订阅键），feed推送与拉取接口同源。 */
    @Volatile private var signals = Bundle()
    private var sequence = 0L
    /** 按需订阅的轮次序号；每轮自增一次，同一轮的多个租约共用一个值。 */
    private var feedSequence = 0L
    @Volatile private var closed = false
    private var status = "created"
    @Volatile private var paused = false
    private val inputEpoch=java.util.UUID.randomUUID().toString()
    private var menuSequence=0L
    private var menuReceivedAt=0L
    private var remote: com.ecarx.autoservice.remote.RemoteBridge? = null
    private var webVehicle: com.ecarx.autoservice.web.VehicleWebBridge? = null
    private var remoteGeneration=0L
    private var remoteState="disabled"
    @Synchronized private fun updateRemote(){
        val webEnabled=!closed&&!paused
        if(!webEnabled){webVehicle?.close();webVehicle=null}
        else if(webVehicle==null){webVehicle=com.ecarx.autoservice.web.VehicleWebBridge(this).also{it.start()}}
        val enabled=!closed&&!paused&&com.ecarx.autoservice.remote.RemoteSettings(this).enabled
        if(!enabled){if(remote!=null)++remoteGeneration;remote?.close();remote=null;remoteState="disabled"}
        else if(remote==null){
            val ticket=++remoteGeneration
            remote=com.ecarx.autoservice.remote.RemoteBridge(this){ value->main.post {
                if(!closed&&remoteGeneration==ticket&&remote!=null&&remoteState!=value){remoteState=value;publish()}
            }}.also{it.start()}
        }
    }
    private var oemArtwork: OemArtworkWriter?=null
    private val backgroundImages=SerialWorker("AutoService-background-images")
    private var clusterImageGeneration=0L
    private var clusterElementRevision=0L
    private var clusterConfigurationStatus="ready"
    private var clusterHook:ClusterHookController?=null
    private var kanziMedia: KanziMediaPublisher?=null
    private var carplayMedia=Bundle()
    private var nativeMedia: Bundle?=null
    private var nativeSource: com.ecarx.autoservice.media.NativeMediaSource?=null
    private var nativeArtwork: android.graphics.Bitmap?=null
    private var visualMedia = Bundle()
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
    private fun enqueue(block: () -> Unit) {
        val source=packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty().filter{it in setOf("com.ecarx.autoservice","com.ecarx.carplay","com.galaxy.autorecord","com.galaxy.autocluster")}.joinToString(",")
        main.post { if (!closed) runCatching(block).onFailure {
            com.galaxy.auto.client.diagnostics.DiagnosticEvents.error("binder_request",it,mapOf("caller" to source))
            ServiceTrace.event("binder_request",it);state("request:${it.javaClass.simpleName}")
        } }
    }
    private fun receiveArtwork(owner: IBinder,calling: Int,fd: ParcelFileDescriptor,expectedTrack: String?) {
            val duplicate = try {
                check(android.system.OsConstants.S_ISREG(android.system.Os.fstat(fd.fileDescriptor).st_mode)) { "artwork_requires_regular_file" }
                ParcelFileDescriptor.dup(fd.fileDescriptor)
            } finally { fd.close() }
            main.post {
                if (closed || (expectedTrack != null && expectedTrack != media.getString("track")) || runCatching { clients.require(owner, calling).role == AutoContract.MAP }.getOrDefault(false).not()) {
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
            if (role == AutoContract.THEME || role == AutoContract.PREVIEW || role == AutoContract.ELEMENTS || role == AutoContract.VISUAL_MUSIC) check(AutoContract.CLUSTER_PACKAGE in packages)
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
                if (lease.role == AutoContract.THEME) getSharedPreferences("platform",0).edit().apply {
                    putBoolean("themeEnabled",active);if(active)putBoolean("clusterElementsEnabled",false)
                }.apply()
                if(lease.role==AutoContract.ELEMENTS&&!active){++clusterImageGeneration;clusterConfigurationStatus="ready"}
                if (lease.role == AutoContract.ELEMENTS) getSharedPreferences("platform",0).edit().apply {
                    putBoolean("clusterElementsEnabled",active);if(active)putBoolean("themeEnabled",false)
                }.apply()
                updateProjection(); publish()
            }
        }
        override fun configureClusterElements(owner: IBinder, configuration: Bundle) {
            val calling = uid()
            val safe = com.galaxy.auto.contract.ClusterDisplayConfiguration.from(configuration)
            enqueue {
                val lease = clients.require(owner, calling)
                check(lease.role == AutoContract.ELEMENTS)
                if(lease.elements==safe)return@enqueue
                ++clusterImageGeneration
                ++clusterElementRevision
                clusterConfigurationStatus="ready"
                lease.elements = safe
                updateProjection(); publish()
            }
        }
        override fun configureClusterImage(owner: IBinder, configuration: Bundle, image: ParcelFileDescriptor, result: IAutoResult) {
            val calling=uid()
            check(AutoContract.CLUSTER_PACKAGE in packageManager.getPackagesForUid(calling).orEmpty())
            val safe=com.galaxy.auto.contract.ClusterDisplayConfiguration.from(configuration)
            require(safe.background==com.galaxy.auto.contract.ClusterDisplayConfiguration.IMAGE_BACKGROUND)
            val info=android.system.Os.fstat(image.fileDescriptor)
            require(android.system.OsConstants.S_ISREG(info.st_mode)&&info.st_size in 1..16L*1024*1024){"background_requires_bounded_file"}
            val duplicate=ParcelFileDescriptor.dup(image.fileDescriptor)
            if(!main.post {
                if(closed){duplicate.close();return@post}
                val lease=runCatching{clients.require(owner,calling).also{check(it.role==AutoContract.ELEMENTS)}}.getOrNull()
                if(lease==null){duplicate.close();return@post}
                if(lease.elements==safe){
                    duplicate.close()
                    callbacks.post{runCatching{result.onResult(Bundle().apply{putBoolean("accepted",true)})}}
                    return@post
                }
                val ticket=++clusterImageGeneration
                clusterConfigurationStatus="pending";publish()
                backgroundImages.post {
                    val outcome=runCatching { ClusterBackgroundStore(this@AutoService).import(safe.image,duplicate) }
                        .onFailure { ServiceTrace.event("cluster_background_import",it) }
                    runCatching{duplicate.close()}
                    main.post {
                        val accepted=!closed && ticket==clusterImageGeneration && clients.role(AutoContract.ELEMENTS)===lease
                        if(accepted){
                            clusterConfigurationStatus=if(outcome.isSuccess)"ready" else "failed"
                            if(outcome.isSuccess){++clusterElementRevision;lease.elements=safe}
                            updateProjection()
                            publish()
                        }
                        callbacks.post {runCatching{result.onResult(Bundle().apply{putBoolean("accepted",accepted&&outcome.isSuccess);if(outcome.isFailure)putString("error","background_import_failed")})}}
                    }
                }
            })duplicate.close()
        }
        override fun publishMedia(owner: IBinder, value: Bundle) {
            val calling = uid(); val safe = AutoContract.sanitizeMedia(value)
            enqueue {
                check(clients.require(owner, calling).role == AutoContract.MAP)
                carplayMedia=safe
                if(nativeMedia==null)selectMedia(safe)
                publish()
            }
        }
        override fun publishVisualMedia(owner: IBinder,value: Bundle) {
            val calling=uid();val safe=AutoContract.sanitizeMedia(value)
            enqueue {
                check(clients.require(owner,calling).role==AutoContract.VISUAL_MUSIC)
                if(safe.getString("track")==media.getString("track")) {visualMedia=safe;publish()}
            }
        }
        override fun prepareOemArtwork(owner: IBinder,fd: ParcelFileDescriptor,result: IAutoResult) {
            val calling=uid()
            check("com.ecarx.carplay" in packageManager.getPackagesForUid(calling).orEmpty())
            val duplicate=try {
                check(android.system.OsConstants.S_ISREG(android.system.Os.fstat(fd.fileDescriptor).st_mode))
                ParcelFileDescriptor.dup(fd.fileDescriptor)
            }finally{fd.close()}
            main.post {
                if(closed||paused||!features.root||runCatching{clients.require(owner,calling)}.isFailure){
                    duplicate.close();callbacks.post{runCatching{result.onResult(Bundle().apply{putString("error","artwork_unavailable")})}};return@post
                }
                val writer=oemArtwork?:OemArtworkWriter(this@AutoService).also{oemArtwork=it}
                writer.write(duplicate){response->callbacks.post{runCatching{result.onResult(response)}}}
            }
        }
        override fun publishTrackArtwork(owner: IBinder,track: String,fd: ParcelFileDescriptor) = receiveArtwork(owner,uid(),fd,track)
        override fun publishNavigation(owner: IBinder, value: Bundle) {
            val calling = uid(); val safe = AutoContract.sanitizeNavigation(value)
            enqueue { check(clients.require(owner, calling).role == AutoContract.MAP); navigation = safe; publish() }
        }
        override fun publishArtwork(owner: IBinder,fd: ParcelFileDescriptor) = receiveArtwork(owner,uid(),fd,null)
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
                scheduleMapSize()
                publish()
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
                    if(surface==null){scenes.remove(AutoContract.PREVIEW);composition?.clearScene(AutoContract.PREVIEW);scheduleMapSize()}
                    else scheduleMapSize()
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
        override fun setVehicleFeed(owner: IBinder, keys: Array<String>?, minIntervalMillis: Int) {
            val calling = uid()
            check(AutoContract.feedKeysValid(keys))
            check(AutoContract.feedIntervalValid(minIntervalMillis)) { "invalid_feed_interval" }
            // 键格式在上面的check已限制；车型目录校验放主线程，因为model可能在这期间被切换。
            enqueue {
                val lease = clients.require(owner, calling)
                lease.feed = VehicleFeed.validate(keys, modelPrefs())
                lease.feedIntervalMillis = minIntervalMillis
                // 间隔变了就要按新节拍重算，不能沿用上一档的到期时刻。
                lease.lastFeedAt = 0L
                applyDemand()
                // 订阅当下先补一次当前缓存，避免最多等一个采集节拍才有数据；未订阅的键不读车机。
                if (lease.feed.isNotEmpty()) publishFeedTo(lease, signals, SystemClock.elapsedRealtime())
            }
        }
        /** 读服务端已采集的缓存：不触发任何车机读取，未订阅/无值的键不出现。 */
        override fun getVehicleValues(keys: Array<String>?): Bundle {
            uid()
            check(AutoContract.feedKeysValid(keys))
            return runCatching {
                val accepted = VehicleFeed.validate(keys, modelPrefs())
                if (accepted.isEmpty()) Bundle() else VehicleFeed.select(signals, accepted)
            }.getOrElse { Bundle() }
        }
        override fun requestClientPermissions(owner: IBinder, permissions: Array<String>?, result: IAutoResult) {
            val calling = uid()
            val requested = permissions.orEmpty().distinct()
            val policy = com.ecarx.autoservice.platform.ClientPermissionPolicy
            check(policy.allowed(packageManager.getPackagesForUid(calling).orEmpty().toList(), requested)) { "permission_request_rejected" }
            val target = policy.RECORD_PACKAGE
            // Android 每用户 UID 命名空间为 100000；不反射隐藏的 UserHandle.getIdentifier。
            val user = calling / 100000
            enqueue {
                val lease = clients.require(owner, calling)
                shell.post {
                    val response = Bundle()
                    try {
                        check(!closed && !paused && AutoStartup.enabled(this@AutoService)) { "platform_stopped" }
                        fun granted(permission:String):Boolean = if(permission==policy.OVERLAY)
                            getSystemService(android.app.AppOpsManager::class.java).unsafeCheckOpNoThrow("android:system_alert_window",calling,target)==android.app.AppOpsManager.MODE_ALLOWED
                            else checkPermission(permission,-1,calling)==PackageManager.PERMISSION_GRANTED
                        val missing = requested.filter { !granted(it) }
                        if (missing.isNotEmpty()) {
                            check(ServiceFeatures.read(this@AutoService).root) { "root_disabled" }
                            shellAccess.ensureRoot()
                            missing.forEach { permission ->
                                val command=if(permission==policy.OVERLAY)"cmd appops set --user $user $target SYSTEM_ALERT_WINDOW allow" else "pm grant --user $user $target $permission"
                                val value = shellAccess.executeShell(command, 10000, "ROOT")
                                check(value.optInt("exitCode", -1) == 0 && !value.optBoolean("timedOut") && !value.has("error")) { "permission_grant_failed" }
                            }
                        }
                        check(requested.all { granted(it) }) { "permission_readback_failed" }
                        response.putBoolean("granted", true)
                    } catch (error: Exception) {
                        ServiceTrace.event("client_permission_failed", error)
                        response.putBoolean("granted", false); response.putString("error", error.message ?: error.javaClass.simpleName)
                    }
                    main.post { if (!closed && clients.all().any { it === lease }) callbacks.post { runCatching { result.onResult(response) } } }
                }
            }
        }
        /** 全目录诊断扫描只允许本包触发，避免外部应用把车机读取放大。 */
        override fun reportSentinelIdle(owner:IBinder,powerSequence:Long){
            val calling=uid()
            check("com.galaxy.autorecord" in packageManager.getPackagesForUid(calling).orEmpty())
            enqueue{clients.require(owner,calling);sentinel?.idle(powerSequence)}
        }
        override fun configureSentinel(owner:IBinder,enabled:Boolean,result:IAutoResult){
            val calling=uid()
            check("com.galaxy.autorecord" in packageManager.getPackagesForUid(calling).orEmpty()){"sentinel_client_rejected"}
            enqueue{
                clients.require(owner,calling)
                val response=Bundle()
                runCatching{
                    check(!enabled||!paused&&features.root&&features.vehicle&&AutoStartup.enabled(this@AutoService)){"platform_disabled"}
                    checkNotNull(sentinel).configure(enabled)
                    response.putBoolean("accepted",true)
                }.onFailure{response.putBoolean("accepted",false);response.putString("error",it.message)}
                callbacks.post{runCatching{result.onResult(response)}}
            }
        }
        override fun vehicleWebUpload(owner:IBinder,clipId:String,direction:String,file:ParcelFileDescriptor,length:Long,result:IAutoResult) {
            val calling=uid()
            check("com.galaxy.autorecord" in packageManager.getPackagesForUid(calling).orEmpty())
            require(clipId.matches(Regex("[A-Za-z0-9_-]{1,128}"))&&direction in listOf("front","rear","left","right","index","open-front","open-rear","open-left","open-right"))
            require(length in 12..268435456)
            val copy=ParcelFileDescriptor.dup(file.fileDescriptor)
            enqueue {
                try{
                    val lease=clients.require(owner,calling)
                    checkNotNull(webVehicle).upload(clipId,direction,copy,length){response->main.post{
                        if(!closed&&clients.all().any{it===lease})callbacks.post{runCatching{result.onResult(response)}}
                    }}
                }catch(error:Exception){copy.close();callbacks.post{runCatching{result.onResult(Bundle().apply{putString("error","媒体上传不可用")})}}}
            }
        }
        override fun vehicleWebRequest(owner: IBinder, action: String, payload: Bundle, result: IAutoResult) {
            val calling=uid()
            val packages=packageManager.getPackagesForUid(calling).orEmpty()
            check(calling==Process.myUid() || "com.galaxy.autorecord" in packages) { "web_client_rejected" }
            check(action in setOf("state","enable","code","confirm","unbind","disable","record-publish","record-poll","record-result","record-telemetry","record-signals","record-live-status"))
            if(action.startsWith("record-"))check("com.galaxy.autorecord" in packages)
            require((payload.getString("json")?.length ?: 0) <= 384*1024)
            enqueue {
                val lease=clients.require(owner,calling)
                val bridge=webVehicle
                if(bridge==null)callbacks.post{result.onResult(Bundle().apply{putString("error","AutoService 自动运行已关闭")})}
                else bridge.request(action,payload) { response -> main.post {
                    if(!closed&&clients.all().any{it===lease}){publish();callbacks.post{runCatching{result.onResult(response)}}}
                } }
            }
        }
        override fun setVehicleScan(enabled: Boolean) {
            check(uid() == Process.myUid()) { "local_only" }
            collector.setScan(enabled)
        }
    }
    override fun onCreate() {
        super.onCreate()
        ServiceTrace.configure(java.io.File(filesDir, "diagnostics/app.log"))
        com.ecarx.autoservice.diagnostics.DiagnosticUploader.kick(this)
        ServiceTrace.event("service_created")
        val prefs=getSharedPreferences("platform",0)
        features = ServiceFeatures.read(this)
        sentinel=com.ecarx.autoservice.sentinel.SentinelCoordinator(this,{shellAccess.ensureRoot()},
            {main.post{if(!closed)publish()}},{main.post{if(!closed)applyDemand()}})
        prefs.edit().putString("vehicleMode", if (features.root) "root" else "app").apply()
        val max=prefs.getInt("projectionFps",30).coerceIn(1,60)
        projectionPolicy=ProjectionPolicy(prefs.getString("projectionMode","content-aware")!!,max,prefs.getInt("animationFps",30).coerceIn(1,max))
        shellAccess = com.ecarx.autoservice.platform.PlatformAccess(this)
        collector = VehicleCollector(this, inventory, { state, count ->
            // 元数据（发现状态/条数）驱动快照；车辆值走feed，不再整包重投。
            discovery = state; discoveredCount = count; publish()
        }, { if (::platform.isInitialized) platform.rootLost() },menuPressed={
            menuReceivedAt=SystemClock.elapsedRealtime();++menuSequence;publish()
        }, feed={ values, at ->
            // 采集在自身worker线程；租约与Binder回调都在主线程，因此只投递不直接触碰。
            main.post { publishFeed(values, at) }
        })
        paused = !AutoStartup.enabled(this)
        startPlatformForeground()
        if (!paused) { platform = newPlatform(); platform.start(); if (features.vehicle) collector.start(); AutoStartup.schedule(this) }
        else status = "stopped"
        updateRemote()
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
        if(!com.galaxy.auto.client.recovery.ProcessRecovery.accepts(this,intent))return if(AutoStartup.enabled(this))START_STICKY else START_NOT_STICKY
        if(intent?.action=="remote-authorize"){
            ++remoteGeneration
            webVehicle?.close();webVehicle=null
        remote?.close();remote=null
            com.ecarx.autoservice.remote.RemoteSettings(this).authorize(intent.getIntExtra("hours",12))
        }
        if(intent?.action=="remote-disable"){
            ++remoteGeneration
            remote?.revoke();com.ecarx.autoservice.remote.RemoteSettings(this).disable();remote=null
        }
        if (intent?.action == "stop" || (intent?.action != "start" && !AutoStartup.enabled(this))) {
            sentinel?.preparePlatformStop()
            AutoStartup.setEnabled(this, false)
            features = ServiceFeatures.read(this)
            runCatching { stopService(Intent().setComponent(ComponentName(AutoContract.CLUSTER_PACKAGE, "com.galaxy.autocluster.ClusterRenderService"))) }
            paused = true; ++platformGeneration
            updateRemote()
            projectionRequested = false; platformReady = false; rootReady = false; preparing = false
            composition?.close(); composition = null; surfaces.clear()
            clients.all().forEach { lease -> callbacks.post { runCatching { lease.callback.onProjectionState(lease.role, "surface_closed") } } }
            // 停服只暂停采集，不清空订阅意图（否则客户端不会重发，恢复后永久没有车辆数据）。
            clients.retainFeeds()
            collector.stop(); signals = Bundle(); discovery = "stopped"; discoveredCount = 0
            if (::platform.isInitialized) platform.close()
            shellAccess.cancel()
            status = "stopped"; publish(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == "theme-stop") {
            getSharedPreferences("platform",0).edit().putBoolean("themeEnabled",false).putBoolean("clusterElementsEnabled",false).apply()
            clients.all().filter {it.role==AutoContract.THEME||it.role==AutoContract.ELEMENTS}.forEach{it.active=false}
            updateProjection()
            runCatching { stopService(Intent().setComponent(ComponentName(AutoContract.CLUSTER_PACKAGE, "com.galaxy.autocluster.ClusterRenderService"))) }
        }
        if (intent?.action == "start") AutoStartup.setEnabled(this, true)
        applyFeatureSettings()
        if (paused) {
            paused = false; platform = newPlatform(); platform.start()
            // 恢复时按当前租约重算订阅：停服期间客户端可能已重新订阅，而 stop() 清过 lease.feed。
            if (features.vehicle) { collector.start(); applyDemand() }
            startPlatformForeground()
            if (clients.all().any { it.role != AutoContract.OBSERVER }) { ensureComposition(); updateProjection() }
        }
        if (intent?.action == "prepare") { shellAccess.cancel(); platform.retry() }
        if (intent?.action == "model") {
            intent.getStringExtra("model")?.takeIf { it in setOf("L6", "L7") }?.let {
                getSharedPreferences("platform", 0).edit().putString("model", it).commit()
            }
            composition?.setSessionActive(false)
            // 换车型：订阅意图保留，但旧键未必属于新目录——按新目录摘掉无效键，有效键继续采集。
            // 整清会让客户端因“本地状态没变”不重发，换车型后永久没有车辆数据。
            clients.retainFeeds(vehicleKeys().toSet())
            // 换车型还要丢掉上一个车型的缓存：publishFeedTo（订阅当下补发）没有 paused 守卫，
            // 留着旧快照会在下一次 setVehicleFeed 时补发上一个车型的值。
            signals = Bundle()
            collector.stop(); ++platformGeneration; platform.close(); platform = newPlatform(); platform.start()
            // 需求必须在采集重启之后重算：stop() 的清理块会清空 demand，先算再停等于没设。
            if (features.vehicle) { collector.start(); applyDemand() }
            projectionRequested = false; platformReady = false; updateProjection()
        }
        if ((features.projection && (intent?.action == "cluster" || getSharedPreferences("platform",0).getBoolean("themeEnabled",false))) ||
            (features.root && getSharedPreferences("platform",0).getBoolean("clusterElementsEnabled",false))) runCatching {
            startForegroundService(Intent().setComponent(ComponentName(AutoContract.CLUSTER_PACKAGE,
                "com.galaxy.autocluster.ClusterRenderService")))
        }.onFailure { state("cluster:${it.javaClass.simpleName}") }
        publish()
        if(AutoStartup.enabled(this))com.galaxy.auto.client.recovery.ProcessRecovery.arm(this,
            Intent(this,AutoService::class.java).setAction("recovery"),intent?.hasExtra(com.galaxy.auto.client.recovery.ProcessRecovery.EXTRA_GENERATION)==true)
        return START_STICKY
    }
    /** 主线程应用用户选择。连接配置换代，投屏和车辆各自释放；外部客户端不能覆盖这些总开关。 */
    private fun applyFeatureSettings() {
        val next = ServiceFeatures.read(this)
        val previous = features
        if (next == previous) return
        if((previous.root&&!next.root)||(previous.vehicle&&!next.vehicle))sentinel?.preparePlatformStop()
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
            signals = Bundle(); discovery = if (next.vehicle) "idle" else "disabled"; discoveredCount = 0
            if (!paused && next.vehicle) { collector.start(); applyDemand() }
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
        composition?.setMapStreamSize(mapStreamSize)
        scenes.forEach { (role,scene) -> composition?.configureScene(role,scene) }
        composition?.setPreviewSurface(previewTarget)
    }
    private fun updateProjection() {
        val map = clients.role(AutoContract.MAP)?.active == true
        // 原厂元素模式保留原厂窗口；不能让仍在线的CarPlay客户端再盖上一整层HAB画面。
        val elements = clients.role(AutoContract.ELEMENTS)?.active == true
        val active = !paused && !elements && features.projection && clients.all().any { AutoContract.producesOutput(it.role) && it.active }
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
        // 租约消失必须立刻收缩采集集合，否则退订后还会继续读车机。
        applyDemand()
        scenes.remove(old.role)
        scheduleMapSize()
        if(old.role==AutoContract.THEME||old.role==AutoContract.PREVIEW)composition?.clearScene(old.role)
        if(old.role==AutoContract.PREVIEW){previewTarget?.release();previewTarget=null;composition?.setPreviewSurface(null)}
        if (old.role == AutoContract.MAP) {
            carplayMedia=Bundle();navigation=Bundle()
            if(nativeMedia==null)selectMedia(Bundle())
        }
        if(old.role==AutoContract.VISUAL_MUSIC)visualMedia=Bundle()
        updateProjection()
        if (clients.all().none { AutoContract.producesOutput(it.role) } && previewTarget==null) {
            composition?.close(); composition = null; surfaces.clear()
        }
        publish()
    }
    private fun state(value: String) {
        main.post { if (!closed) { status = value; android.util.Log.i("AutoService", value); publish() } }
    }
    /** 订阅键与车型目录绑定，必须按当前选择的车型校验，不能沿用切换前的键。 */
    private fun modelPrefs(): String = getSharedPreferences("platform", 0).getString("model", "L6") ?: "L6"
    private var catalogKeysModel = ""
    private var catalogKeys: Array<String> = emptyArray()
    /** 目录键名清单：只在车型变化时重建，避免每次快照都重新分配78个字符串数组。 */
    private fun vehicleKeys(): Array<String> {
        val model = modelPrefs()
        if (model != catalogKeysModel) {
            catalogKeysModel = model
            // 键名清单＝目录键＋旧公共别名：客户端据此知道哪些键可订阅。
            catalogKeys = (VehicleCatalog.forModel(model).map { it.key } + VehicleCatalog.LEGACY_ALIASES.keys).toTypedArray()
        }
        return catalogKeys
    }
    /**
     * 按需订阅的每轮推送；主线程调用。
     * 无订阅者、暂停或车辆采集关闭时直接返回，250ms采集节拍上不产生额外开销。
     */
    private fun publishFeed(values: Bundle, at: Long) {
        if (closed || paused || !features.vehicle) return
        // 缓存对外供给：拉取接口与订阅补发都用这一份，保证推/拉同源。
        signals = values
        sentinel?.accept(values)
        val targets = clients.all().filter { it.feed.isNotEmpty() }
        if (targets.isEmpty()) return
        // 一轮只自增一次：同一轮的多个租约拿到同一个序号，跨租约可直接对齐节拍。
        val round = ++feedSequence
        targets.forEach { lease ->
            // 按租约自己的间隔节流：1Hz的订阅者不会因为别人要250ms而收4倍数据。
            if (lease.lastFeedAt != 0L && at - lease.lastFeedAt < lease.feedIntervalMillis) return@forEach
            lease.lastFeedAt = at
            sendFeed(lease, values, at, round)
        }
    }
    /**
     * 把当前所有租约的订阅合并成采集需求：键取并集、间隔取最紧。
     * 空表表示没人订阅，采集端据此完全停摆（不读车机、不空转）。
     */
    private fun applyDemand() {
        if (!::collector.isInitialized) return
        // 别名要翻译成基础键才能读到车机；租约自己的feed仍保留别名，用于裁剪下发。
        collector.setDemand(VehicleDemand.merge(clients.all().map { lease ->
            lease.feed.mapTo(HashSet()) { VehicleCatalog.baseKey(it) } to lease.feedIntervalMillis
        } + listOf(sentinel?.demands.orEmpty().keys to 500)))
    }
    /** 订阅/改订阅当下的即时补发：此时还没有整轮节拍，序号单独分配。 */
    private fun publishFeedTo(lease: ClientRegistry.Lease, values: Bundle, at: Long) {
        sendFeed(lease, values, at, ++feedSequence)
    }
    private fun sendFeed(lease: ClientRegistry.Lease, values: Bundle, at: Long, round: Long) {
        val out = VehicleFeed.select(values, lease.feed)
        // 该租约本轮没有任何有效值就不发空包：不补0/不补默认值，消费者按maxAgeMillis自行判过期。
        if (out.isEmpty) return
        out.putLong("at", at); out.putLong("sequence", round)
        callbacks.post { runCatching { lease.callback.onVehicleFeed(out) } }
    }
    private fun selectMedia(value: Bundle) {
        if(media.getString("track")!=value.getString("track")){
            ++artworkGeneration;visualMedia=Bundle();java.io.File(cacheDir,"artwork.png").delete();++artworkVersion
        }
        val rawLines=value.getStringArrayList("lyrics").orEmpty()
        if(rawLines.isNotEmpty()&&value.getLongArray("lyricTimes")?.size==rawLines.size)visualMedia=Bundle()
        media=AutoContract.sanitizeMedia(value).apply{
            val now=SystemClock.elapsedRealtime();putLong("receivedAt",getLong("sampledAt").takeIf{it in 1..now}?:now)
        }
    }
    private fun nativeMusic(value: Bundle?,bitmap: android.graphics.Bitmap?) {
        nativeMedia=value
        val changed=media.getString("track")!=(value?:carplayMedia).getString("track")
        selectMedia(value?:carplayMedia)
        if(value!=null&&bitmap!=null&&(changed||nativeArtwork!==bitmap)) {
            nativeArtwork=bitmap
            val ticket=artworkGeneration
            val width=bitmap.width;val height=bitmap.height
            if(width>0&&height>0) {
                val scale=minOf(1f,512f/maxOf(width,height))
                val image=android.graphics.Bitmap.createScaledBitmap(bitmap,(width*scale).toInt().coerceAtLeast(1),(height*scale).toInt().coerceAtLeast(1),true)
                val owned=if(image===bitmap)image.copy(android.graphics.Bitmap.Config.ARGB_8888,false)else image
                artwork.post {
                    try {
                        val bytes=java.io.ByteArrayOutputStream().also{owned.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
                        main.post {if(!closed&&ticket==artworkGeneration){
                            val file=android.util.AtomicFile(java.io.File(cacheDir,"artwork.png"));val output=file.startWrite()
                            try{output.write(bytes);file.finishWrite(output);++artworkVersion;publish()}catch(error:Exception){file.failWrite(output)}
                        }}
                    }finally{owned.recycle()}
                }
            }
        }
        publish()
    }
    private fun publish() {
        if(!closed&&nativeSource==null)nativeSource=com.ecarx.autoservice.media.NativeMediaSource(this,::nativeMusic)
        nativeSource?.enabled(!closed&&!paused&&AutoStartup.enabled(this))
        sentinel?.platform(!closed&&!paused&&features.root&&features.vehicle&&AutoStartup.enabled(this))
        updateRemote()
        val snapshot = Bundle().apply {
            putString("vehicleDiscovery", discovery); putInt("vehiclePropertyCount", discoveredCount); putInt("vehicleSampledCount", inventory.count()); putInt("vehicleValidCount", inventory.validCount())
            putBundle("sentinel",sentinel?.snapshot())
            putInt("sentinelVersion",1)
            putInt("version", AutoContract.VERSION); putLong("sequence", ++sequence); putLong("receivedAt", SystemClock.elapsedRealtime())
            putBoolean("rootReady", rootReady); putBoolean("qnxReady", platformReady)
            putBoolean("automatic", AutoStartup.enabled(this@AutoService))
            putBoolean("vehicleWebEnabled",!paused&&getSharedPreferences("vehicle-web",0).getBoolean("enabled",false))
            putBundle("features", features.bundle())
            putBoolean("projectionRequested", projectionRequested)
            // 不依赖features.projection；基础Root关闭/全局停止时停止可视需求。
            val elements = clients.elementDemand().takeIf { !paused && features.root }
            putBoolean("clusterElementsRequested", elements != null)
            putLong("clusterElementRevision",clusterElementRevision)
            putBoolean("clusterMusicRequested", elements?.music == true)
            putBoolean("clusterLyricsRequested", !paused && (elements?.music == true || features.projection && projectionRequested && clients.role(AutoContract.THEME)?.active == true && scenes[AutoContract.THEME]?.lyrics == true))
            elements?.let { putBundle("clusterElements", it.bundle()) }
            putString("foundation", foundationEvidence())
            putString("status", status); putBundle("media", Bundle(media)); putBundle("navigation", Bundle(navigation))
            if(visualMedia.getString("track")!=null&&visualMedia.getString("track")==media.getString("track")) {
                putBundle("visualMedia",Bundle(media).apply {
                    putStringArrayList("lyrics",visualMedia.getStringArrayList("lyrics"))
                    if(visualMedia.containsKey("lyricTimes"))putLongArray("lyricTimes",visualMedia.getLongArray("lyricTimes"))else remove("lyricTimes")
                })
            }
            // v4：快照不再携带全量车辆键（原来每次投递83个子Bundle×每客户端）。
            // 只发键名清单，客户端据此把主题/自身需求裁剪成订阅；值走feed或getVehicleValues。
            putStringArray("vehicleKeys", vehicleKeys()); putBoolean("vehicleScanning", collector.scanning())
            putInt("minVehicleFeedIntervalMs", AutoContract.MIN_FEED_INTERVAL_MS)
            putString("inputEpoch",inputEpoch);putLong("menuSequence",menuSequence);putLong("menuReceivedAt",menuReceivedAt)
            putBundle("projectionPolicy",projectionPolicy.bundle())
            putString("remoteState",remoteState)
            putBundle("mapStreamSize", mapStreamSize.bundle())
            putString("vehicleMode", getSharedPreferences("platform", 0).getString("vehicleMode", "app"))
            putString("model", modelPrefs())
            putBoolean("artworkReady",java.io.File(cacheDir,"artwork.png").isFile)
            putLong("artworkVersion", artworkVersion)
            putString("artworkUri", "content://com.ecarx.autoservice.artwork/current")
        }
        val elementConfiguration=snapshot.getBundle("clusterElements")?.let{com.galaxy.auto.contract.ClusterDisplayConfiguration.from(it)}
        val elementsWanted=snapshot.getBoolean("clusterElementsRequested")&&!closed
        if(elementsWanted&&clusterHook==null)clusterHook=ClusterHookController(this,::publish)
        clusterHook?.update(elementsWanted,rootReady,elementConfiguration)
        snapshot.putBundle("clusterElementRuntime",clusterHook?.snapshot())
        snapshot.putString("clusterConfigurationStatus",clusterConfigurationStatus)
        snapshot.putString("clusterElementStatus",when(clusterConfigurationStatus){"pending"->"preparing";"failed"->"failed";else->clusterHook?.snapshot()?.getString("state")?:"idle"})
        val musicWanted=elementsWanted && rootReady
        if(musicWanted&&kanziMedia==null)kanziMedia=KanziMediaPublisher(this,published={generation,flags->clusterHook?.published(generation,flags)})
        kanziMedia?.update(snapshot,musicWanted)
        synchronized(snapshotLock) { latest = snapshot; recipients = clients.all().map { it.callback } }
        callbacks.removeCallbacks(notify); if (recipients.isNotEmpty()) callbacks.post(notify)
    }
    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>) {
        if ("--foundation-check" in args) {
            writer.println("FOUNDATION_CHECK=" + foundationEvidence())
            return
        }
        val snapshot = synchronized(snapshotLock) { Bundle(latest) }
        if ("--record-properties" in args) {
            writer.println("RECORD_PROPERTIES=" + inventory.diagnostics())
            return
        }
        if ("--record-fields" in args) {
            // 只读当前订阅缓存，不扫描、不额外读车辆属性；用于逐项核对录制端数据接线。
            val withVehicle = Bundle(snapshot).apply { putBundle("vehicle", Bundle(signals)) }
            val keys = VehicleCatalog.forModel(snapshot.getString("model") ?: "L6").map { it.key } +
                VehicleCatalog.LEGACY_ALIASES.keys
            writer.println("RECORD_FIELDS=" + ClusterFieldDiagnostics.collect(withVehicle,
                android.os.SystemClock.elapsedRealtime(), keys))
            return
        }
        if ("--cluster-fields" in args) {
            // v4快照不含车辆值：诊断用当前订阅快照补上，保持同一份数据口径。
            val withVehicle = Bundle(snapshot).apply { putBundle("vehicle", Bundle(signals)) }
            writer.println("CLUSTER_FIELDS=" + ClusterFieldDiagnostics.collect(withVehicle, android.os.SystemClock.elapsedRealtime()))
            return
        }
        writer.println("AUTOSERVICE_DIAGNOSTIC version=${AutoContract.VERSION}")
        for (key in listOf("status", "model", "vehicleMode", "vehicleDiscovery")) writer.println("$key=${snapshot.getString(key)}")
        for (key in listOf("vehiclePropertyCount", "vehicleSampledCount", "vehicleValidCount")) writer.println("$key=${snapshot.getInt(key)}")
        writer.println("projectionRequested=$projectionRequested platformReady=$platformReady preparing=$preparing paused=$paused")
        writer.println("clients=" + clients.all().joinToString { "${it.role}:${it.active}" })
        // 只读诊断：谁订了几个键。键名本身是公共别名，可直接打印。
        writer.println("feeds=" + clients.all().filter { it.feed.isNotEmpty() }
            .joinToString { "${it.role}:${it.feed.size}@${it.feedIntervalMillis}ms[${it.feed.joinToString(",")}]" })
        writer.println("vehicleDemand=" + VehicleDemand.merge(clients.all().map { it.feed to it.feedIntervalMillis })
            .entries.joinToString { "${it.key}@${it.value}ms" })
        writer.println("vehicleStats=" + collector.stats())
        writer.println("hab=" + (composition?.metrics() ?: "inactive"))
        writer.println("clusterElementsRequested=${snapshot.getBoolean("clusterElementsRequested")} revision=${snapshot.getLong("clusterElementRevision")} configuration=${snapshot.getString("clusterConfigurationStatus")} state=${snapshot.getString("clusterElementStatus")}")
        writer.println("clusterElementRuntime="+snapshot.getBundle("clusterElementRuntime"))
        writer.println("automatic=" + AutoStartup.enabled(this))
        val diagnosticPrefs=getSharedPreferences("diagnostics",0)
        writer.println("diagnosticLastUpload="+diagnosticPrefs.getLong("lastUploadAt",0)+" error="+diagnosticPrefs.getString("lastUploadError","")+" pending="+java.io.File(noBackupFilesDir,"diagnostic-inbox").listFiles().orEmpty().size)
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
        sentinel?.close();sentinel=null
        webVehicle?.close();webVehicle=null
        remote?.close();remote=null
        main.removeCallbacks(settleMapSize)
        com.galaxy.auto.client.recovery.ProcessRecovery.release(this,AutoStartup.enabled(this))
        closed = true; ++platformGeneration
        composition?.close(); composition = null
        if (::platform.isInitialized) platform.close()
        shellAccess.close(); shell.close()
        clients.close(); surfaces.clear()
        previewTarget?.release();previewTarget=null;scenes.clear()
        collector.close()
        callbacks.close()
        oemArtwork?.close();oemArtwork=null
        nativeSource?.close();nativeSource=null
        clusterHook?.close();clusterHook=null
        backgroundImages.close()
        kanziMedia?.close();kanziMedia=null
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
