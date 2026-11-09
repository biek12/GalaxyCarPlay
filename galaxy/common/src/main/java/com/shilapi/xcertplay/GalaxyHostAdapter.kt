package com.shilapi.xcertplay

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.AudioOutputRole
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.media.GalaxyMediaPolicy
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import kotlin.math.roundToInt

/** 唯一公开安装入口供Application调用，不向mobile暴露内部宿主接口。 */
object GalaxyHostAdapter {
    fun install() {
        HostProductBindings.mediaDiagnostics=GalaxyMediaKeys::diagnostics
        HostProductBindings.audioDiagnostics = {
            val snapshot = CarPlayBackgroundSession.snapshot()
            org.json.JSONObject().apply {
                put("schema", 1); put("connected", snapshot != null)
                snapshot?.let { put("input", org.json.JSONObject(it.sink.audioInputDiagnostics())) }
            }.toString()
        }
        HostProductBindings.navigationDiagnostics = {
            val snapshot = CarPlayBackgroundSession.snapshot()
            org.json.JSONObject().apply {
                put("schema", 1)
                put("connected", snapshot != null)
                put("androidDisplays", org.json.JSONArray(GalaxyMediaKeys.displayDiagnostics()))
                snapshot?.let {
                    put("request", org.json.JSONObject(it.controller.displayDiagnostics()))
                    put("outputs", org.json.JSONObject(it.sink.videoOutputDiagnostics()))
                }
            }.toString()
        }
        // Application显式安装即选择本产品；核心无注册时仍用上游默认，不再叠加隐式资源门控。
        HostProductBindings.factory = { activity, actions -> GalaxyProductHostAdapter(activity, actions) }
    }
}

