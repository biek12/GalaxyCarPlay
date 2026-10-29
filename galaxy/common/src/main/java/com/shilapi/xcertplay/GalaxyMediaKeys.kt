package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.steering.GalaxySteeringMediaWindow

/** 唯一产品媒体绑定；焦点归Sink，媒体注册/封面/回调随真实手机会话代次释放。 */
internal object GalaxyMediaKeys {
    private val handler = Handler(Looper.getMainLooper())
    private var controller: CarPlayController? = null
    private var context: Context? = null
    private var attachment = 0L
    private var owner: Any? = null
    private var onPlaybackStarted: () -> Unit = {}
    private var assistantActive: () -> Boolean = { false }
    private var bridge: CarPlayMediaSession? = null
    private var mediaCenter: L7MediaCenterSession? = null
    private var covers: L7MediaArtwork? = null
    private var navigation: L7NavigationSession? = null
    private val navigationInput = com.shilapi.xcertplay.hud.CarPlayNavigationInput(L7DebugLog::record)
    private var navigationTick: Runnable? = null
    private var steeringWheel: L7SteeringWheel? = null
    private var commandGate = L7MediaCommandGate()
    private var mediaInfo = CarPlayNowPlaying()

    @Synchronized fun attach(context: Context, next: CarPlayController, onPlaybackStarted: () -> Unit,
                             assistantActive: () -> Boolean = { false }) {
        if (controller === next) {
            this.onPlaybackStarted = onPlaybackStarted
            this.assistantActive = assistantActive
            refresh(next)
            return
        }
        clearAttachment()
        this.context = context.applicationContext
        this.controller = next
        this.onPlaybackStarted = onPlaybackStarted
        this.assistantActive = assistantActive
        val ticket = attachment
        next.nowPlayingListener = { _ -> deliver(next, ticket) { refresh(next) } }
        next.playbackListener = { _ -> deliver(next, ticket) { refresh(next) } }
        next.navigationFrameListener = { frame ->
            val expectedOwner = next.activeMediaSessionOwner()
            deliver(next, ticket) {
                refresh(next)
                if (expectedOwner != null && owner === expectedOwner) navigationInput.accept(frame)?.let { navigation?.update(it) }
            }
        }
        next.artworkListener = { id, bytes ->
            val expectedOwner = next.activeMediaSessionOwner()
            deliver(next, ticket) {
                refresh(next)
                // 旧会话排队中的封面不能以复用的transferId污染新手机。
                if (expectedOwner != null && owner === expectedOwner) {
                    bridge?.onArtworkChanged(id, bytes)
                    covers?.submit(id, bytes)
                }
            }
        }
        refresh(next)
    }

    /** 官方Host的active/ended回调仅触发重读；迟到的false不能关闭同控制器的新会话。 */
    @Synchronized fun onSessionStateChanged(expected: CarPlayController, active: Boolean) {
        if (controller !== expected) return
        if (!active && expected.hasActiveSession()) return
        refresh(expected)
    }

    private fun deliver(expected: CarPlayController, ticket: Long, action: () -> Unit) {
        handler.post { synchronized(this) {
            if (controller === expected && attachment == ticket) action()
        } }
    }

    private fun refresh(next: CarPlayController) {
        if (controller !== next) return
        val app = context ?: return
        val currentOwner = next.activeMediaSessionOwner()
        if (owner !== currentOwner) {
            releaseSession()
            owner = currentOwner
            if (currentOwner != null) openSession(app, next, currentOwner)
        }
        if (currentOwner == null) return
        // 回调可来自任意链路线程，交付时读最新快照，不把排队旧增量倒灌。
        val update = next.nowPlayingSnapshot()
        mediaInfo = update
        bridge?.onNowPlayingChanged(update)
        if (update.playbackKnown) L7SteeringDiagnostics.store.state("phonePlayback", "playing=${update.playing}")
        if (update == CarPlayNowPlaying()) covers?.reset()
        covers?.select(update.artworkTransferId) ?: mediaCenter?.update(update, null)
    }

