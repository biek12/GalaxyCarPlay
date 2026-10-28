package com.ecarx.carplay.product

import android.content.Context
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyStartupDefaultsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences(
        GalaxyStartupDefaults.PREFERENCES, Context.MODE_PRIVATE,
    )

    @Before fun reset() {
        preferences.edit().clear().commit()
        context.getSharedPreferences("l7_audio_templates", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun firstLaunchUsesL6AndManualWithoutSelectingAuthentication() {
        GalaxyStartupDefaults.initialize(context)
        assertEquals("g733-2023-max-flymeauto-2.3.1", preferences.getString("galaxy_vehicle_profile", null))
        assertEquals("MANUAL", preferences.getString("wireless_hotspot_mode", null))
        assertEquals("l6", context.getSharedPreferences("l7_audio_templates", Context.MODE_PRIVATE).getString("model", null))
        assertFalse(preferences.contains("mfi_target"))
        assertFalse(preferences.contains("transport"))
    }

    @Test fun repeatedLaunchPreservesExplicitP2pProfileAndAuthentication() {
        preferences.edit()
            .putString("galaxy_vehicle_profile", "explicit-profile")
            .putString("wireless_hotspot_mode", WirelessHotspotMode.WIFI_P2P.name)
            .putString("mfi_target", "USB_CH341")
            .commit()
        context.getSharedPreferences("l7_audio_templates", Context.MODE_PRIVATE).edit()
            .putString("model", "l7").commit()
        repeat(2) { GalaxyStartupDefaults.initialize(context) }
        assertEquals("l7", context.getSharedPreferences("l7_audio_templates", Context.MODE_PRIVATE).getString("model", null))
        assertEquals("explicit-profile", preferences.getString("galaxy_vehicle_profile", null))
        assertEquals("WIFI_P2P", preferences.getString("wireless_hotspot_mode", null))
        assertEquals("USB_CH341", preferences.getString("mfi_target", null))
    }

    @Test fun missingProfileDoesNotResetSavedWirelessChoice() {
        preferences.edit().putString("wireless_hotspot_mode", "WIFI_P2P").commit()
        GalaxyStartupDefaults.initialize(context)
        assertEquals(GalaxyProduct.INITIAL_VEHICLE_PROFILE, preferences.getString("galaxy_vehicle_profile", null))
        assertEquals("WIFI_P2P", preferences.getString("wireless_hotspot_mode", null))
    }
}
