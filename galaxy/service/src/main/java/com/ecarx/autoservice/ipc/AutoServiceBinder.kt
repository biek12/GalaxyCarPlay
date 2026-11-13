package com.ecarx.autoservice.ipc

import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.Surface
import com.ecarx.autoservice.media.ServiceMediaSession
import com.ecarx.autoservice.oem.ClusterElementsSession
import com.ecarx.autoservice.platform.foundation.ClientPermissionPolicy
import com.ecarx.autoservice.platform.foundation.ClientPermissionRequests
import com.ecarx.autoservice.platform.foundation.ServiceShellRequests
import com.ecarx.autoservice.remote.ServiceRemoteSession
import com.ecarx.autoservice.sentinel.ServiceSentinelRequests
import com.ecarx.autoservice.vehicle.ServiceVehicleSession
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.auto.contract.IAutoCallback
import com.galaxy.auto.contract.IAutoResult
import com.galaxy.auto.contract.IAutoService

/**
 * 29 个 AIDL 方法的唯一 Binder 实现。
 *
 * 在调用线程捕获 UID 和包名，同步拒绝发生在排队之前。排队后的租约校验仍在 main 闭包里。
 */
internal class AutoServiceBinder(
    private val callerIdentity: ServiceCallerIdentity,
    private val requestQueue: ServiceRequestQueue,
    private val packagesForUid: (Int) -> List<String>,
    private val hasAdmin: (Int) -> Boolean,
    private val processUid: () -> Int,
    private val clients: ClientRegistry,
    private val sessions: ServiceClientSessions,
    private val elements: ClusterElementsSession,
    private val media: ServiceMediaSession,
    private val vehicle: ServiceVehicleSession,
    private val shellRequests: ServiceShellRequests,
    private val permissionRequests: ClientPermissionRequests,
    private val sentinelRequests: ServiceSentinelRequests,
    private val remote: ServiceRemoteSession,
    private val snapshots: ServiceSnapshots,
    private val publish: () -> Unit,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) : IAutoService.Stub() {
    /** 在当前 Binder 线程读取并校验调用 UID。 */
    private fun uid(): Int = callerIdentity.requireUid()

    /** 先在 Binder 线程生成来源，再交给既有 main 队列。 */
    private fun enqueue(block: () -> Unit) = requestQueue.enqueue(callerIdentity.source(), block)

    /** 返回契约版本。签名不通过时不会返回。 */
    override fun getProtocolVersion(): Int {
        uid()
        return AutoContract.VERSION
    }

    /** 返回当前快照缓存，不重新组装。 */
    override fun getSnapshot(): Bundle {
        uid()
        return snapshots.current()
    }

    /** 按目录分页。 */
    override fun getVehicleCatalog(
        offset: Int,
        limit: Int,
    ): Bundle {
        uid()
        return vehicle.page(offset, limit, true)
    }

    /** 按属性分页。 */
    override fun getVehicleProperties(
        offset: Int,
        limit: Int,
    ): Bundle {
        uid()
        return vehicle.page(offset, limit, false)
    }

    /** 读取一条缓存属性值。 */
    override fun getVehicleValue(
        propertyId: Int,
        areaId: Int,
        characterOffset: Int,
    ): Bundle {
        uid()
        return vehicle.value(propertyId, areaId, characterOffset)
    }

    /**
     * 登记客户端。
     *
     * MAP 必须是 CarPlay 包。主题、预览、元素和可视音乐必须是 Cluster 包。包校验在排队前完成。
     */
    override fun attach(
        owner: IBinder,
        role: String,
        callback: IAutoCallback,
    ) {
        val calling = uid()
        require(AutoContract.roleValid(role))
        val packages = packagesForUid(calling)
        if (role == AutoContract.MAP) {
            check("com.ecarx.carplay" in packages)
        }
        if (role == AutoContract.THEME ||
            role == AutoContract.PREVIEW ||
            role == AutoContract.ELEMENTS ||
            role == AutoContract.VISUAL_MUSIC
        ) {
            check(AutoContract.CLUSTER_PACKAGE in packages)
        }
        enqueue {
            if (sessions.attach(owner, calling, role, callback)) {
                publish()
            }
        }
    }

    /** 校验同 UID 租约后移除。 */
    override fun detach(owner: IBinder) {
        val calling = uid()
        enqueue {
            clients.require(owner, calling)
            sessions.detach(owner)
        }
    }

    /** 切换 active。主题和预览不发布。 */
    override fun setActive(
        owner: IBinder,
        active: Boolean,
    ) {
        val calling = uid()
        enqueue {
            if (sessions.setActive(owner, calling, active)) {
                publish()
            }
        }
    }

    /** 写入元素配置。相同配置不发布。 */
    override fun configureClusterElements(
        owner: IBinder,
        configuration: Bundle,
    ) {
        val calling = uid()
        val safe = ClusterDisplayConfiguration.from(configuration)
        enqueue {
            val lease = clients.require(owner, calling)
            if (elements.configure(lease, safe)) {
                publish()
            }
        }
    }

    /**
     * 导入背景图。
     *
     * 只接受 Cluster 包和有界普通文件。dup 后把副本交给元素会话，原 FD 仍由调用方持有。
     */
    override fun configureClusterImage(
        owner: IBinder,
        configuration: Bundle,
        image: ParcelFileDescriptor,
        result: IAutoResult,
    ) {
        val calling = uid()
        check(AutoContract.CLUSTER_PACKAGE in packagesForUid(calling))
        val safe = ClusterDisplayConfiguration.from(configuration)
        require(safe.background == ClusterDisplayConfiguration.IMAGE_BACKGROUND)
        val info = Os.fstat(image.fileDescriptor)
        require(OsConstants.S_ISREG(info.st_mode) && info.st_size in 1..16L * 1024 * 1024) {
            "background_requires_bounded_file"
        }
        val duplicate = ParcelFileDescriptor.dup(image.fileDescriptor)
        elements.submitImage(owner, calling, safe, duplicate, result)
    }

    /** CarPlay 媒体。必须持有 MAP 租约。 */
    override fun publishMedia(
        owner: IBinder,
        value: Bundle,
    ) {
        val calling = uid()
        val safe = AutoContract.sanitizeMedia(value)
        enqueue {
            check(clients.require(owner, calling).role == AutoContract.MAP)
            media.publishCarPlay(safe)
            publish()
        }
    }

    /** 可视歌词。必须持有可视音乐租约，曲目不一致时不发布。 */
    override fun publishVisualMedia(
        owner: IBinder,
        value: Bundle,
    ) {
        val calling = uid()
        val safe = AutoContract.sanitizeMedia(value)
        enqueue {
            check(clients.require(owner, calling).role == AutoContract.VISUAL_MUSIC)
            if (media.publishVisual(safe)) {
                publish()
            }
        }
    }

    /** 原厂封面。调用方必须是 CarPlay 包。 */
    override fun prepareOemArtwork(
        owner: IBinder,
        fd: ParcelFileDescriptor,
        result: IAutoResult,
    ) {
        val calling = uid()
        check("com.ecarx.carplay" in packagesForUid(calling))
        media.prepareOemArtwork(owner, calling, fd, result)
    }

    /** 带曲目的封面。同步校验后交给媒体会话。 */
    override fun publishTrackArtwork(
        owner: IBinder,
        track: String,
        fd: ParcelFileDescriptor,
    ) = media.receiveArtwork(owner, uid(), fd, track)

    /** 导航。必须持有 MAP 租约。 */
    override fun publishNavigation(
        owner: IBinder,
        value: Bundle,
    ) {
        val calling = uid()
        val safe = AutoContract.sanitizeNavigation(value)
        enqueue {
            check(clients.require(owner, calling).role == AutoContract.MAP)
            media.publishNavigation(safe)
            publish()
        }
    }

    /** 当前曲目封面。 */
    override fun publishArtwork(
        owner: IBinder,
        fd: ParcelFileDescriptor,
    ) = media.receiveArtwork(owner, uid(), fd, null)

    /**
     * 排队后才执行 Shell。
     *
     * ADMIN 权限、传输和命令长度都在 Binder 线程拒绝，不提前做 IO。
     */
    override fun executeShell(
        owner: IBinder,
        transport: String,
        command: String,
        timeoutMillis: Int,
        result: IAutoResult,
    ) {
        val calling = uid()
        check(hasAdmin(calling))
        require(
            transport in setOf("ROOT", "ADB") &&
                command.length in 1..8192 &&
                '\u0000' !in command &&
                timeoutMillis in 100..30000,
        )
        enqueue {
            val lease = clients.require(owner, calling)
            shellRequests.execute(lease, command, timeoutMillis, transport, result)
        }
    }

    /** 只接受 0、1、2，并回送给当前 MAP。 */
    override fun mediaCommand(
        owner: IBinder,
        command: Int,
    ) {
        val calling = uid()
        require(command in setOf(0, 1, 2))
        enqueue { sessions.mediaCommand(owner, calling, command) }
    }

    /** 旧场景配置只回归档态，不保存 scene。 */
    override fun configureScene(
        owner: IBinder,
        value: Bundle,
    ) {
        val calling = uid()
        enqueue { sessions.configureScene(owner, calling) }
    }

    /** 先释放 Surface，再由会话判断关闭和租约。 */
    override fun setPreviewSurface(
        owner: IBinder,
        surface: Surface?,
    ) {
        val calling = uid()
        sessions.setPreviewSurface(owner, calling, surface)
    }

    /** 旧投屏策略只回归档态。调用方必须是 Cluster 包。 */
    override fun setProjectionPolicy(
        owner: IBinder,
        value: Bundle,
    ) {
        val calling = uid()
        check(AutoContract.CLUSTER_PACKAGE in packagesForUid(calling))
        enqueue { sessions.setProjectionPolicy(owner, calling) }
    }

    /** 订阅车辆键。格式在排队前检查，车型目录在 main 上校验。 */
    override fun setVehicleFeed(
        owner: IBinder,
        keys: Array<String>?,
        minIntervalMillis: Int,
    ) {
        val calling = uid()
        check(AutoContract.feedKeysValid(keys))
        check(AutoContract.feedIntervalValid(minIntervalMillis)) { "invalid_feed_interval" }
        enqueue {
            val lease = clients.require(owner, calling)
            vehicle.setFeed(lease, keys, minIntervalMillis, now())
        }
    }

    /** 只读缓存。不触发采集。 */
    override fun getVehicleValues(keys: Array<String>?): Bundle {
        uid()
        check(AutoContract.feedKeysValid(keys))
        return vehicle.values(keys)
    }

    /** 权限申请。允许列表在排队前拒绝。 */
    override fun requestClientPermissions(
        owner: IBinder,
        permissions: Array<String>?,
        result: IAutoResult,
    ) {
        val calling = uid()
        val requested = permissions.orEmpty().distinct()
        check(ClientPermissionPolicy.allowed(packagesForUid(calling), requested)) { "permission_request_rejected" }
        enqueue {
            val lease = clients.require(owner, calling)
            permissionRequests.request(lease, calling, requested, result)
        }
    }

    /** 哨兵空闲。调用方必须是记录仪包。 */
    override fun reportSentinelIdle(
        owner: IBinder,
        powerSequence: Long,
    ) {
        val calling = uid()
        check("com.galaxy.autorecord" in packagesForUid(calling))
        enqueue {
            clients.require(owner, calling)
            sentinelRequests.idle(powerSequence)
        }
    }

    /** 哨兵开关。错误包在排队前以 sentinel_client_rejected 拒绝。 */
    override fun configureSentinel(
        owner: IBinder,
        enabled: Boolean,
        result: IAutoResult,
    ) {
        val calling = uid()
        check("com.galaxy.autorecord" in packagesForUid(calling)) { "sentinel_client_rejected" }
        enqueue {
            clients.require(owner, calling)
            sentinelRequests.configure(enabled, result)
        }
    }

    /**
     * 上传媒体副本。
     *
     * 原 FD 仍由调用方持有，这里只 dup。记录仪包、片段号、方向和长度在排队前检查。
     * 租约校验留在远程会话的同一个 try 里，失败仍关闭副本并回原上传错误。
     */
    override fun vehicleWebUpload(
        owner: IBinder,
        clipId: String,
        direction: String,
        file: ParcelFileDescriptor,
        length: Long,
        result: IAutoResult,
    ) {
        val calling = uid()
        check("com.galaxy.autorecord" in packagesForUid(calling))
        require(
            clipId.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
                direction in
                listOf(
                    "front",
                    "rear",
                    "left",
                    "right",
                    "index",
                    "open-front",
                    "open-rear",
                    "open-left",
                    "open-right",
                ),
        )
        require(length in 12..268435456)
        val copy = ParcelFileDescriptor.dup(file.fileDescriptor)
        enqueue {
            remote.upload(owner, calling, clipId, direction, copy, length, result)
        }
    }

    /**
     * 网页请求。
     *
     * 本进程或记录仪才能调用。record-* 还要求记录仪包。动作和 json 长度在排队前检查。
     */
    override fun vehicleWebRequest(
        owner: IBinder,
        action: String,
        payload: Bundle,
        result: IAutoResult,
    ) {
        val calling = uid()
        val packages = packagesForUid(calling)
        check(calling == processUid() || "com.galaxy.autorecord" in packages) { "web_client_rejected" }
        check(
            action in
                setOf(
                    "state",
                    "enable",
                    "code",
                    "confirm",
                    "unbind",
                    "disable",
                    "record-publish",
                    "record-poll",
                    "record-result",
                    "record-telemetry",
                    "record-signals",
                    "record-live-status",
                ),
        )
        if (action.startsWith("record-")) {
            check("com.galaxy.autorecord" in packages)
        }
        require((payload.getString("json")?.length ?: 0) <= 384 * 1024)
        enqueue {
            val lease = clients.require(owner, calling)
            remote.request(lease, action, payload, result)
        }
    }

    /** 全目录扫描只允许本进程。 */
    override fun setVehicleScan(enabled: Boolean) {
        check(uid() == processUid()) { "local_only" }
        vehicle.setScan(enabled)
    }
}
