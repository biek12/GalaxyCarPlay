package com.shilapi.xcertplay

import android.net.Uri
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GalaxyOemArtworkServerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun source(id: Int) = Uri.parse("content://${context.packageName}.media-artwork/$id.jpg")
    private fun request(url: Uri, path: String = url.path!!): String = Socket("127.0.0.1", url.port).use { socket ->
        socket.soTimeout = 3000
        socket.getOutputStream().apply { write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray()); flush() }
        socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
    }
    @Test fun servesOnlySelectedImagesWithDistinctUrlsAndBoundedHistory() {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        val server = GalaxyOemArtworkServer(context) { bytes }
        val first = server.url(source(1))!!
        try {
            assertEquals(first, server.url(source(1)))
            assertTrue(request(first).startsWith("HTTP/1.1 200 OK"))
            assertTrue(request(first).contains("Content-Length: 6"))
            assertTrue(request(first, "/../private").startsWith("HTTP/1.1 404 Not Found"))
            for (id in 2..4) assertNotEquals(first, server.url(source(id)))
            assertTrue(request(first).startsWith("HTTP/1.1 404 Not Found"))
            assertEquals(3, server.diagnostics()["httpImages"])
            assertNull(server.url(Uri.parse("file:///private/cover.jpg")))
            assertNull(server.url(Uri.parse("content://other.app/cover.jpg")))
        } finally { server.close() }
        assertNull(server.url(source(1)))
        assertFalse(server.diagnostics()["httpReady"] as Boolean)
        server.close()
    }
    @Test fun missingOrOversizedImageDoesNotStartAListener() {
        val server = GalaxyOemArtworkServer(context) { ByteArray(2 * 1024 * 1024 + 1) }
        try { assertNull(server.url(source(1))); assertFalse(server.diagnostics()["httpReady"] as Boolean) }
        finally { server.close() }
    }
    @Test fun defaultLoaderReadsOnlyThePrivateProviderAndProducesARealJpeg() {
        val file = java.io.File.createTempFile("cover-test", ".png", context.cacheDir)
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/png"
            override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, order: String?) = null
            override fun insert(uri: Uri, values: android.content.ContentValues?) = null
            override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
            override fun update(uri: Uri, values: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
            override fun openFile(uri: Uri, mode: String) = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val authority = "${context.packageName}.media-artwork"
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { this.authority = authority })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider)
        val server = GalaxyOemArtworkServer(context)
        try {
            val url = server.url(source(1))
            assertNotNull(url)
            assertTrue(request(url!!).startsWith("HTTP/1.1 200 OK"))
        } finally { server.close(); file.delete() }
    }
}
