package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 核心默认焦点与产品委托分别验证，避免迁移时把Galaxy默认强加到无适配宿主。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GalaxyAudioIntegrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val attributes get() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()

    @Test fun defaultCoordinatorStillIgnoresNavigationWithoutProductInjection() {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val coordinator = AudioFocusCoordinator(context, true)
        val track = track()
        try {
            coordinator.acquire(track, AudioChannel.NAVIGATION, attributes)
            assertNull(shadowOf(manager).lastAudioFocusRequest)
        } finally {
            coordinator.close()
            track.release()
        }
    }

    @Test fun explicitFocusControllerOwnsItsCallbacksAndReceivesLifecycleInOrder() {
        val events = mutableListOf<String>()
        val strategy = object : AudioFocusController {
            override val listener = AudioManager.OnAudioFocusChangeListener { events += "callback:$it" }
            override fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) { events += "acquire:$channel" }
            override fun release(track: AudioTrack) { events += "release" }
            override fun onExternalFocusChange(change: Int) { listener.onAudioFocusChange(change) }
            override fun resumeMedia() { events += "resume" }
            override fun communicationStarted() { events += "call-start" }
            override fun communicationEnded() { events += "call-end" }
            override fun close() { events += "close" }
        }
        val coordinator = AudioFocusCoordinator(context, true, adaptation = strategy)
        val track = track()
        try {
            assertSame(strategy.listener, coordinator.listener)
            coordinator.acquire(track, AudioChannel.NAVIGATION, attributes)
            coordinator.communicationStarted()
            coordinator.communicationEnded()
            coordinator.resumeMedia()
            coordinator.onExternalFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            coordinator.release(track)
            coordinator.close()
            assertEquals(listOf("acquire:NAVIGATION", "call-start", "call-end", "resume", "callback:-2", "release", "close"), events)
        } finally { track.release() }
    }

    @Test fun sinkReadOnlyAudioStateDoesNotClaimAnInactiveOrClosedMicrophone() {
        val sink = AndroidMediaSink(context = context)
        assertFalse(sink.hasMicrophoneUplink())
        assertFalse(sink.isAssistantAudioActive())
        sink.close()
        assertFalse(sink.hasMicrophoneUplink())
        assertFalse(sink.isAssistantAudioActive())
    }

    @Test fun noAdaptationDoesNotCreateRoutingOrFactorySourcePolicy() {
        val adaptation = MediaPlatformAdaptation.NONE
        assertNull(adaptation.profile)
        assertNull(adaptation.template)
        assertNull(adaptation.audioRoutes(context) {})
        assertNull(adaptation.audioFocus(context, true) {})
    }

    private fun track(): AudioTrack = AudioTrack.Builder().setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setBufferSizeInBytes(4096).build()
}
