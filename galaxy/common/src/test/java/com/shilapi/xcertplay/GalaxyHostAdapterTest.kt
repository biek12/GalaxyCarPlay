package com.shilapi.xcertplay

import androidx.activity.ComponentActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 产品接口装配回归：核心无需认识车型或产品偏好，注册本身不启动任何会话。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyHostAdapterTest {
    @After fun clearFactory() { HostProductBindings.factory = null }

    private fun adapter(activity: ComponentActivity, actions: HostProductActions = HostProductActions(
        home = {}, cancel = {}, releaseTouches = {}, currentController = { null }, report = {},
    )): CarPlayHostProduct {
        GalaxyHostAdapter.install()
        return requireNotNull(requireNotNull(HostProductBindings.factory).invoke(activity, actions))
    }

    @Test fun installationRoutesToGalaxyWithoutStartingControllerOrOemServices() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var actions = 0
        val product = adapter(activity, HostProductActions(
            home = { actions++ }, cancel = { actions++ }, releaseTouches = { actions++ },
            currentController = { actions++; null }, report = { actions++ },
        ))
        assertEquals("CarPlay", product.name)
        val destination = product.homeIntent("settings-connection")
        assertEquals(GalaxySettingsActivity::class.java.name, destination.component?.className)
        assertEquals("settings-connection", destination.getStringExtra("page"))
        assertEquals(0, actions)
        activity.finish()
    }

    @Test fun l6TemplateParametersDoNotPassThroughCoreLegacyStreamSanitizer() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.getSharedPreferences("l7_audio_templates", 0).edit().clear().commit()
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        val audio = adapter(activity).audioConfiguration()
        assertEquals(GalaxyVehicleAudioProfiles.Model.L6, GalaxyVehicleAudioProfiles.model(activity))
        assertEquals(101, audio.mediaChannel)
        assertEquals(103, audio.navigationChannel)
        assertEquals(102, audio.assistantChannel)
        assertTrue(audio.advancedMapping)
        assertTrue(audio.focusEnabled)
        assertTrue(audio.localMediaAudioEnabled)
        assertFalse(requireNotNull(audio.platform.template).preferBus)
        activity.finish()
    }

    @Test fun productIconScaleKeepsNonPresetLegacyChoiceWithoutChangingCoreDefaults() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().putInt("ui_scale_percent", 85).commit()
        assertEquals(85, adapter(activity).uiScalePercent())
        activity.finish()
    }
}
