package com.ecarx.autoservice.remote

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.ecarx.autoservice.ipc.ClientRegistry
import com.ecarx.autoservice.web.VehicleWebBridge
import com.galaxy.auto.contract.IAutoResult

/**
 * 车辆网页和限时远程连接的所有者。
 *
 * 两边各自开关。授权先关掉现有连接再写设置；disable 使用 revoke，不用 close 代替。
 * 构造不启动网络。
 */
internal class ServiceRemoteSession(
    private val context: Context,
    private val main: Handler,
    private val postCallback: (Runnable) -> Unit,
    private val closed: () -> Boolean,
    private val paused: () -> Boolean,
    private val onPublish: () -> Unit,
    private val clients: ClientRegistry,
    private val webFactory: () -> WebPort = { WebAdapter(VehicleWebBridge(context)) },
    private val commandFactory: ((String) -> Unit) -> CommandPort = { report ->
        CommandAdapter(RemoteBridge(context, report))
    },
) {
    /** 隔离网页桥的启动、关闭、请求和上传。 */
    interface WebPort {
        /** 开始网页桥。 */
        fun start()

        /** 关闭网页桥。 */
        fun close()

        /** 提交一个已经校验过的动作。 */
        fun request(
            action: String,
            payload: Bundle,
            reply: (Bundle) -> Unit,
        )

        /** 接管上传副本。调用方不再关闭成功交给这里的 FD。 */
        fun upload(
            clipId: String,
            direction: String,
            fd: ParcelFileDescriptor,
            length: Long,
            reply: (Bundle) -> Unit,
        )
    }

    /** 隔离限时诊断连接。revoke 与 close 不是同一个动作。 */
    interface CommandPort {
        /** 开始连接。 */
        fun start()

        /** 关闭连接，不撤销授权。 */
        fun close()

        /** 撤销当前授权窗口。 */
        fun revoke()
    }

    private var generation = 0L
    private var web: WebPort? = null
    private var command: CommandPort? = null
    private var remoteState = "disabled"

    /** 最近一次采纳的远程状态。 */
    fun state(): String = remoteState

    /**
     * 按关闭、暂停和授权窗口分别维护网页桥与远程连接。
     *
     * 关闭远程时只有实例还在才增加代次。开启时先记代次，旧回调对不上就不再改状态。
     */
    @Synchronized
    fun update() {
        val webEnabled = !closed() && !paused()
        if (!webEnabled) {
            web?.close()
            web = null
        } else if (web == null) {
            web = webFactory().also { it.start() }
        }
        val enabled = !closed() && !paused() && RemoteSettings(context).enabled
        if (!enabled) {
            if (command != null) {
                ++generation
            }
            command?.close()
            command = null
            remoteState = "disabled"
        } else if (command == null) {
            val ticket = ++generation
            command =
                commandFactory { value ->
                    main.post {
                        if (!closed() && generation == ticket && command != null && remoteState != value) {
                            remoteState = value
                            onPublish()
                        }
                    }
                }.also { it.start() }
        }
    }

    /**
     * 重新授权。
     *
     * 先增加代次，再关闭网页和远程实例，然后按小时写入设置。默认仍由调用方传入 12。
     */
    fun authorize(hours: Int) {
        ++generation
        closeWeb()
        closeRemote()
        RemoteSettings(context).authorize(hours)
    }

    /**
     * 关闭限时诊断。
     *
     * 使用 revoke，不把 revoke 换成 close，也不改车辆绑定期限。
     */
    fun disable() {
        ++generation
        command?.revoke()
        RemoteSettings(context).disable()
        command = null
    }

    /**
     * 上传已经 dup 的副本。
     *
     * 租约校验和桥接上传在同一个 try 里。未登记、uid 不符、桥为空或上传抛错都关闭副本，
     * 并回「媒体上传不可用」。成功后的迟到租约不再回执。调用方仍持有原输入。
     */
    fun upload(
        owner: IBinder,
        calling: Int,
        clipId: String,
        direction: String,
        copy: ParcelFileDescriptor,
        length: Long,
        result: IAutoResult,
    ) {
        try {
            val lease = clients.require(owner, calling)
            checkNotNull(web).upload(clipId, direction, copy, length) { response ->
                main.post {
                    if (!closed() && leaseStillHeld(lease)) {
                        postCallback(Runnable { runCatching { result.onResult(response) } })
                    }
                }
            }
        } catch (
            error: Exception,
        ) {
            copy.close()
            postCallback(
                Runnable {
                    runCatching { result.onResult(Bundle().apply { putString("error", "媒体上传不可用") }) }
                },
            )
        }
    }

    /**
     * 转发网页动作。
     *
     * 桥为空时直接回「AutoService 自动运行已关闭」，这个分支不加 runCatching。
     * 成功后先在 main 确认租约，再发布，然后回结果。
     */
    fun request(
        lease: ClientRegistry.Lease,
        action: String,
        payload: Bundle,
        result: IAutoResult,
    ) {
        val bridge = web
        if (bridge == null) {
            postCallback(Runnable { result.onResult(Bundle().apply { putString("error", "AutoService 自动运行已关闭") }) })
        } else {
            bridge.request(action, payload) { response ->
                main.post {
                    if (!closed() && leaseStillHeld(lease)) {
                        onPublish()
                        postCallback(Runnable { runCatching { result.onResult(response) } })
                    }
                }
            }
        }
    }

    /** 只关闭网页桥。 */
    fun closeWeb() {
        web?.close()
        web = null
    }

    /** 只关闭远程连接，不 revoke。 */
    fun closeRemote() {
        command?.close()
        command = null
    }

    /** 注册表里仍是当初那一个租约对象。移除或换上新对象后为假。 */
    private fun leaseStillHeld(lease: ClientRegistry.Lease): Boolean = clients.all().any { it === lease }

    private class WebAdapter(
        private val bridge: VehicleWebBridge,
    ) : WebPort {
        override fun start() = bridge.start()

        override fun close() = bridge.close()

        override fun request(
            action: String,
            payload: Bundle,
            reply: (Bundle) -> Unit,
        ) = bridge.request(action, payload, reply)

        override fun upload(
            clipId: String,
            direction: String,
            fd: ParcelFileDescriptor,
            length: Long,
            reply: (Bundle) -> Unit,
        ) = bridge.upload(clipId, direction, fd, length, reply)
    }

    private class CommandAdapter(
        private val bridge: RemoteBridge,
    ) : CommandPort {
        override fun start() = bridge.start()

        override fun close() = bridge.close()

        override fun revoke() = bridge.revoke()
    }
}
