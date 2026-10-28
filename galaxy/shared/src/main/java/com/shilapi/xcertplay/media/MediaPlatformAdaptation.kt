package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioRouting
import android.media.AudioManager
import android.media.AudioTrack
import java.io.Closeable

/** 核心拥有播放/录音对象，适配仅提供属性、输入源和可关闭的路由观察。 */
interface PlatformAudioProfile {
    fun attributes(channel: AudioChannel, contentType: Int, choice: Int): AudioAttributes
    fun microphoneSource(audioType: String, sampleRate: Int, wireless: Boolean): Int?
}

interface AudioRouteBinding : Closeable {
    fun reportActual()
}

interface AudioRouteProvider : Closeable {
    fun bind(routing: AudioRouting, channel: AudioChannel, input: Boolean,
             sampleRate: Int, channels: Int, useBus: Boolean = true): AudioRouteBinding
}

/** 渲染器持有音轨，策略持有单次会话焦点；不得由产品实现接管codec或worker。 */
interface AudioFocusController : Closeable {
    val listener: AudioManager.OnAudioFocusChangeListener
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes)
    fun release(track: AudioTrack)
    fun onExternalFocusChange(change: Int)
    fun resumeMedia()
    fun communicationStarted()
    fun communicationEnded()
}

interface MediaPlatformAdaptation {
    val profile: PlatformAudioProfile? get() = null
    val template: AudioRoutingTemplate? get() = null
    fun audioRoutes(context: Context?, report: (String) -> Unit): AudioRouteProvider? = null
    fun audioFocus(context: Context?, enabled: Boolean, report: (String) -> Unit): AudioFocusController? = null

    companion object { val NONE = object : MediaPlatformAdaptation {} }
}

/** 每次会话固定模板，核心默认不读取车型文件、不写入首选设备。 */
class GalaxyMediaPolicy(
    private val factoryProfile: Boolean,
    private val preferBus: Boolean,
    override val template: AudioRoutingTemplate? = null,
    private val navigationDevice: NavigationOutputDevice? = null,
) : MediaPlatformAdaptation {
    override val profile: PlatformAudioProfile? = if (factoryProfile) L7FactoryAudioProfile.load() else null
    override fun audioFocus(context: Context?, enabled: Boolean, report: (String) -> Unit): AudioFocusController? =
        if (factoryProfile) GalaxyAudioFocusCoordinator(context, enabled, report, true, template) else null
    override fun audioRoutes(context: Context?, report: (String) -> Unit): AudioRouteProvider? =
        context?.let { L7AudioRouting(it, template?.preferBus ?: preferBus, template, navigationDevice, report) }
}
