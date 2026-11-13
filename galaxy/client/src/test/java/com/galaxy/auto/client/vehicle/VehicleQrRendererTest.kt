package com.galaxy.auto.client.vehicle

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.HandlerThread
import android.os.Looper
import android.widget.ImageView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 二维码测试走生产编码，只替换线程调度或注入会失败的编码边界。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleQrRendererTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `真实编码可解码回原地址且重复地址不重新编码`() {
        val url = "https://example.test/galaxy/app/vehicles#bind=0123456789abcdef"
        var encodes = 0
        val image = image()
        val fixture =
            fixture {
                encodes += 1
                VehicleQrRenderer.encodeUrl(it)
            }
        fixture.renderer.submit(url, image, { true }, { error("不应失败") })
        fixture.drain()
        val bitmap = bitmap(image)
        assertEquals(512, bitmap.width)
        assertEquals(512, bitmap.height)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.all { it == Color.BLACK || it == Color.WHITE })
        assertTrue(pixels.any { it == Color.BLACK })
        assertEquals(url, decode(bitmap))
        fixture.renderer.submit(url, image, { true }, { error("不应失败") })
        fixture.drain()
        assertEquals(1, encodes)
        fixture.close()
    }

    @Test
    fun `编码异常不挂上占位图`() {
        val image = image()
        var failed = false
        val fixture = fixture { error("qr") }
        fixture.renderer.submit("https://example.test/qr", image, { true }, { failed = true })
        fixture.drain()
        assertTrue(failed)
        assertNull(image.drawable)
        fixture.close()
    }

    @Test
    fun `旧代次和到期结果不显示并回收`() {
        val image = image()
        val created = AtomicReference<Bitmap>()
        val fixture =
            fixture {
                VehicleQrRenderer.encodeUrl(it).also(created::set)
            }
        fixture.renderer.submit("https://example.test/old", image, { false }, {})
        fixture.drain()
        assertNull(image.drawable)
        assertTrue(created.get().isRecycled)
        fixture.close()
    }

    @Test
    fun `关闭后的迟到任务只回收不复活页面`() {
        val image = image()
        val created = AtomicReference<Bitmap>()
        val fixture =
            fixture {
                VehicleQrRenderer.encodeUrl(it).also(created::set)
            }
        fixture.renderer.submit("https://example.test/late", image, { true }, {})
        shadowOf(fixture.thread.looper).idle()
        fixture.renderer.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(image.drawable)
        assertTrue(created.get().isRecycled)
        assertTrue(fixture.thread.isAlive)
        runCatching { shadowOf(fixture.thread.looper).quitUnchecked() }
        fixture.thread.join(1000)
    }

    @Test
    fun `后到的地址覆盖尚未完成的编码且中间地址不额外编码`() {
        val image = image()
        val urlA = "https://example.test/galaxy/app/vehicles#bind=aaa"
        val urlB = "https://example.test/galaxy/app/vehicles#bind=bbb"
        val urlC = "https://example.test/galaxy/app/vehicles#bind=ccc"
        val encoded = mutableListOf<String>()
        val createdA = AtomicReference<Bitmap>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = HandlerThread("qr-coalesce-${System.nanoTime()}").apply { start() }
        shadowOf(thread.looper).unPause()
        val renderer =
            VehicleQrRenderer(
                workerLooper = thread.looper,
                encode = { url ->
                    synchronized(encoded) { encoded += url }
                    val bitmap = VehicleQrRenderer.encodeUrl(url)
                    if (url == urlA) {
                        createdA.set(bitmap)
                        started.countDown()
                        check(release.await(2, TimeUnit.SECONDS))
                    }
                    bitmap
                },
            )
        try {
            renderer.submit(urlA, image, { true }, {})
            assertTrue(started.await(2, TimeUnit.SECONDS))
            renderer.submit(urlB, image, { true }, {})
            renderer.submit(urlC, image, { true }, {})
            release.countDown()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (image.drawable == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertEquals(listOf(urlA, urlC), synchronized(encoded) { encoded.toList() })
            assertTrue(createdA.get().isRecycled)
            assertEquals(urlC, decode(bitmap(image)))
            renderer.close()
            assertTrue(thread.isAlive)
        } finally {
            if (thread.isAlive) {
                runCatching { shadowOf(thread.looper).quitUnchecked() }
                thread.join(1000)
            }
        }
    }

    @Test
    fun `关闭后编码线程退出`() {
        val renderer = VehicleQrRenderer()
        val thread =
            renderer.javaClass
                .getDeclaredField(
                    "thread",
                ).apply { isAccessible = true }
                .get(renderer) as HandlerThread
        assertTrue(thread.isAlive)
        try {
            renderer.close()
            thread.join(2000)
            assertFalse(thread.isAlive)
        } finally {
            if (thread.isAlive) {
                runCatching { shadowOf(thread.looper).quitUnchecked() }
                thread.join(1000)
            }
        }
    }

    private class Fixture(
        val thread: HandlerThread,
        val renderer: VehicleQrRenderer,
    ) {
        fun drain() {
            shadowOf(thread.looper).idle()
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun close() {
            renderer.close()
            runCatching { shadowOf(thread.looper).quitUnchecked() }
            thread.join(1000)
        }
    }

    private fun fixture(encode: (String) -> Bitmap): Fixture {
        val thread = HandlerThread("qr-test-${System.nanoTime()}").apply { start() }
        return Fixture(thread, VehicleQrRenderer(workerLooper = thread.looper, encode = encode))
    }

    private fun image() = ImageView(activity)

    private fun bitmap(image: ImageView): Bitmap = (image.drawable as BitmapDrawable).bitmap

    private fun decode(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val reader = MultiFormatReader()
        reader.setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        return reader.decode(BinaryBitmap(HybridBinarizer(source))).text
    }
}
