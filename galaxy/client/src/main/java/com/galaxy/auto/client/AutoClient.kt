package com.galaxy.auto.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.view.Surface
import com.galaxy.auto.contract.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/** 每客户端一代Binder租约。主线程只接生命周期，远端调用和封面pipe均在独立worker。 */
class AutoClient(context: Context, private val role: String,
    private val onSurface: (Surface?) -> Unit = {},
    private val onSnapshot: (Bundle) -> Unit = {},
    private val onState: (String) -> Unit = {},
    private val onCommand: (Int) -> Unit = {},
    private val onConfiguredSurface: (Surface?, Long) -> Unit = { _, _ -> },
    private val onVehicleFeed: (Bundle) -> Unit = {},
    private val onReady: () -> Unit = {},
    private val replayVehicleFeedOnConnect: Boolean = true,
) : Closeable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("AutoClient-$role").apply { start() }
    private val io = Handler(thread.looper)
    @Volatile private var owner: IBinder = Binder()
    @Volatile private var closed = false
    @Volatile private var api: IAutoService? = null
    private val connectionLock = Any()
    private var bound = false
    @Volatile private var generation = 0
    private var retries = 0
    @Volatile private var lastMedia: Bundle? = null
    @Volatile private var lastNavigation: Bundle? = null
    @Volatile private var lastArtwork: ByteArray? = null
    @Volatile private var lastScene: Bundle? = null
    private var previewSurface: Surface? = null // io线程持有自己的引用
    private var borrowed: Surface? = null
    @Volatile private var active = false
    /** v4车辆信号订阅键与最小推送间隔；重连后要原样重放，否则断线恢复会静默失去订阅。 */
    @Volatile private var feedKeys: List<String> = emptyList()
    @Volatile private var feedInterval: Int = AutoContract.DEFAULT_FEED_INTERVAL_MS
    private val media = AtomicReference<Bundle?>()
    private val navigation = AtomicReference<Bundle?>()
    private val art = AtomicReference<ByteArray?>()
    private val scene = AtomicReference<Bundle?>()
    private val policy = AtomicReference<Bundle?>()
    private val flush = Runnable { flushLatest() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            val ticket = ++generation
            val lease = Binder().also { owner = it }
            retries = 0
            val next = IAutoService.Stub.asInterface(binder)
            val callback = object : IAutoCallback.Stub() {
                override fun onSnapshot(snapshot: Bundle) { main.post {
                    if (!closed && generation == ticket) {
                        snapshot.classLoader = javaClass.classLoader
                        if (snapshot.containsKey("command")) onCommand(snapshot.getInt("command")) else this@AutoClient.onSnapshot(snapshot)
                    }
                } }
                override fun onSurface(kind: String, surface: Surface) { main.post {
                    if (closed || generation != ticket) { surface.release(); return@post }
                    val old = borrowed
                    borrowed = surface
                    onSurface(surface)
                    onConfiguredSurface(surface, kind.substringAfter('@', "0").toLongOrNull() ?: 0)
                    old?.release()
                } }
                override fun onProjectionState(kind: String, state: String) { main.post {
                    if (!closed && generation == ticket) {
                        if (state == "surface_closed") { onSurface(null); onConfiguredSurface(null, 0); borrowed?.release(); borrowed = null }
                        onState(state)
                    }
                } }
                override fun onVehicleFeed(values: Bundle) { main.post {
                    if (!closed && generation == ticket) {
                        values.classLoader = javaClass.classLoader
                        this@AutoClient.onVehicleFeed(values)
                    }
                } }
            }
            io.post {
                try {
                    check(next.protocolVersion == AutoContract.VERSION) { "protocol_version_mismatch" }
                    synchronized(connectionLock) {
                        if (closed || generation != ticket) return@post
                        api = next
                    }
                    next.attach(lease, role, callback)
                    if (closed || generation != ticket) return@post
                    next.setActive(lease, active)
                    // 与attach/setActive同一批补发，重连后订阅语义不依赖调用方再次触发。
                    if (replayVehicleFeedOnConnect && feedKeys.isNotEmpty()) next.setVehicleFeed(lease, feedKeys.toTypedArray(), feedInterval)
                    media.compareAndSet(null, lastMedia)
                    navigation.compareAndSet(null, lastNavigation)
                    art.compareAndSet(null, lastArtwork)
                    scene.compareAndSet(null, lastScene)
                    if (role == AutoContract.PREVIEW) next.setPreviewSurface(lease, previewSurface)
                    flushLatest()
                    main.post { if (!closed && generation == ticket) onReady() }
                } catch (error: Exception) { state("connect:${error.javaClass.simpleName}") }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { lost("service_disconnected") }
        override fun onNullBinding(name: ComponentName) { lost("binding_rejected"); rebind() }
        override fun onBindingDied(name: ComponentName) { lost("binding_died"); rebind() }
    }
    init { require(AutoContract.roleValid(role)) }
    fun connect() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || bound) return
        val intent = Intent(AutoContract.ACTION).setComponent(ComponentName(AutoContract.SERVICE_PACKAGE, AutoContract.SERVICE_CLASS))
        try {
            app.startForegroundService(intent)
            bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) { state("service_unavailable"); rebind() }
        } catch (error: Exception) { state("bind:${error.javaClass.simpleName}"); rebind() }
    }
    fun setActive(value: Boolean) { active = value; io.post { call { it.setActive(owner, value) } } }
    fun publishMedia(value: Bundle) {
        val next = AutoContract.sanitizeMedia(value)
        if (lastMedia?.getString("track") != next.getString("track")) { lastArtwork = null; art.set(null) }
        lastMedia = next; media.set(next); scheduleFlush()
    }
    fun publishNavigation(value: Bundle) {
        lastNavigation = AutoContract.sanitizeNavigation(value); navigation.set(lastNavigation); scheduleFlush()
    }
    fun publishArtwork(bytes: ByteArray) {
        require(bytes.size <= 2 * 1024 * 1024)
        lastArtwork = bytes.copyOf(); art.set(lastArtwork); scheduleFlush()
    }
    fun configureScene(value: SceneConfiguration) {
        require(role == AutoContract.THEME || role == AutoContract.PREVIEW)
        lastScene = value.bundle(); scene.set(lastScene); scheduleFlush()
    }
    fun setProjectionPolicy(value: ProjectionPolicy) { policy.set(value.bundle()); scheduleFlush() }
    fun setPreviewSurface(value: Surface?) {
        require(role == AutoContract.PREVIEW)
        if(closed)return
        if(value==null){lastScene=null;scene.set(null)}
        val copy = value?.let { duplicateSurface(it) }
        if(!io.post {
            if (closed) { copy?.release(); return@post }
            previewSurface?.release(); previewSurface = copy
            call { it.setPreviewSurface(owner, copy) }
        })copy?.release()
    }
    private fun scheduleFlush() { io.removeCallbacks(flush); io.post(flush) }
    private fun flushLatest() {
        val remote = api ?: return
        if (closed) return
        call {
            scene.getAndSet(null)?.let { value -> remote.configureScene(owner, value) }
            policy.getAndSet(null)?.let { value -> remote.setProjectionPolicy(owner, value) }
            media.getAndSet(null)?.let { value -> remote.publishMedia(owner, value) }
            navigation.getAndSet(null)?.let { value -> remote.publishNavigation(owner, value) }
            art.getAndSet(null)?.let { bytes ->
                // 普通只读FD没有pipe写满/读端不消费造成的永久等待，临时文件仅在本App缓存中。
                val file = java.io.File.createTempFile("auto-art-", ".bin", app.cacheDir)
                try {
                    file.writeBytes(bytes)
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> remote.publishArtwork(owner, fd) }
                } finally { file.delete() }
            }
        }
    }
    fun executeShell(transport: String, command: String, timeoutMillis: Int = 10000, result: (Bundle) -> Unit) {
        val ticket = generation
        io.post { call { remote -> remote.executeShell(owner, transport, command, timeoutMillis, object : IAutoResult.Stub() {
            override fun onResult(value: Bundle) { main.post { if (!closed && generation == ticket) result(value) } }
        }) } }
    }
    /** 权限回调与租约代次绑定。旧服务不支持追加接口时明确失败，不越过授权屏障。 */
    fun requestClientPermissions(permissions: List<String>, result: (Bundle) -> Unit) {
        require(permissions.isNotEmpty() && permissions.size <= 8)
        val ticket = generation
        io.post {
            fun deliver(value: Bundle) { main.post { if (!closed && generation == ticket) result(value) } }
            try {
                val remote = api ?: error("service_unavailable")
                remote.requestClientPermissions(owner, permissions.toTypedArray(), object : IAutoResult.Stub() {
                    override fun onResult(value: Bundle) = deliver(value)
                })
            } catch (error: Exception) {
                deliver(Bundle().apply { putBoolean("granted", false); putString("error", error.javaClass.simpleName) })
            }
        }
    }
    fun vehiclePage(offset: Int, catalog: Boolean = false, result: (Bundle) -> Unit) {
        io.post { call { remote ->
            val page = if (catalog) remote.getVehicleCatalog(offset, 32) else remote.getVehicleProperties(offset, 32)
            main.post { if (!closed) result(page) }
        } }
    }
    fun vehicleValue(propertyId: Int, areaId: Int, offset: Int, result: (Bundle) -> Unit) {
        io.post { call { remote ->
            val value = remote.getVehicleValue(propertyId, areaId, offset)
            main.post { if (!closed) result(value) }
        } }
    }
    fun mediaCommand(command: Int) { io.post { call { it.mediaCommand(owner, command) } } }
    /**
     * 订阅语义车辆信号（v4）：空集合即取消订阅。
     *
     * [intervalMillis] 既是推送间隔也是该键的采集周期——服务端按它决定读车机的频率，
     * 所以不要为了“更实时”随手填250，先确认消费端真的用得上。
     * 只在主线程调用；非法键直接失败，不静默丢键——静默丢弃会让调用方以为已订阅。
     */
    fun setVehicleFeed(keys: Collection<String>, intervalMillis: Int = AutoContract.DEFAULT_FEED_INTERVAL_MS) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val next = keys.distinct().sorted()
        require(AutoContract.feedKeysValid(next.toTypedArray())) { "invalid_feed_keys" }
        require(AutoContract.feedIntervalValid(intervalMillis)) { "invalid_feed_interval" }
        feedKeys = next; feedInterval = intervalMillis
        io.post { call { it.setVehicleFeed(owner, feedKeys.toTypedArray(), feedInterval) } }
    }
    /** 直接读服务端已采集的缓存（不触发车机读取）；结果在主线程回调。 */
    fun getVehicleValues(keys: Collection<String>, result: (Bundle) -> Unit) {
        val next = keys.distinct().sorted()
        require(AutoContract.feedKeysValid(next.toTypedArray())) { "invalid_feed_keys" }
        io.post { call { remote ->
            val values = remote.getVehicleValues(next.toTypedArray())
            main.post { if (!closed) { values.classLoader = javaClass.classLoader; result(values) } }
        } }
    }
    /** 全目录诊断扫描开关；只有本包（AutoService自带诊断界面）能生效。 */
    fun setVehicleScan(enabled: Boolean) { io.post { call { it.setVehicleScan(enabled) } } }
    private fun call(block: (IAutoService) -> Unit) {
        val remote = api ?: return
        if (!closed) try { block(remote) } catch (error: Exception) { state("ipc:${error.javaClass.simpleName}") }
    }
    private fun state(value: String) { main.post { if (!closed) onState(value) } }
    private fun lost(reason: String) {
        if (closed) return
        synchronized(connectionLock) { ++generation; api = null }
        onSurface(null); borrowed?.release(); borrowed = null
        onConfiguredSurface(null, 0)
        onState(reason)
    }
    private val retry = Runnable { connect() }
    private fun rebind() {
        if (bound) { runCatching { app.unbindService(connection) }; bound = false }
        if (!closed && retries < 3) {
            main.removeCallbacks(retry)
            main.postDelayed(retry, 1000L shl retries++)
        }
    }
    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val closing = synchronized(connectionLock) {
            if (closed) return
            closed = true
            (api to owner).also { api = null }
        }
        ++generation
        main.removeCallbacks(retry)
        onSurface(null); borrowed?.release(); borrowed = null
        onConfiguredSurface(null, 0)
        if (bound) { app.unbindService(connection); bound = false }
        // 已排队的Surface任务必须运行其closed分支，才能释放独立引用。
        // 其他IPC任务由call/flushLatest的closed检查失效，清理任务随后串行退出。
        io.removeCallbacks(flush)
        media.set(null); navigation.set(null); art.set(null)
        scene.set(null); policy.set(null); lastScene = null
        lastMedia = null; lastNavigation = null; lastArtwork = null
        // close是终态：订阅键一并清空，避免残留意图在下一代连接里复活。
        feedKeys = emptyList(); feedInterval = AutoContract.DEFAULT_FEED_INTERVAL_MS
        io.post { runCatching { closing.first?.detach(closing.second) }; previewSurface?.release(); previewSurface = null; thread.quitSafely() }
    }
    private fun duplicateSurface(value: Surface): Surface {
        val parcel = Parcel.obtain()
        return try { value.writeToParcel(parcel, 0); parcel.setDataPosition(0); Surface.CREATOR.createFromParcel(parcel) }
        finally { parcel.recycle() }
    }
}
