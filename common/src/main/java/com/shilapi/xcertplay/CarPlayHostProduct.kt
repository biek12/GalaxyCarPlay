package com.shilapi.xcertplay

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.media.MediaPlatformAdaptation
import com.shilapi.xcertplay.orchestration.CarPlayController

/** 宿主只提供现有会话操作；产品不能取得Surface或解码worker的所有权。 */
internal data class HostProductActions(
    val home: (String) -> Unit,
    val cancel: () -> Unit,
    val releaseTouches: () -> Unit,
    val currentController: () -> CarPlayController?,
    val report: (String) -> Unit,
)

/** 每次建立Sink时固定的音频参数，未注入时完全沿用官方宿主参数。 */
internal data class HostAudioConfiguration(
    val platform: MediaPlatformAdaptation,
    val advancedMapping: Boolean,
    val focusEnabled: Boolean,
    val mediaChannel: Int,
    val navigationChannel: Int,
    val assistantChannel: Int,
    val callProcessingEnabled: Boolean,
    val localMediaAudioEnabled: Boolean,
)

/** 替换连接控件，不修改视频布局；所有回调跟随Activity持有的这一份绑定。 */
internal data class HostChromeBinding(
    val waiting: View,
    val stage: TextView,
    val retry: View,
    val recovery: View,
    val connected: (Boolean) -> Unit,
    val refresh: () -> Unit,
    val showMenu: () -> Unit,
)

/** 通用产品接点；品牌、车型、偏好、媒体注册和UI实现不进入上游宿主。 */
internal interface CarPlayHostProduct {
    val name: String
    fun enterHost(): Boolean
    fun homeIntent(page: String): Intent
    fun uiScalePercent(): Int
    fun oemLabel(): String
    fun icon(): AirPlayIcon
    fun scaleIcons(display: AirPlayDisplayConfig, percent: Int): AirPlayDisplayConfig
    fun audioConfiguration(): HostAudioConfiguration
    /** 一次建链读取并冻结；无产品注入时由核心使用原有信道计划。 */
    fun wifiP2pAllowedChannels(): List<Int>? = null
    /** 可选解码器名称只影响本次Sink；核心负责能力校验和资源生命周期。 */
    fun preferredAvcDecoder(): String? = null
    /** 产品拥有屏幕外合成输出；核心仅绑定其返回的111解码Surface。 */
    fun clusterDisplayConfiguration(): AirPlayDisplayConfig? = null
    fun ensureClusterOutput(surfaceChanged: (android.view.Surface?) -> Unit): Boolean = false
    fun closeClusterOutput() = Unit
    fun clusterStreamChanged(active: Boolean) = Unit
    fun clusterGuidanceChanged(guidance: com.shilapi.xcertplay.hud.ClusterTurnGuidance?) = Unit
    fun keepsClusterOutputWhileMapIdle(): Boolean = false
    fun installChrome(root: FrameLayout, replacing: View, before: View?, wireless: Boolean,
                      retry: () -> Unit): HostChromeBinding
    fun onHardwareKey(controller: CarPlayController?, event: KeyEvent): Boolean
    fun bindMedia(sink: AndroidMediaSink, controller: CarPlayController)
    fun sessionStateChanged(controller: CarPlayController?, active: Boolean)
    fun sessionStored(controller: CarPlayController)
    fun detachMedia(controller: CarPlayController?)
    fun connectionStage(message: String, englishMessage: String): String
    fun observeVideoLayout(generation: Int, viewWidth: Int, viewHeight: Int,
                           canvasWidth: Int, canvasHeight: Int, layout: CarPlayVideoLayout,
                           softwareDecoder: Boolean)
}

/** Application显式注册；普通源码/测试宿主没有产品实现时仍执行官方默认行为。 */
internal object HostProductBindings {
    @Volatile var factory: ((ComponentActivity, HostProductActions) -> CarPlayHostProduct?)? = null
}
