package com.galaxy.autocluster

import android.graphics.*
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterRendererTest {
    @Test fun independentThemeKeepsMapTransparentAndDrawsAllPanelsWithoutVehicleData() {
        val folder = File("build/screenshots").apply { mkdirs() }
        for (panel in listOf("MUSIC", "TIRES", "PEDALS")) {
            val bitmap = Bitmap.createBitmap(1920, 532, Bitmap.Config.ARGB_8888)
            ClusterRenderer().render(Canvas(bitmap), Bundle(), 5000, panel, null)
            assertEquals(0, Color.alpha(bitmap.getPixel(1210, 300)))
            assertTrue(Color.alpha(bitmap.getPixel(80, 30)) > 0)
            File(folder, "cluster-${panel.lowercase()}-unavailable.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
