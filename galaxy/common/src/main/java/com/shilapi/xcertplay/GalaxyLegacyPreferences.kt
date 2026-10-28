package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.network.NativeHotspotCredentials
import com.shilapi.xcertplay.orchestration.MfiTarget

/** 旧产品调用的兼容扩展仅在Galaxy源集编译，不向上游偏好对象堆产品方法。 */
internal fun AirPlayPersistence.loadL7AudioBusEnabled(context: Context) = GalaxyPreferencePolicy.loadL7AudioBusEnabled(context)
internal fun AirPlayPersistence.saveL7AudioBusEnabled(context: Context, enabled: Boolean) = GalaxyPreferencePolicy.saveL7AudioBusEnabled(context, enabled)
internal fun AirPlayPersistence.restoreUsageAudioDefaults(context: Context) = GalaxyPreferencePolicy.restoreUsageAudioDefaults(context)
internal fun AirPlayPersistence.loadCallProcessingEnabled(context: Context) = GalaxyPreferencePolicy.loadCallProcessingEnabled(context)
internal fun AirPlayPersistence.saveCallProcessingEnabled(context: Context, enabled: Boolean) = GalaxyPreferencePolicy.saveCallProcessingEnabled(context, enabled)
internal fun AirPlayPersistence.loadBluetoothMediaExclusive(context: Context) = GalaxyPreferencePolicy.loadBluetoothMediaExclusive(context)
internal fun AirPlayPersistence.saveBluetoothMediaExclusive(context: Context, enabled: Boolean) = GalaxyPreferencePolicy.saveBluetoothMediaExclusive(context, enabled)
internal fun AirPlayPersistence.loadAssistantAudioChannel(context: Context) = GalaxyPreferencePolicy.loadAssistantAudioChannel(context)
internal fun AirPlayPersistence.saveAssistantAudioChannel(context: Context, channel: Int) = GalaxyPreferencePolicy.saveAssistantAudioChannel(context, channel)
internal fun AirPlayPersistence.saveMfiConfiguration(context: Context, target: MfiTarget, server: String? = null, token: String? = null) =
    GalaxyPreferencePolicy.saveMfiConfiguration(context, target, server, token)
internal fun AirPlayPersistence.saveNativeHotspotCredentials(context: Context, value: NativeHotspotCredentials) =
    GalaxyPreferencePolicy.saveNativeHotspotCredentials(context, value)
internal fun AirPlayPersistence.loadMediaBufferMillis(context: Context): Int = loadMediaBufferMillis(context, loadWirelessEnabled(context))
internal fun AirPlayPersistence.saveMediaBufferMillis(context: Context, millis: Int) = saveMediaBufferMillis(context, loadWirelessEnabled(context), millis)
