package com.galaxy.auto.client.ipc

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.auto.contract.IAutoResult
import com.galaxy.auto.contract.IAutoService
import com.galaxy.auto.contract.ProjectionPolicy
import com.galaxy.auto.contract.SceneConfiguration
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 发布状态、待发合并槽与重连补发的唯一权威。
 *
 * active、feedKeys/interval、elementPlan/revision以及last*保留值只存在这一份，Connection不复制；
 * 同一个flush runnable先remove再post，只合并现有场景/策略/媒体/导航/封面槽。
 * 握手补发顺序整体由此类执行，onReady仍由Connection在补发完成后回main发出。
 */
class ClientReplayPublisher(
    private val connection: ClientConnectionSession,
    private val callbacks: ClientCallbackDispatch,
    private val app: Context,
    private val role: String,
    private val main: Handler,
    private val io: Handler,
    private val replayVehicleFeedOnConnect: Boolean,
    private val onState: (String) -> Unit,
) {
    /** 封面待发项的曲目身份与已复制字节；调用方后续改动不影响保留值。 */
    private data class TrackArtwork(
        val track: String,
        val bytes: ByteArray,
    )

    @Volatile
    private var active = false

    /** v4车辆信号订阅键与最小推送间隔；重连后按原值重放，否则断线恢复会静默失去订阅。 */
    @Volatile
    private var feedKeys: List<String> = emptyList()

    @Volatile
    private var feedInterval: Int = AutoContract.DEFAULT_FEED_INTERVAL_MS

    @Volatile
    private var lastMedia: Bundle? = null

    @Volatile
    private var lastNavigation: Bundle? = null

    @Volatile
    private var lastArtwork: TrackArtwork? = null

    @Volatile
    private var lastScene: Bundle? = null

    @Volatile
    private var lastElementPlan: Pair<Bundle, File?>? = null

    @Volatile
    private var elementRevision = 0L

    private val media = AtomicReference<Bundle?>()
    private val navigation = AtomicReference<Bundle?>()
    private val art = AtomicReference<TrackArtwork?>()
    private val scene = AtomicReference<Bundle?>()
    private val policy = AtomicReference<Bundle?>()
    private val flush = Runnable { flushLatest() }

    /** 先写active再发io；该值同时是重连补发的来源。 */
    fun setActive(value: Boolean) {
        active = value
        io.post { connection.call { it.setActive(connection.currentOwner, value) } }
    }

    /** 先提交方案再激活；调用线程推进revision，io只在未过期时发送，不新增输出。 */
    fun configureClusterElements(
        value: ClusterDisplayConfiguration,
        image: File? = null,
    ) {
        require(role == AutoContract.ELEMENTS)
        val configuration = value.bundle()
        val revision = ++elementRevision
        lastElementPlan = configuration to image
        io.post {
            if (revision == elementRevision) {
                connection.call {
                    sendElements(it, connection.currentOwner, Bundle(configuration), image, revision)
                }
            }
        }
    }

    /** 切歌时清掉旧封面保留值与待发槽，防止旧封面在下一首被接受。 */
    fun publishMedia(value: Bundle) {
        val next = AutoContract.sanitizeMedia(value)
        if (lastMedia?.getString("track") != next.getString("track")) {
            lastArtwork = null
            art.set(null)
        }
        lastMedia = next
        media.set(next)
        scheduleFlush()
    }

    /** 可视歌词只发本次调用，不建立重连保留缓存。 */
    fun publishVisualMedia(value: Bundle) {
        require(role == AutoContract.VISUAL_MUSIC)
        val safe = AutoContract.sanitizeMedia(value)
        io.post { connection.call { it.publishVisualMedia(connection.currentOwner, safe) } }
    }

    fun publishNavigation(value: Bundle) {
        lastNavigation = AutoContract.sanitizeNavigation(value)
        navigation.set(lastNavigation)
        scheduleFlush()
    }

    /** 同步字节拷贝与2MiB上限；默认track由Facade在调用时从这里取当时值。 */
    fun publishArtwork(
        bytes: ByteArray,
        track: String,
    ) {
        require(bytes.size <= 2 * 1024 * 1024)
        lastArtwork = TrackArtwork(track, bytes.copyOf())
        art.set(lastArtwork)
        scheduleFlush()
    }

    /** publishArtwork的默认参数在调用时求值，取当时lastMedia的track或空串。 */
    fun defaultTrack(): String = lastMedia?.getString("track").orEmpty()

    /** 兼容调用仍保存场景；服务端归档语义不在客户端改变。 */
    fun configureScene(value: SceneConfiguration) {
        require(role == AutoContract.THEME || role == AutoContract.PREVIEW)
        lastScene = value.bundle()
        scene.set(lastScene)
        scheduleFlush()
    }

    /** 策略原本没有lastPolicy，不自行新增重连重放。 */
    fun setProjectionPolicy(value: ProjectionPolicy) {
        policy.set(value.bundle())
        scheduleFlush()
    }

    /** setPreviewSurface(null)时由CallbackDispatch调用，清场景保留值与待发槽。 */
    fun clearSceneForPreview() {
        lastScene = null
        scene.set(null)
    }

    /** 主线程同步入口：先保存keys/interval，io上再读当前字段发订阅，不捕获旧next。 */
    fun setVehicleFeed(
        keys: Collection<String>,
        intervalMillis: Int,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val next = keys.distinct().sorted()
        require(AutoContract.feedKeysValid(next.toTypedArray())) { "invalid_feed_keys" }
        require(AutoContract.feedIntervalValid(intervalMillis)) { "invalid_feed_interval" }
        feedKeys = next
        feedInterval = intervalMillis
        io.post {
            connection.call {
                it.setVehicleFeed(connection.currentOwner, feedKeys.toTypedArray(), feedInterval)
            }
        }
    }

    /**
     * 握手完成后的固定补发顺序：元素→active→订阅→各槽compareAndSet→PREVIEW Surface→flush。
     * 直接调用远端，异常由Connection的握手try统一诊断；只有flush内部沿用原call策略。
     */
    fun replayOnConnect(
        remote: IAutoService,
        lease: IBinder,
    ) {
        lastElementPlan?.let {
            sendElements(remote, lease, Bundle(it.first), it.second, elementRevision)
        }
        remote.setActive(lease, active)
        if (replayVehicleFeedOnConnect && feedKeys.isNotEmpty()) {
            remote.setVehicleFeed(lease, feedKeys.toTypedArray(), feedInterval)
        }
        media.compareAndSet(null, lastMedia)
        navigation.compareAndSet(null, lastNavigation)
        art.compareAndSet(null, lastArtwork)
        scene.compareAndSet(null, lastScene)
        if (role == AutoContract.PREVIEW) {
            remote.setPreviewSurface(lease, callbacks.previewSurfaceForReplay())
        }
        flushLatest()
    }

    /** close第一步：使排队的元素方案与旧图片回执失效。 */
    fun invalidateElements() {
        ++elementRevision
        lastElementPlan = null
    }

    /** close中清remove flush并清待发与last值；不删除io全部任务，Surface任务仍需运行。 */
    fun clearPendingAndRetained() {
        io.removeCallbacks(flush)
        media.set(null)
        navigation.set(null)
        art.set(null)
        scene.set(null)
        policy.set(null)
        lastScene = null
        lastMedia = null
        lastNavigation = null
        lastArtwork = null
    }

    /** close终态：订阅意图不残留，避免下一代连接复活。 */
    fun clearFeed() {
        feedKeys = emptyList()
        feedInterval = AutoContract.DEFAULT_FEED_INTERVAL_MS
    }

    /** 同一个runnable先remove再post，只合并现有五个槽。 */
    private fun scheduleFlush() {
        io.removeCallbacks(flush)
        io.post(flush)
    }

    /**
     * 进入时先捕获remote，call自身仍读取api并做closed/异常处理，两处读取时点保持不变。
     * 一次call包住整轮，异常后的后续槽不做预先清空，也不逐项吞错。
     */
    private fun flushLatest() {
        val remote = connection.currentApi ?: return
        if (connection.isClosed) return
        connection.call {
            scene.getAndSet(null)?.let { value -> remote.configureScene(connection.currentOwner, value) }
            policy.getAndSet(null)?.let { value -> remote.setProjectionPolicy(connection.currentOwner, value) }
            media.getAndSet(null)?.let { value -> remote.publishMedia(connection.currentOwner, value) }
            navigation.getAndSet(null)?.let { value -> remote.publishNavigation(connection.currentOwner, value) }
            art.getAndSet(null)?.let { artwork ->
                val bytes = artwork.bytes
                // 普通只读FD没有pipe写满/读端不消费造成的永久等待，临时文件仅在本App缓存中。
                val file = File.createTempFile("auto-art-", ".bin", app.cacheDir)
                try {
                    file.writeBytes(bytes)
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        remote.publishTrackArtwork(connection.currentOwner, artwork.track, fd)
                    }
                } finally {
                    file.delete()
                }
            }
        }
    }

    /** 图片方案在io打开调用方文件的只读FD，不复制到持久目录、不删除输入文件。 */
    private fun sendElements(
        remote: IAutoService,
        lease: IBinder,
        value: Bundle,
        image: File?,
        revision: Long,
    ) {
        if (value.getString("background") != ClusterDisplayConfiguration.IMAGE_BACKGROUND) {
            remote.configureClusterElements(lease, value)
            return
        }
        check(image?.isFile == true) { "background_image_missing" }
        val ticket = connection.currentGeneration
        ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            remote.configureClusterImage(
                lease,
                value,
                fd,
                object : IAutoResult.Stub() {
                    override fun onResult(result: Bundle) {
                        main.post {
                            if (!connection.isClosed &&
                                connection.currentGeneration == ticket &&
                                elementRevision == revision &&
                                !result.getBoolean("accepted")
                            ) {
                                onState("background_import_failed")
                            }
                        }
                    }
                },
            )
        }
    }
}