/** Galaxy宿主装配集中在产品目录；核心只调用中性接口，不读取车型文件或厂商服务。 */
private class GalaxyProductHostAdapter(
    private val activity: ComponentActivity,
    private val actions: HostProductActions,
) : CarPlayHostProduct {
    private val chrome = GalaxyHostChrome(activity)
    private val geometry = GalaxyDisplayTrace(actions.report)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var platform: GalaxyAutoServiceBinding? = null
    private var platformSurface: ((android.view.Surface?) -> Unit)? = null
    private var platformSession: CarPlayController? = null
    private var platformActive = false
    private val mapPreferences = activity.getSharedPreferences("cluster-map-stream", 0)
    private var mapSize = runCatching { com.galaxy.auto.contract.MapStreamSize(
        mapPreferences.getInt("width", com.galaxy.auto.contract.AutoContract.WIDTH),
        mapPreferences.getInt("height", com.galaxy.auto.contract.AutoContract.MAP_HEIGHT))
    }.getOrDefault(com.galaxy.auto.contract.MapStreamSize.DEFAULT)
    private var configuredMapSize = mapSize
    private var sessionMapSize = mapSize
    private var mapController: CarPlayController? = null
    private var laneListener: ((List<com.shilapi.xcertplay.iap2.message.LaneGuidanceLane>)->Unit)? = null
    private var laneController: CarPlayController? = null
    private fun mapSizeChanged(next: com.galaxy.auto.contract.MapStreamSize) {
        if (next != mapSize) {
            mapSize = next
            mapPreferences.edit().putInt("width", next.width).putInt("height", next.height).apply()
        }
        if (mapSize != sessionMapSize && actions.currentController()?.hasActiveSession() == true)
            actions.clusterDisplayChanged()
    }
    private fun platformCluster() = GalaxyVehicleAudioProfiles.model(activity) != GalaxyVehicleAudioProfiles.Model.CUSTOM
    override fun clusterDisplayConfiguration(): AirPlayDisplayConfig? {
        if (!platformCluster()) return null
        configuredMapSize = mapSize
        return AirPlayDisplayConfig(
        widthPixels = mapSize.width,
        heightPixels = mapSize.height,
        fps = 30, primaryInputDevice = 0, features = 0,
        initialUrl = com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.MAP_URL,
        safeArea = com.shilapi.xcertplay.airplay.AirPlayInsets(),
        safeAreaDrawOutside = true)
    }
    override fun keepsClusterOutputWhileMapIdle() = platformCluster()
    override fun ensureClusterOutput(surfaceChanged: (android.view.Surface?) -> Unit): Boolean {
        if (!platformCluster()) return false
        platformSurface = surfaceChanged
        if (platform == null) platform = GalaxyAutoServiceBinding(activity, surfaceChanged, actions.report, command = { index ->
            val current = actions.currentController()
            if (current != null && current.hasActiveSession()) {
                val key = when (index) {
                    com.shilapi.xcertplay.airplay.CarPlayMediaButton.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    com.shilapi.xcertplay.airplay.CarPlayMediaButton.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
                GalaxyMediaKeys.onHardwareKey(current, KeyEvent(KeyEvent.ACTION_DOWN, key))
                GalaxyMediaKeys.onHardwareKey(current, KeyEvent(KeyEvent.ACTION_UP, key))
            }
        }, mapSizeChanged = ::mapSizeChanged).also { it.active(platformActive) }
        return true
    }
    override fun closeClusterOutput() { platform?.close(); platform = null }
    override fun clusterStreamChanged(active: Boolean) = Unit
    override fun clusterGuidanceChanged(guidance: com.shilapi.xcertplay.hud.ClusterTurnGuidance?) {
        platform?.guidance(guidance)
    }
    override val name = "CarPlay"

    override fun enterHost() = L7Agreement.require(activity) && L7StartupGuard.enterHost(activity)
    override fun homeIntent(page: String) = Intent(activity, GalaxySettingsActivity::class.java).putExtra("page", page)
    override fun physicalSize(width: Int, height: Int) = L7DisplayGeometry.forViewport(activity, width, height)
    override fun uiScalePercent() = GalaxyProjectionScale.sanitize(activity
        .getSharedPreferences("xcertplay_airplay", 0).getInt("ui_scale_percent", 100))
    override fun oemLabel() = GalaxyCarPlayAppearance.label(activity)
    override fun icon() = GalaxyCarPlayAppearance.icon(activity)
    override fun scaleIcons(display: AirPlayDisplayConfig, percent: Int) = GalaxyProjectionScale.icons(display, percent)
    override fun connectionStage(message: String, englishMessage: String) = message
    override fun wifiP2pAllowedChannels(): List<Int> = GalaxyP2pSettings.load(activity).policy.channels
    override fun preferredAvcDecoder(): String? = GalaxyVideoDecoderPreferences.load(activity).decoder
    // 车速/挡位来自本车平台的按需订阅（GalaxyVehicleSpeed），不由“本机装了ECARX SDK”推导。
    // 每次调用都新建来源实例：核心的 VehicleSpeedLocationProvider 负责它这一次连接的 start/stop。
    override fun vehicleSpeedToIphone() = GalaxyVehicleOutputSettings.vehicleSpeedToIphone(activity)
    override fun vehicleSpeedSource(): VehicleSpeedSource? =
        if (GalaxyVehicleOutputSettings.vehicleSpeedToIphone(activity))
            GalaxyVehicleSpeed(activity.applicationContext, log = actions.report) else null

    override fun audioConfiguration(): HostAudioConfiguration {
        val template = GalaxyVehicleAudioProfiles.load(activity)
        return HostAudioConfiguration(
            platform = GalaxyMediaPolicy(factoryProfile = true, preferBus = template.preferBus,
                template = template, navigationDevice = GalaxyNavigationOutput.load(activity)),
            advancedMapping = true,
            focusEnabled = GalaxyPreferencePolicy.loadAudioFocusEnabled(activity),
            mediaChannel = template.choice(AudioOutputRole.MEDIA),
            navigationChannel = template.choice(AudioOutputRole.NAVIGATION),
            assistantChannel = template.choice(AudioOutputRole.ASSISTANT),
            callProcessingEnabled = GalaxyPreferencePolicy.loadCallProcessingEnabled(activity),
            localMediaAudioEnabled = GalaxyMusicPlayback.localEnabled(activity),
            navigationDucksMedia = GalaxyPreferencePolicy.loadNavigationDucksMedia(activity),
        )
    }

    override fun installChrome(root: FrameLayout, replacing: View, before: View?, wireless: Boolean,
                               retry: () -> Unit): HostChromeBinding {
        root.removeView(replacing)
        val waiting = chrome.waiting(wireless, { actions.home("home") },
            cancel = { actions.cancel(); actions.home("home") },
            recovery = { actions.home("settings-connection") })
        waiting.retryButton.setOnClickListener { retry() }
        root.addView(waiting, root.indexOfChild(before), FrameLayout.LayoutParams(-1, -1))
        val navigation = chrome.navigation(actions.releaseTouches, actions.home)
        root.addView(navigation, FrameLayout.LayoutParams(-1, -1))
        return HostChromeBinding(waiting, waiting.stage, waiting.retryButton, waiting.recoveryButton,
            connected = navigation::setConnected,
            refresh = { navigation.refreshAppearance(); L7Ui.refresh(waiting) },
            showMenu = navigation::expand)
    }

    override fun onHardwareKey(controller: CarPlayController?, event: KeyEvent) =
        GalaxyMediaKeys.onHardwareKey(controller, event)

    override fun bindMedia(sink: AndroidMediaSink, controller: CarPlayController) {
        laneController=controller
        laneListener={ values->main.post{if(actions.currentController()===controller)platform?.lanes(values)} }
        com.shilapi.xcertplay.glance.CarPlayGlance.laneListener=laneListener
        main.post {
            if (actions.currentController() !== controller) return@post
            platformSession = controller
            platformActive = controller.hasActiveSession()
            platformSurface?.let { ensureClusterOutput(it) }
            platform?.active(platformActive)
        }
        GalaxyMediaKeys.attach(activity, controller, sink::resumeMediaAudioFocus, sink::isAssistantAudioActive)
        // 投屏媒体建立即挂上方控；本类回调来自连接链路，GalaxySteeringBinding内部投递到主线程。
        GalaxySteeringBinding.attach(activity, controller)
        val app = activity.applicationContext
        sink.setMediaAudioChangedListener { active ->
            // Sink可能脱离Activity继续后台播放；仅持有Application和本代控制器/音频对象。
            val snapshot = CarPlayBackgroundSession.snapshot()
            if (snapshot?.controller === controller && snapshot.sink === sink) {
                GalaxyMediaKeys.onMediaAudioChanged(controller, active)
                CarPlayBackgroundSession.setBluetoothMediaActive(controller, app, active)
            }
        }
    }

    override fun sessionStateChanged(controller: CarPlayController?, active: Boolean) {
        controller ?: return
        main.post {
            if (actions.currentController() !== controller) return@post
            platformSession = controller
            platformActive = active
            platform?.active(active)
            if(!active)platform?.lanes(emptyList())
            if (active) mapSizeChanged(mapSize)
        }
        GalaxyMediaKeys.onSessionStateChanged(controller, active)
        if (active) {
            GalaxySteeringBinding.attach(activity, controller)
            L7StartupGuard.connected(activity.applicationContext) {
                actions.currentController() === controller && controller.hasActiveSession()
            }
        } else GalaxySteeringBinding.onSessionEnded(controller)
    }

    override fun sessionStored(controller: CarPlayController) {
        if (mapController !== controller) {
            mapController = controller
            sessionMapSize = configuredMapSize
        }
        CarPlayBackgroundSession.setConfiguration(controller, GalaxyProfiles(activity).refresh())
        // 媒体命令要经 CarPlayBackgroundSession 的蓝牙等待闸门，因此在 store 之后再确认一次挂接；
        // 与 bindMedia 的同控制器重复调用是空操作。
        GalaxySteeringBinding.attach(activity, controller)
    }

    override fun detachMedia(controller: CarPlayController?) {
        if((controller==null||laneController===controller)&&com.shilapi.xcertplay.glance.CarPlayGlance.laneListener===laneListener)
            com.shilapi.xcertplay.glance.CarPlayGlance.laneListener=null
        main.post {
            if (controller != null && platformSession !== controller) return@post
            platformSession = null
            platformActive = false
            closeClusterOutput()
        }
        GalaxyMediaKeys.detach(controller)
        GalaxySteeringBinding.detach(controller)
    }

    override fun observeVideoLayout(generation: Int, viewWidth: Int, viewHeight: Int,
                                    canvasWidth: Int, canvasHeight: Int, layout: CarPlayVideoLayout,
                                    softwareDecoder: Boolean) {
        geometry.observe(generation, viewWidth, viewHeight, canvasWidth, canvasHeight,
            layout.left.roundToInt(), layout.top.roundToInt(), layout.width.roundToInt(), layout.height.roundToInt(),
            hardware = !softwareDecoder)
    }

}
