package com.shilapi.xcertplay

import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GalaxyMediaBindingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<CarPlayController>()

    @Before fun disableExternalServices() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putBoolean("galaxy_media_reporting_enabled", false)
            .putBoolean("galaxy_navigation_reporting_enabled", false)
            .putBoolean("galaxy_steering_enabled", false).commit()
    }
    @After fun close() {
        controllers.forEach(GalaxyMediaKeys::detach)
        shadowOf(Looper.getMainLooper()).idle()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }
    private fun controller(owner: Any) = mock(CarPlayController::class.java).also {
        controllers += it
        `when`(it.activeMediaSessionOwner()).thenReturn(owner)
        `when`(it.hasActiveSession()).thenReturn(true)
        `when`(it.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(it.localMediaAudioEnabled).thenReturn(true)
    }
    private fun mediaInfo(): CarPlayNowPlaying = ReflectionHelpers.getField(GalaxyMediaKeys, "mediaInfo")

    @Test fun attachReplaysRetainedMetadataWithoutWaitingForNextDelta() {
        val controller = controller(Any())
        val retained = CarPlayNowPlaying(title = "retained", playing = false, playbackKnown = true)
        `when`(controller.nowPlayingSnapshot()).thenReturn(retained)
        GalaxyMediaKeys.attach(context, controller, {})
        assertEquals(retained, mediaInfo())
        assertNotNull(ReflectionHelpers.getField<CarPlayMediaSession?>(GalaxyMediaKeys, "bridge"))
    }

    @Test fun queuedOldMetadataIsIgnoredAfterControllerReplacement() {
        val old = controller(Any())
        var callback: ((CarPlayNowPlaying) -> Unit)? = null
        doAnswer { callback = it.getArgument(0); null }.`when`(old).nowPlayingListener = any()
        GalaxyMediaKeys.attach(context, old, {})
        val oldCallback = callback!!
        val next = controller(Any())
        val retained = CarPlayNowPlaying(title = "current")
        `when`(next.nowPlayingSnapshot()).thenReturn(retained)
        GalaxyMediaKeys.attach(context, next, {})
        oldCallback(CarPlayNowPlaying(title = "stale"))
        GalaxyMediaKeys.detach(old)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(next, ReflectionHelpers.getField(GalaxyMediaKeys, "controller"))
        assertEquals(retained, mediaInfo())
    }

    @Test fun sameControllerNewPhoneReleasesOldBridgeAndLateEndCannotCloseNewSession() {
        val firstOwner = Any()
        val controller = controller(firstOwner)
        GalaxyMediaKeys.attach(context, controller, {})
        val oldBridge: CarPlayMediaSession = ReflectionHelpers.getField(GalaxyMediaKeys, "bridge")
        val nextOwner = Any()
        `when`(controller.activeMediaSessionOwner()).thenReturn(nextOwner)
        val nextSong = CarPlayNowPlaying(title = "new-phone", playing = true, playbackKnown = true)
        `when`(controller.nowPlayingSnapshot()).thenReturn(nextSong)
        GalaxyMediaKeys.onSessionStateChanged(controller, true)
        val newBridge: CarPlayMediaSession = ReflectionHelpers.getField(GalaxyMediaKeys, "bridge")
        assertNotSame(oldBridge, newBridge)
        assertEquals(true, ReflectionHelpers.getField<Boolean>(oldBridge, "closed"))
        GalaxyMediaKeys.onSessionStateChanged(controller, false)
        assertSame(newBridge, ReflectionHelpers.getField(GalaxyMediaKeys, "bridge"))
        assertEquals(nextSong, mediaInfo())
    }
}
