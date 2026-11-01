package com.shilapi.xcertplay

import androidx.activity.ComponentActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation

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
        assertEquals(L7DisplayGeometry.forViewport(activity, 1440, 1920), product.physicalSize(1440, 1920))
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

    @Test
    @Config(shadows = [AvailableEcarxSpeed::class])
    fun productSpeedComesFromItsOwnSettingNotAnotherModelsSdk() {
        // 同一个宿主Activity内写设置再读产品判定：设置项与产品读取走同一份偏好实例。
        fun host(): CarPlayHostActivity {
            val value = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
            CarPlayHostActivity::class.java.getDeclaredField("airPlayIdentity").apply { isAccessible = true }
                .set(value, AirPlayPersistence.loadIdentity(value))
            CarPlayHostActivity::class.java.getDeclaredField("locationReportingEnabled").apply { isAccessible = true }
                .setBoolean(value, true)
            return value
        }
        fun runtime(value: CarPlayHostActivity): com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig =
            CarPlayHostActivity::class.java.getDeclaredMethod("createRuntimeConfig")
                .apply { isAccessible = true }.invoke(value) as com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig

        // 上游对照：没有产品注入时，ECARX SDK 存在即声明车速；这不是L6的核验结论。
        HostProductBindings.factory = null
        assertTrue(runtime(host()).identification.vehicleSpeedEnabled)

        GalaxyHostAdapter.install()
        val activity = host()
        GalaxyVehicleOutputSettings.setVehicleSpeedToIphone(activity, true)
        // 产品的车速声明来自自有来源（AutoService 订阅），不再由“本机装了ECARX SDK”推导。
        assertTrue(adapter(activity).vehicleSpeedToIphone()!!)
        assertNotNull(adapter(activity).vehicleSpeedSource())
        val enabled = runtime(activity)
        assertTrue(enabled.identification.locationInformationEnabled)
        assertTrue(enabled.identification.vehicleSpeedEnabled)

        // 关掉产品开关后，即使 SDK 仍在，车速能力也必须关；位置合同不受影响。
        GalaxyVehicleOutputSettings.setVehicleSpeedToIphone(activity, false)
        assertFalse(adapter(activity).vehicleSpeedToIphone()!!)
        assertNull(adapter(activity).vehicleSpeedSource())
        val disabled = runtime(activity)
        assertTrue(disabled.identification.locationInformationEnabled)
        assertFalse(disabled.identification.vehicleSpeedEnabled)
        activity.finish()
    }

    @Implements(EcarxWheelSpeed::class, isInAndroidSdk = false)
    class AvailableEcarxSpeed {
        @Implementation fun present(): Boolean = true
    }
}