    private fun openSession(app: Context, next: CarPlayController, expectedOwner: Any) {
        commandGate = L7MediaCommandGate()
        val commands = dispatcher(next)
        val traced: (Int, String, L7SteeringTrace) -> Unit = { index, source, trace ->
            if (GalaxyVehiclePreferences.steering(app)) commands.dispatch(index, source, trace)
            else trace.step("DROP", "VEHICLE_CONTROL_DISABLED")
        }
        val dispatch: (Int, String) -> Unit = { index, source -> traced(index, source, L7SteeringDiagnostics.begin(source, index)) }
        if (next.localMediaAudioEnabled) {
            bridge = CarPlayMediaSession(app, { onPlaybackStarted() }, dispatch, traced).also { it.onConnected(true) }
        }
        L7SteeringDiagnostics.store.connection(true)
        if (GalaxyVehiclePreferences.mediaReporting(app)) startMediaReporting(app, next, expectedOwner, dispatch, traced)
        if (GalaxyVehiclePreferences.navigationReporting(app)) startNavigation(app, next, expectedOwner)
        // 旧Galaxy的语音广播属于L7；L6监听学习由Autolink链接入，不能套用该广播。
        if (GalaxyVehiclePreferences.steering(app) && GalaxyVehicleAudioProfiles.model(app) == GalaxyVehicleAudioProfiles.Model.L7) {
            steeringWheel = L7SteeringWheel(app, next::hasActiveSession, { assistantActive() }) {
                synchronized(this) { controller === next && owner === expectedOwner && next.requestSiri() }
            }.also { it.start() }
        }
        val retained = next.nowPlayingSnapshot()
        mediaInfo = retained
        bridge?.onNowPlayingChanged(retained)
        next.matchingArtworkSnapshot(retained.artworkTransferId)?.let {
            bridge?.onArtworkChanged(it.id, it.bytes)
            covers?.submit(it.id, it.bytes)
        }
    }

    private fun dispatcher(next: CarPlayController) = L7MediaCommandDispatcher(
        next::activeMediaSessionOwner,
        { synchronized(this) { controller === next } },
        // 方控HardKey与原车媒体中心可能回送同一次按键；共享M05的250ms跨源窗口后再走原有入口闸门。
        { index, source -> synchronized(this) {
            GalaxySteeringMediaWindow.acceptVehicleMedia(index, SystemClock::elapsedRealtime) {
                commandGate.accept(index, source)
            }
        } },
        { index, action -> CarPlayBackgroundSession.beforeMediaCommand(next, index, action) },
        CarPlayVideo::onMediaKey,
        { index, expected -> next.sendMediaButton(index, expected) },
        { onPlaybackStarted() },
        traceSend = { index, expected, trace -> next.sendMediaButton(index, expected, trace::step) },
        traceBefore = { index, action, dropped -> CarPlayBackgroundSession.beforeMediaCommand(next, index, action, dropped) },
    )

    private fun startMediaReporting(app: Context, next: CarPlayController, expectedOwner: Any,
                                   dispatch: (Int, String) -> Unit, traced: (Int, String, L7SteeringTrace) -> Unit) {
        fun current() = synchronized(this) {
            controller === next && owner === expectedOwner && next.activeMediaSessionOwner() === expectedOwner
        }
        lateinit var coverOwner: L7MediaArtwork
        coverOwner = L7MediaArtwork(app) { _ -> synchronized(this) {
            if (current() && covers === coverOwner)
                mediaCenter?.update(mediaInfo, coverOwner.selectedUri(mediaInfo.artworkTransferId))
        } }
        covers = coverOwner
        mediaCenter = L7MediaCenterSession(
            L7ReflectiveMediaCenter(app, next.localMediaAudioEnabled), app.packageName,
            ::current, dispatch, traceSend = traced, localPlayback = next.localMediaAudioEnabled,
        ).also { it.start(); it.update(next.nowPlayingSnapshot(), null) }
    }

