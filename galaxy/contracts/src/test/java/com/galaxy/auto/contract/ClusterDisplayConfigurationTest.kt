package com.galaxy.auto.contract

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ClusterDisplayConfigurationTest {
    @Test fun modesAndBackgroundsAreIndependentAndOldInstallDefaultsToOverlay() {
        val original = ClusterDisplayConfiguration()
        assertEquals(ClusterDisplayConfiguration.OVERLAY, original.mode)
        assertFalse(original.needsElements)
        val replacement = original.copy(mode = ClusterDisplayConfiguration.OEM_ELEMENTS,
            background = ClusterDisplayConfiguration.CARPLAY_MAP)
        assertEquals(replacement, ClusterDisplayConfiguration.from(replacement.bundle()))
        assertTrue(replacement.needsElements)
        assertFalse(replacement.copy(music = false, background = ClusterDisplayConfiguration.ORIGINAL).needsElements)
    }

    @Test fun rejectsUnknownSchemaAndUnownedImagePaths() {
        assertThrows(IllegalArgumentException::class.java) { ClusterDisplayConfiguration.from(Bundle()) }
        assertThrows(IllegalArgumentException::class.java) { ClusterDisplayConfiguration(mode = "unknown") }
        assertThrows(IllegalArgumentException::class.java) { ClusterDisplayConfiguration(background = "image") }
        assertThrows(IllegalArgumentException::class.java) { ClusterDisplayConfiguration(image = "../private.png") }
        assertThrows(IllegalArgumentException::class.java) { ClusterDisplayConfiguration(image = "content://photos/image") }
        val image = ClusterDisplayConfiguration(background = "image", image = "background_1.webp")
        assertEquals(image, ClusterDisplayConfiguration.from(image.bundle()))
    }
}
