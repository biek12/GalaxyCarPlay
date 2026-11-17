package com.galaxy.auto.client

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.galaxy.auto.client.ipc.ClientCallbackDispatch
import com.galaxy.auto.client.ipc.ClientConnectionSession
import com.galaxy.auto.client.ipc.ClientReplayPublisher
import com.galaxy.auto.client.ipc.ClientRequests
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.auto.contract.ProjectionPolicy
import com.galaxy.auto.contract.SceneConfiguration
import java.io.Closeable
import java.io.File

/**
 * 每客户端一代Binder租约的公开门面。
 *
 * 构造参数、24个公开方法与默认值保持原样；本类只持app/main、一个AutoClient-$role
 * worker与4个owner，负责装配、薄委托和逐项close顺序。业务缓存、ServiceConnection、
 * IAutoCallback、临时文件与RPC闭包都不留在Facade；4个owner在构造期全部装配完毕后
 * 才允许外部connect，装配传的是具体方法引用而不是可任意读写的Host。
 */
class AutoClient(
    context: Context,
    private val role: String,
    private val onSurface: (Surface?) -> Unit = {},
    private val onSnapshot: (Bundle) -> Unit = {},
    private val onState: (String) -> Unit = {},
    private val onCommand: (Int) -> Unit = {},
    private val onConfiguredSurface: (Surface?, Long) -> Unit = { _, _ -> },
    private val onVehicleFeed: (Bundle) -> Unit = {},
    private val onReady: () -> Unit = {},
    private val replayVehicleFeedOnConnect: Boolean = true,
    private val vehicleFeedOnWorker: Boolean = false,
) : Closeable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("AutoClient-$role").apply { start() }
    private val io = Handler(thread.looper)
    private val ownerClassLoader = javaClass.classLoader
    private val connection = ClientConnectionSession(app, role, main, io, onState, onReady)
    private val callbacks =
        ClientCallbackDispatch(
            connection,
            role,
            main,
            io,
            ownerClassLoader,
            vehicleFeedOnWorker,
            onSurface,
            onSnapshot,
            onState,
            onCommand,
            onConfiguredSurface,
            onVehicleFeed,
        )
    private val replay =
        ClientReplayPublisher(connection, callbacks, app, role, main, io, replayVehicleFeedOnConnect, onState)
    private val requests = ClientRequests(connection, app, main, io, ownerClassLoader)

    init {
        require(AutoContract.roleValid(role))
        connection.attach(callbacks::callbackFor, replay::replayOnConnect, callbacks::releaseBorrowed)
    }

    /** 主线程同步绑定入口，完整握手与恢复由Connection执行。 */
    fun connect() = connection.connect()

    /** 先写active再发io，重连按同一值补发。 */
    fun setActive(value: Boolean) = replay.setActive(value)

    /** 元素方案与图片FD由Publisher按revision提交。 */
    fun configureClusterElements(
        value: ClusterDisplayConfiguration,
        image: File? = null,
    ) = replay.configureClusterElements(value, image)

    /** 媒体清洗、切歌清封面与保留值由Publisher负责。 */
    fun publishMedia(value: Bundle) = replay.publishMedia(value)

    /** 可视歌词只发本次调用，不建立重连缓存。 */
    fun publishVisualMedia(value: Bundle) = replay.publishVisualMedia(value)

    /** 导航清洗与保留值由Publisher负责。 */
    fun publishNavigation(value: Bundle) = replay.publishNavigation(value)

    /** 默认track在调用时取Publisher当时的lastMedia.track或空串。 */
    fun publishArtwork(
        bytes: ByteArray,
        track: String = replay.defaultTrack(),
    ) = replay.publishArtwork(bytes, track)

    /** 场景兼容调用仍由Publisher保存并flush，服务端保持归档。 */
    fun configureScene(value: SceneConfiguration) = replay.configureScene(value)

    /** 策略只写待发槽，原本没有重连重放缓存。 */
    fun setProjectionPolicy(value: ProjectionPolicy) = replay.setProjectionPolicy(value)

    /** PREVIEW的Parcel复制、io投递与null清场景由CallbackDispatch与Publisher协作。 */
    fun setPreviewSurface(value: Surface?) = callbacks.setPreviewSurface(value, replay::clearSceneForPreview)

    /** OEM封面临时FD与票据回执由Requests按原边界处理。 */
    fun prepareOemArtwork(
        bytes: ByteArray,
        result: (Bundle) -> Unit,
    ) = requests.prepareOemArtwork(bytes, result)

    /** Shell默认超时10000，调用时捕获代次；正文不进入诊断。 */
    fun executeShell(
        transport: String,
        command: String,
        timeoutMillis: Int = 10000,
        result: (Bundle) -> Unit,
    ) = requests.executeShell(transport, command, timeoutMillis, result)

    /** 权限票据与granted=false回执形状由Requests保持。 */
    fun requestClientPermissions(
        permissions: List<String>,
        result: (Bundle) -> Unit,
    ) = requests.requestClientPermissions(permissions, result)

    /** 哨兵空闲只传powerSequence，没有缓存或重放。 */
    fun reportSentinelIdle(powerSequence: Long) = requests.reportSentinelIdle(powerSequence)

    /** 哨兵配置的accepted=false回执形状由Requests保持。 */
    fun configureSentinel(
        enabled: Boolean,
        result: (Bundle) -> Unit,
    ) = requests.configureSentinel(enabled, result)

    /** 上传只打开调用方文件的本次FD，回执与清理由Requests保持。 */
    fun vehicleWebUpload(
        clipId: String,
        direction: String,
        file: File,
        result: (Bundle) -> Unit,
    ) = requests.vehicleWebUpload(clipId, direction, file, result)

    /** Web请求的message/service_unavailable错误形状由Requests保持。 */
    fun vehicleWebRequest(
        action: String,
        payload: Bundle = Bundle(),
        result: (Bundle) -> Unit,
    ) = requests.vehicleWebRequest(action, payload, result)

    /** 分页固定limit32，结果只判closed。 */
    fun vehiclePage(
        offset: Int,
        catalog: Boolean = false,
        result: (Bundle) -> Unit,
    ) = requests.vehiclePage(offset, catalog, result)

    /** 单值结果只判closed，不补另一种票据语义。 */
    fun vehicleValue(
        propertyId: Int,
        areaId: Int,
        offset: Int,
        result: (Bundle) -> Unit,
    ) = requests.vehicleValue(propertyId, areaId, offset, result)

    /** 媒体命令不在客户端新增白名单。 */
    fun mediaCommand(command: Int) = requests.mediaCommand(command)

    /** 主线程同步校验后先保存订阅字段，io上读当前值发订阅。 */
    fun setVehicleFeed(
        keys: Collection<String>,
        intervalMillis: Int = AutoContract.DEFAULT_FEED_INTERVAL_MS,
    ) = replay.setVehicleFeed(keys, intervalMillis)

    /** 只读服务端已采集缓存，不触发采集、不新加generation过滤。 */
    fun getVehicleValues(
        keys: Collection<String>,
        result: (Bundle) -> Unit,
    ) = requests.getVehicleValues(keys, result)

    /** 诊断扫描的服务端本UID限制保持，不自动开启。 */
    fun setVehicleScan(enabled: Boolean) = requests.setVehicleScan(enabled)

    /**
     * 唯一终态：Connection锁内取旧lease，随后按代次/元素→retry→借用Surface→unbind
     * →清待发与保留→清feed的次序逐项交给对应owner，最后在io上detach/释放预览并quitSafely。
     * 某一个owner不提供closeAll，避免提前吞掉其它owner职责；worker只退出原那一个。
     */
    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val closing = connection.beginClose() ?: return
        connection.advanceGeneration()
        replay.invalidateElements()
        connection.removeRetry()
        callbacks.releaseBorrowed()
        connection.unbindForClose()
        replay.clearPendingAndRetained()
        replay.clearFeed()
        io.post {
            connection.detach(closing.first, closing.second)
            callbacks.releasePreview()
            thread.quitSafely()
        }
    }
}