    private fun startNavigation(app: Context, next: CarPlayController, expectedOwner: Any) {
        navigation = L7NavigationSession(L7ReflectiveNavigation(app), {
            synchronized(this) { controller === next && owner === expectedOwner && next.activeMediaSessionOwner() === expectedOwner }
        })
        navigationTick = object : Runnable {
            override fun run() = synchronized(this@GalaxyMediaKeys) {
                if (controller === next && owner === expectedOwner && next.activeMediaSessionOwner() === expectedOwner && navigationTick === this) {
                    navigation?.update(navigationInput.snapshot())
                    handler.postDelayed(this, 1000)
                }
            }
        }.also { handler.post(it) }
    }

    private fun releaseSession() {
        navigationTick?.let(handler::removeCallbacks)
        navigationTick = null
        navigation?.close(); navigation = null
        navigationInput.clear()
        mediaCenter?.close(); mediaCenter = null
        covers?.close(); covers = null
        bridge?.close(); bridge = null
        steeringWheel?.close(); steeringWheel = null
        commandGate = L7MediaCommandGate()
        mediaInfo = CarPlayNowPlaying()
        owner = null
        L7SteeringDiagnostics.store.connection(false)
    }

    private fun clearAttachment() {
        attachment++
        controller?.playbackListener = null
        controller?.nowPlayingListener = null
        controller?.artworkListener = null
        controller?.navigationFrameListener = null
        releaseSession()
        controller = null
        context = null
    }

    @Synchronized fun detach(expected: CarPlayController?) {
        if (expected != null && controller === expected) clearAttachment()
    }

    @Synchronized fun onMediaAudioChanged(expected: CarPlayController, active: Boolean) {
        if (controller !== expected) return
        refresh(expected)
        L7SteeringDiagnostics.store.state("audioStream", "active=$active")
        bridge?.onMediaAudioChanged(active)
    }

    @Synchronized fun onHardwareKey(expected: CarPlayController?, event: KeyEvent): Boolean {
        VehicleSteeringInputLog.key(event, "vehicle-window-key")
        if (expected == null || controller !== expected || !expected.hasActiveSession()) return false
        refresh(expected)
        return bridge?.onHardwareKey(event) == true
    }

    @Synchronized fun onVoiceKey(expected: CarPlayController?): Boolean =
        expected != null && controller === expected && steeringWheel?.onVoiceKey() == true
}

/** 系统媒体控制用明确播放/暂停，硬件键保持上游切换语义；长按重复事件不转发。 */
internal class GalaxyMediaCallback(
    private val explicitHardwareActions: Boolean = false,
    private val tracedSend: ((Int, String, L7SteeringTrace) -> Unit)? = null,
    private val observeKey: ((KeyEvent, String) -> Unit)? = null,
    private val send: (index: Int, source: String) -> Unit,
) : MediaSession.Callback() {
    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        return onKey(event, "media-session-key")
    }

    fun onKey(event: KeyEvent, source: String): Boolean {
        observeKey?.invoke(event, "vehicle-$source")
        val index = if (explicitHardwareActions && event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) CarPlayMediaButton.PLAY
            else if (explicitHardwareActions && event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) CarPlayMediaButton.PAUSE
            else CarPlayMediaButton.forKeyCode(event.keyCode) ?: return false
        val origin = "$source:${KeyEvent.keyCodeToString(event.keyCode)}"
        val trace = tracedSend?.let { L7SteeringDiagnostics.begin(origin, index,
            "code=${event.keyCode} action=${event.action} repeat=${event.repeatCount}") }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (trace != null) tracedSend?.invoke(index, origin, trace) else send(index, origin)
        } else trace?.step("FILTER", if (event.repeatCount != 0) "REPEAT" else "KEY_UP_OR_OTHER_ACTION")
        return true
    }

    private fun command(index: Int, source: String) {
        if (tracedSend != null) tracedSend.invoke(index, source, L7SteeringDiagnostics.begin(source, index)) else send(index, source)
    }
    override fun onPlay() = command(CarPlayMediaButton.PLAY, "controller-play")
    override fun onPause() = command(CarPlayMediaButton.PAUSE, "controller-pause")
    override fun onSkipToNext() = command(CarPlayMediaButton.NEXT, "next")
    override fun onSkipToPrevious() = command(CarPlayMediaButton.PREVIOUS, "previous")
}
