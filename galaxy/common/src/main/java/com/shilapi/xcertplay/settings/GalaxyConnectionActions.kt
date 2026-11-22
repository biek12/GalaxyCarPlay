package com.shilapi.xcertplay.settings

import android.view.View
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.L7Icons
import com.shilapi.xcertplay.L7SettingRow
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayTransport

/**
 * 连接、重连和从首页停止。
 *
 * 等待状态留在本类。平台和会话通过具名边界注入，生产环境指向现有单例和宿主回调。
 * 页面关闭或重建后增加代次，迟到的停止完成回调不再保存传输方式或打开投屏。
 */
internal class GalaxyConnectionActions(
    private val session: GalaxyConnectionSession = GalaxyConnectionSession(),
    private val platform: GalaxyConnectionPlatform = GalaxyConnectionPlatform(),
) {
    /** 一次连接尚未走完停止或打开。重复的连接在原检查点被拒绝。 */
    var pending: Boolean = false

    /** 通知权限弹窗关闭后继续使用的传输方式。 */
    var notificationTransport: Boolean = true

    private var generation = 0
    private var lastRunning: Boolean? = null

    /** 使已登记的停止完成回调失效。宿主销毁或配置重建时调用。 */
    fun abandon() {
        generation += 1
    }

    /** 换页后按钮是新实例，运行状态对比重新开始。 */
    fun resetPresentation() {
        lastRunning = null
    }

    /**
     * 按原顺序发起连接。
     * 宿主已销毁时直接返回，避免页面重建再次弹出无会话前置。
     * 已有等待中的请求仍会先走原前置检查，到原等待点才拒绝第二次打开。
     */
    fun connect(wireless: Boolean, nativeHotspotPrepared: Boolean = false) {
        if (!platform.alive()) return
        if (!platform.requireAgreement()) return
        platform.stopReporting()
        platform.stopCodecProbe()
        if (platform.confirmAudio { connect(wireless, nativeHotspotPrepared) }) return
        if (wireless && !session.hasSession() && !platform.ensurePrerequisite()) return
        val direct = platform.directMode()
        if (wireless && direct && !platform.ensureDirect()) return
        if (wireless && platform.hotspotBusy()) {
            platform.problem(R.string.l7_hotspot_busy)
            return
        }
        if (wireless && !direct && !nativeHotspotPrepared && platform.setupError() == null && !session.hasSession()) {
            platform.startGate({
                platform.clearHotspotSetupIfValid()
                connect(wireless = true, nativeHotspotPrepared = true)
            }, platform.openWirelessPage)
            return
        }
        platform.log(if (wireless) "请求无线连接" else "请求 USB 有线连接")
        if (wireless && !direct && platform.hotspotSetupPending()) {
            platform.openWirelessPage()
            platform.problem(R.string.save_your_hotspot_details_in_connection_setup_first)
            return
        }
        platform.setupError()?.let {
            platform.problemText(it)
            return
        }
        if (wireless && !direct && platform.hotspotInvalid()) {
            platform.markHotspotSetup()
            platform.openWirelessPage()
            platform.problem(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings)
            return
        }
        if (wireless && platform.carHotspotOff()) {
            platform.showHotspotOff()
            return
        }
        if (wireless && platform.phoneMissing()) {
            platform.choosePhone()
            return
        }
        if (platform.notificationNeeded()) {
            platform.markNotificationAsked()
            notificationTransport = wireless
            platform.requestNotification(wireless)
            return
        }
        if (pending) return
        pending = true
        platform.refresh()
        val token = generation

        fun open() {
            pending = false
            if (token != generation || !platform.alive() || !platform.canUse()) return
            platform.saveWireless(wireless)
            platform.openProjection()
        }
        if (session.hasSession()) {
            session.stop { platform.runOnMain { open() } }
        } else {
            open()
        }
    }

    /**
     * 沿当前会话的传输方式重连。
     * 正在停止、已有等待、没有会话或会话已关闭时不创建新的连接。
     */
    fun reconnectCurrent() {
        if (!platform.alive()) return
        if (pending || session.isStopping()) return
        val wireless = session.currentWireless()
        if (wireless == null) {
            platform.refresh()
            platform.problem(R.string.l7_reconnect_no_session)
            return
        }
        platform.log("手动重连当前会话 transport=${if (wireless) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED}")
        connect(wireless)
    }

    /**
     * 从首页或无线页停止。
     * 已经连接时先确认；等待连接时直接取消。完成回到主线程后才刷新，销毁后的宿主不再更新。
     */
    fun stopFromHome() {
        if (!platform.alive()) return
        val stop = {
            platform.disableDisconnect()
            val token = generation
            session.stop {
                platform.runOnMain {
                    if (token == generation && platform.alive()) platform.refresh()
                }
            }
        }
        if (session.active()) platform.confirmDisconnect(stop) else stop()
    }

    /** 刷新重连行的可用状态和说明。 */
    fun applyReconnect(row: L7SettingRow?, setupError: String?) {
        val reconnectable = session.reconnectable()
        val restarting = pending || session.isStopping()
        row?.apply {
            isEnabled = reconnectable && !restarting && setupError == null
            setFeedback(
                when {
                    restarting -> context.getString(R.string.reconnecting_to_your_iphone)
                    setupError != null -> setupError
                    !reconnectable -> context.getString(R.string.l7_reconnect_no_session)
                    else -> context.getString(R.string.l7_reconnect_ready)
                },
            )
        }
    }

    /**
     * 刷新连接和断开按钮。
     * 运行状态变化时先按原文字更新，再用当前等待、会话和页面覆盖最终文案。
     */
    fun applyButtons(
        connectButton: Button?,
        disconnectButton: Button?,
        usbButton: Button?,
        setupError: String?,
        home: Boolean,
    ) {
        val running = session.hasSession()
        if (lastRunning != running) {
            connectButton?.replaceText(
                if (running) {
                    connectButton.context.getString(
                        R.string.open_carplay,
                    )
                } else {
                    connectButton.context.getString(R.string.connect_phone)
                },
            )
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.replaceText(
            connectButton.context.getString(
                when {
                    pending -> R.string.l7_preparing
                    session.active() -> R.string.l7_resume_projection
                    running -> R.string.l7_view_connection
                    home -> R.string.l7_entry_connect
                    else -> R.string.l7_start_wireless
                },
            ),
        )
        disconnectButton?.replaceText(
            disconnectButton.context.getString(
                if (session.active()) R.string.disconnect else R.string.l7_cancel_connection,
            ),
        )
        usbButton?.isEnabled = !running && !pending && setupError == null
        connectButton?.isEnabled = !pending
    }

    /** 文字没变时不重复设置，避免持续的无障碍事件。 */
    private fun TextView.replaceText(value: String) {
        if (text.toString() != value) {
            text = value
            if (this is Button) L7Icons.decorate(this)
        }
    }
}

/**
 * 会话边界。生产默认读 [CarPlayBackgroundSession]，测试传入记录实现。
 * 不复制会话状态，只转发查询和停止。
 */
internal class GalaxyConnectionSession(
    val hasSession: () -> Boolean = { CarPlayBackgroundSession.hasSession() },
    val isStopping: () -> Boolean = { CarPlayBackgroundSession.isStopping() },
    val active: () -> Boolean = { CarPlayBackgroundSession.active },
    val reconnectable: () -> Boolean = {
        CarPlayBackgroundSession.snapshot()?.controller?.let { !it.isClosed() } == true
    },
    val currentWireless: () -> Boolean? = {
        val controller = CarPlayBackgroundSession.snapshot()?.controller
        if (controller == null || controller.isClosed()) {
            null
        } else {
            controller.transport == CarPlayTransport.WIRELESS
        }
    },
    val stop: (completion: () -> Unit) -> Unit = { completion -> CarPlayBackgroundSession.stop(completion) },
)

/**
 * 平台边界：协议确认、热点、权限、页面和投屏打开都由宿主提供具体动作。
 * 默认值不触碰设备；生产宿主在创建动作时逐项接上现有实现。
 */
internal class GalaxyConnectionPlatform {
    /** 宿主窗口仍可接收结果。销毁或正在结束时为 false。 */
    var alive: () -> Boolean = { false }

    /** 协议门闩。未通过时不得继续连接。 */
    var requireAgreement: () -> Boolean = { false }

    /** 同意仍有效，允许在停止完成后打开投屏。 */
    var canUse: () -> Boolean = { false }

    /** 停掉正在进行的上报测试。 */
    var stopReporting: () -> Unit = {}

    /** 停掉编码探测。 */
    var stopCodecProbe: () -> Unit = {}

    /** 车型音频确认。返回 true 表示已经接住本次连接，确认后才会调用重试。 */
    var confirmAudio: (retry: () -> Unit) -> Boolean = { false }

    /** 首次无线前置。返回 false 表示已经提示，本次不继续。 */
    var ensurePrerequisite: () -> Boolean = { true }

    /** 当前保存的无线方式是否为 Wi-Fi Direct。 */
    var directMode: () -> Boolean = { false }

    /** Wi-Fi Direct 是否可用且无线已开。 */
    var ensureDirect: () -> Boolean = { true }

    /** 热点任务是否仍在执行。 */
    var hotspotBusy: () -> Boolean = { false }

    /** 用资源提示连接问题。 */
    var problem: (message: Int) -> Unit = {}

    /** 用已生成的文字提示连接问题。 */
    var problemText: (message: String) -> Unit = {}

    /** 启动认证错误；没有错误时为空。 */
    var setupError: () -> String? = { null }

    /** 热点资料已经有效时清掉「待保存」标记。 */
    var clearHotspotSetupIfValid: () -> Unit = {}

    /** 启动原热点 gate。就绪和需要进入设置分别回调。 */
    var startGate: (onReady: () -> Unit, onSetup: () -> Unit) -> Unit = { _, _ -> }

    /** 是否还要求用户先保存热点。 */
    var hotspotSetupPending: () -> Boolean = { false }

    /** 打开无线设置页并刷新。 */
    var openWirelessPage: () -> Unit = {}

    /** 记下热点资料尚未保存。 */
    var markHotspotSetup: () -> Unit = {}

    /** MANUAL 热点名称或密码当前不可用。 */
    var hotspotInvalid: () -> Boolean = { false }

    /** 车机热点开关处于关闭。 */
    var carHotspotOff: () -> Boolean = { false }

    /** 显示原车机热点关闭提示。 */
    var showHotspotOff: () -> Unit = {}

    /** 还没有选择手机。 */
    var phoneMissing: () -> Boolean = { false }

    /** 进入原手机选择，并记住这次连接在选择后继续。 */
    var choosePhone: () -> Unit = {}

    /** Android 13 及以上尚未询问过通知权限。 */
    var notificationNeeded: () -> Boolean = { false }

    /** 记下已经询问过通知权限，避免下次连接重复弹出。 */
    var markNotificationAsked: () -> Unit = {}

    /** 发起系统通知权限请求。结果只回到 [GalaxyConnectionActions.connect]。 */
    var requestNotification: (wireless: Boolean) -> Unit = {}

    /** 停止完成后保存本次传输方式。 */
    var saveWireless: (wireless: Boolean) -> Unit = {}

    /** 打开原投屏页。 */
    var openProjection: () -> Unit = {}

    /** 刷新当前页面上的连接状态。 */
    var refresh: () -> Unit = {}

    /** 已连接时的停止确认。取消不调用参数。 */
    var confirmDisconnect: (onConfirm: () -> Unit) -> Unit = { confirm -> confirm() }

    /** 停止开始后禁用断开按钮。 */
    var disableDisconnect: () -> Unit = {}

    /** 把停止完成切回主线程。 */
    var runOnMain: (() -> Unit) -> Unit = { action -> action() }

    /** 记录不含密钥和设备标识的连接阶段。 */
    var log: (message: String) -> Unit = {}
}
