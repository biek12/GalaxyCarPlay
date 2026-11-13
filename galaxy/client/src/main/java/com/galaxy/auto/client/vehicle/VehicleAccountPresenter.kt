package com.galaxy.auto.client.vehicle

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.client.R
import com.galaxy.auto.client.databinding.ActivityVehicleAccountBinding
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.ui.GalaxyDialogs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账号页的连接、请求和呈现。
 *
 * 持有同一个 [AutoClient] 端口，保存最新车辆 JSON、请求代次和两秒轮询。
 * Activity 只转发生命周期。断线会使当前票据失效并放开 busy；未变化的 JSON 仍会按时间重新计算
 * 绑定码和待确认行是否可见。纯刷新不会额外发请求。
 */
class VehicleAccountPresenter(
    private val binding: ActivityVehicleAccountBinding,
    private val port: ClientPort,
    private val qr: VehicleQrRenderer,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onBack: () -> Unit,
    private val main: Handler = Handler(Looper.getMainLooper()),
) {
    private val adapter =
        VehicleBindingRequestsAdapter(binding.bindingRequests) { bindingId, accepted ->
            request(
                ACTION_CONFIRM,
                Bundle().apply {
                    putString(KEY_BINDING_ID, bindingId)
                    putBoolean(KEY_ACCEPTED, accepted)
                },
            )
        }
    private val expiryFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private val tick: Runnable =
        Runnable {
            if (closed || !ticking) {
                return@Runnable
            }
            render()
            if (connected && !busy) {
                request(ACTION_STATE, Bundle())
            }
            if (ticking && !closed) {
                main.postDelayed(tick, TICK_MS)
            }
        }

    private var closed = false
    private var connected = false
    private var busy = false
    private var ticking = false
    private var processing = false
    private var disconnected = false
    private var ticket = 0L
    private var rawJson: String? = null
    private var replyError: String? = null
    private var shown: VehicleAccountState? = null
    private var unbindDialog: AlertDialog? = null

    /** 已有连接端口：只转发 connect、close 和 vehicleWebRequest。 */
    interface ClientPort {
        /** 开始连接。调用方保证在主线程。 */
        fun connect()

        /** 关闭这一份连接，重复调用由端口自己忽略。 */
        fun close()

        /**
         * 发送一次账号动作。
         *
         * [result] 可能在工作线程回来，Presenter 会切回主线程并核对票据。
         */
        fun request(
            action: String,
            payload: Bundle,
            result: (Bundle) -> Unit,
        )
    }

    /**
     * 用真实 [AutoClient] 实现端口。
     *
     * 构造参数保持观察者角色和原有 onReady/onState。这里不加第二套重连或协议。
     */
    class AutoClientPort(
        context: Context,
        onReady: () -> Unit,
        onState: (String) -> Unit,
    ) : ClientPort {
        private val client =
            AutoClient(
                context,
                AutoContract.OBSERVER,
                onReady = onReady,
                onState = onState,
            )

        /** 连接 AutoService。 */
        override fun connect() {
            client.connect()
        }

        /** 关闭这一份 AutoClient。 */
        override fun close() {
            client.close()
        }

        /** 把动作原样交给 vehicleWebRequest。 */
        override fun request(
            action: String,
            payload: Bundle,
            result: (Bundle) -> Unit,
        ) {
            client.vehicleWebRequest(action, payload, result)
        }
    }

    /**
     * 绑定返回、申请码、解绑和在线开关。
     *
     * 待确认行的按钮在适配器里绑定。此方法不发请求。
     */
    fun bindActions() {
        binding.backButton.setOnClickListener { onBack() }
        binding.requestCodeButton.setOnClickListener {
            if (!binding.requestCodeButton.isEnabled) {
                return@setOnClickListener
            }
            request(
                ACTION_CODE,
                Bundle().apply { putBoolean(KEY_REFRESH, true) },
            )
        }
        binding.unbindButton.setOnClickListener {
            if (binding.unbindButton.isEnabled) {
                confirmUnbind()
            }
        }
        binding.onlineButton.setOnClickListener {
            if (!binding.onlineButton.isEnabled) {
                return@setOnClickListener
            }
            val enabled = shown?.enabled == true
            request(if (enabled) ACTION_DISABLE else ACTION_ENABLE, Bundle())
        }
    }

    /** 由 Activity 在点击绑定之后调用，开始唯一连接。 */
    fun connect() {
        if (!closed) {
            port.connect()
        }
    }

    /**
     * 新连接已经可用。
     *
     * 丢弃断线前的票据并立刻请求 state。旧回执不能清掉这次 busy，也不能覆盖新状态。
     */
    fun onReady() {
        onMain {
            if (closed) {
                return@onMain
            }
            connected = true
            disconnected = false
            ticket += 1
            busy = false
            processing = false
            request(ACTION_STATE, Bundle())
        }
    }

    /**
     * 连接失败或丢失。
     *
     * 只认 AutoClient 已经发出的断开原因。当前票据作废，busy 放开，页面显示未连接。
     * 不在这里启动新的重连计时。
     */
    fun onConnectionState(value: String) {
        if (!connectionLost(value)) {
            return
        }
        onMain {
            if (closed) {
                return@onMain
            }
            connected = false
            disconnected = true
            ticket += 1
            busy = false
            processing = false
            render()
        }
    }

    /**
     * 启动唯一轮询。
     *
     * 立即跑一拍，之后每 2 秒一拍。重复 start 不会再挂一条。离线或 busy 时仍刷新时间可见性，
     * 但只有已连接且空闲才发 state。
     */
    fun onStart() {
        if (closed || ticking) {
            return
        }
        ticking = true
        main.post(tick)
    }

    /** 停止轮询。不关闭在线服务，也不解除绑定。 */
    fun onStop() {
        ticking = false
        main.removeCallbacks(tick)
    }

    /**
     * 关闭页面持有的弹窗、二维码和连接。
     *
     * 先标记关闭并使票据失效，再清 ImageView。之后的回执不能再写控件。
     */
    fun close() {
        if (closed) {
            return
        }
        closed = true
        ticking = false
        ticket += 1
        busy = false
        processing = false
        main.removeCallbacks(tick)
        binding.codeQr.setImageDrawable(null)
        unbindDialog?.dismiss()
        unbindDialog = null
        qr.close()
        port.close()
    }

    /**
     * 发送一次动作。
     *
     * 已关闭、未连接或正在忙时直接返回。非 state 才显示“正在处理…”。回执必须对上票据才采纳。
     */
    private fun request(
        action: String,
        payload: Bundle,
    ) {
        if (closed || !connected || busy) {
            return
        }
        val mine = ++ticket
        busy = true
        processing = action != ACTION_STATE
        applyChrome()
        port.request(action, payload) { reply ->
            onMain {
                if (closed || mine != ticket) {
                    return@onMain
                }
                busy = false
                processing = false
                accept(reply)
                render()
            }
        }
    }

    /**
     * 保存回执。
     *
     * 没有 json 或 json 无法解析时保留上一份车辆数据。error 仍然记下，避免把失败显示成成功。
     */
    private fun accept(reply: Bundle) {
        replyError = reply.getString("error")
        val json = reply.getString("json") ?: return
        if (VehicleAccountState.parse(json, clock()) != null) {
            rawJson = json
        }
    }

    /**
     * 用同一份 JSON 和当前时间重算可见内容。
     *
     * 状态没变时不重建行，也不重新排队二维码。
     */
    private fun render() {
        if (closed) {
            return
        }
        val state = rawJson?.let { VehicleAccountState.parse(it, clock()) }
        if (state != null && state != shown) {
            shown = state
            bind(state)
        }
        applyChrome()
    }

    /** 把一份呈现状态写到 XML 控件。只改文字、可见性和二维码，不增删整页。 */
    private fun bind(state: VehicleAccountState) {
        val context = binding.root.context
        binding.vehicleCard.visibility = View.VISIBLE
        binding.vehicleModel.text = context.getString(R.string.vehicle_account_model, state.model)
        binding.vehicleVersions.text =
            context.getString(
                R.string.vehicle_account_versions,
                state.androidVersion,
                state.serviceVersion,
            )
        binding.boundCard.visibility = if (state.bound) View.VISIBLE else View.GONE
        binding.codeCard.visibility = if (state.bound) View.GONE else View.VISIBLE
        binding.onlineCard.visibility = View.VISIBLE
        binding.onlineButton.setText(
            if (state.enabled) {
                R.string.vehicle_account_online_pause
            } else {
                R.string.vehicle_account_online_enable
            },
        )
        if (state.bound) {
            binding.ownerLine.text =
                context.getString(
                    R.string.vehicle_account_owner,
                    state.ownerName,
                    state.ownerPhone,
                )
            hideCode()
        } else {
            binding.codeTitle.setText(
                if (state.codeActive) {
                    R.string.vehicle_account_code_title_active
                } else {
                    R.string.vehicle_account_code_title_idle
                },
            )
            binding.requestCodeButton.setText(
                if (state.codeActive) {
                    R.string.vehicle_account_code_refresh
                } else {
                    R.string.vehicle_account_code_request
                },
            )
            bindCode(state)
        }
        adapter.submit(state.bindings, actionsEnabled())
    }

    /** 绑定未过期的码；过期后清掉二维码，标题和按钮仍由 codeActive 决定。 */
    private fun bindCode(state: VehicleAccountState) {
        if (!state.codeVisible) {
            hideCode()
            return
        }
        val context = binding.root.context
        binding.codeValue.visibility = View.VISIBLE
        binding.codeValue.text = state.code
        binding.codeExpiry.visibility = View.VISIBLE
        binding.codeExpiry.text =
            context.getString(
                R.string.vehicle_account_code_until,
                expiryFormat.format(Date(state.expiresAt)),
            )
        binding.codeError.visibility = View.GONE
        if (state.scanUrl.isBlank()) {
            binding.codeQr.visibility = View.GONE
            qr.clear(binding.codeQr)
            binding.codeError.visibility = View.VISIBLE
            return
        }
        binding.codeQr.visibility = View.VISIBLE
        qr.submit(
            scanUrl = state.scanUrl,
            image = binding.codeQr,
            accept = { codeStillCurrent(state.scanUrl) },
            onFailure = {
                if (!closed && codeStillCurrent(state.scanUrl)) {
                    binding.codeQr.visibility = View.GONE
                    binding.codeError.visibility = View.VISIBLE
                }
            },
        )
    }

    /** 隐藏二维码、文本码和编码失败提示，并让渲染器放弃尚未显示的结果。 */
    private fun hideCode() {
        binding.codeQr.visibility = View.GONE
        binding.codeValue.visibility = View.GONE
        binding.codeExpiry.visibility = View.GONE
        binding.codeError.visibility = View.GONE
        qr.clear(binding.codeQr)
    }

    /**
     * 完成回调时是否仍能显示这个地址。
     *
     * `codeVisible` 来自上次解析，不能单独代表此刻仍有效。必须再用注入时钟比较 `expiresAt`，
     * 到期时刻本身也算失效。不因此发请求，也不显示编码失败占位。
     */
    private fun codeStillCurrent(scanUrl: String): Boolean {
        val current = shown
        return !closed &&
            current != null &&
            !current.bound &&
            current.codeVisible &&
            current.expiresAt > clock() &&
            current.scanUrl == scanUrl
    }

    /** 按连接、busy 和关闭状态刷新按钮与底部提示。返回键保持可用。 */
    private fun applyChrome() {
        if (closed) {
            return
        }
        val enabled = actionsEnabled()
        binding.unbindButton.isEnabled = enabled
        binding.requestCodeButton.isEnabled = enabled
        binding.onlineButton.isEnabled = enabled
        binding.backButton.isEnabled = true
        adapter.setActionsEnabled(enabled)
        binding.statusHint.text = currentHint()
    }

    /** 会提交动作的按钮仅在已连接且空闲时可用。 */
    private fun actionsEnabled(): Boolean = connected && !busy && !closed

    /**
     * 选择底部提示。
     *
     * 断线优先于业务提示。state 轮询不盖住正常说明；其他动作进行中才显示正在处理。
     */
    private fun currentHint(): String {
        val context = binding.root.context
        if (disconnected) {
            return context.getString(R.string.vehicle_account_hint_disconnected)
        }
        if (processing) {
            return context.getString(R.string.vehicle_account_hint_processing)
        }
        val state = shown ?: return context.getString(R.string.vehicle_account_hint_connecting)
        return when (val hint = state.hint(replyError)) {
            is VehicleAccountState.Hint.Reply -> hint.text
            is VehicleAccountState.Hint.Json -> hint.text
            VehicleAccountState.Hint.Enabled -> context.getString(R.string.vehicle_account_hint_enabled)
            VehicleAccountState.Hint.Disabled -> context.getString(R.string.vehicle_account_hint_disabled)
        }
    }

    /**
     * 弹出取消绑定确认框。
     *
     * 只有正向按钮发送 unbind。保留、返回和关闭都不请求。页面销毁时关闭这个弹窗。
     */
    private fun confirmUnbind() {
        if (closed || !connected || busy || unbindDialog?.isShowing == true) {
            return
        }
        unbindDialog =
            GalaxyDialogs
                .builder(binding.root.context)
                .setTitle(R.string.vehicle_account_unbind_title)
                .setMessage(R.string.vehicle_account_unbind_message)
                .setNegativeButton(R.string.vehicle_account_unbind_keep, null)
                .setPositiveButton(R.string.vehicle_account_unbind_confirm) { _, _ ->
                    request(ACTION_UNBIND, Bundle())
                }.show()
    }

    /** 连接层已经报告的失败或丢失。其他投影状态不当成账号断线。 */
    private fun connectionLost(value: String): Boolean =
        value.startsWith("ipc:") ||
            value.startsWith("connect:") ||
            value.startsWith("bind:") ||
            value == "service_disconnected" ||
            value == "binding_rejected" ||
            value == "binding_died" ||
            value == "service_unavailable"

    /** 保证 JSON 和按钮状态只在主线程改。已经在主线程时直接执行。 */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            main.post(block)
        }
    }

    private companion object {
        const val ACTION_STATE = "state"
        const val ACTION_CODE = "code"
        const val ACTION_CONFIRM = "confirm"
        const val ACTION_UNBIND = "unbind"
        const val ACTION_ENABLE = "enable"
        const val ACTION_DISABLE = "disable"
        const val KEY_REFRESH = "refresh"
        const val KEY_BINDING_ID = "bindingId"
        const val KEY_ACCEPTED = "accepted"
        const val TICK_MS = 2_000L
    }
}
