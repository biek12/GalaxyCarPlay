package com.ecarx.autoservice.media

import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.ecarx.autoservice.ipc.ClientRegistry
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.IAutoCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.ArrayDeque

/**
 * 用生产 [ServiceMediaSession] 和迁包后的 [ArtworkProvider] 核对媒体优先级、封面 FD 和位图所有权。
 * 系统媒体会话只记录 enabled，不连接真实会话。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServiceMediaSessionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val main = Handler(Looper.getMainLooper())
    private val artwork = ArrayDeque<Runnable>()
    private val registry = ClientRegistry {}
    private val enabled = mutableListOf<Boolean>()

    @Test
    fun `原车优先于CarPlay，曲目变化和匹配的原始歌词会清掉可视歌词`() {
        val session = session()
        val owner = Binder()
        registry.attach(owner, 7, AutoContract.VISUAL_MUSIC, callback())
        try {
            session.publishCarPlay(track("car"))
            assertEquals("car", session.media().getString("track"))
            assertTrue(session.publishVisual(track("car").apply { putStringArrayList("lyrics", arrayListOf("可视")) }))
            assertEquals("可视", session.visualMedia().getStringArrayList("lyrics")?.single())
            session.ensureNative()
            receiveNative(track("native"), null)
            assertEquals("native", session.media().getString("track"))
            assertTrue(session.visualMedia().isEmpty)
            receiveNative(null, null)
            assertEquals("car", session.media().getString("track"))
            session.publishVisual(track("car"))
            session.publishCarPlay(
                track("car").apply {
                    putStringArrayList("lyrics", arrayListOf("原词"))
                    putLongArray("lyricTimes", longArrayOf(0))
                },
            )
            assertTrue(session.visualMedia().isEmpty)
            assertFalse(session.publishVisual(track("other")))
        } finally {
            registry.close()
        }
    }

    @Test
    fun `封面只接受有界普通文件，旧代次不覆盖，异签名和非当前路径拒绝`() {
        val session = session()
        val owner = Binder()
        registry.attach(owner, 7, AutoContract.MAP, callback())
        try {
            session.publishCarPlay(track("song"))
            val png = File(app.cacheDir, "source.png")
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap ->
                png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val opened = ParcelFileDescriptor.open(png, ParcelFileDescriptor.MODE_READ_ONLY)
            val mode =
                android.system.Os
                    .fstat(opened.fileDescriptor)
                    .st_mode
            if (android.system.OsConstants.S_ISREG(mode)) {
                session.receiveArtwork(owner, 7, opened, null)
                shadowOf(main.looper).idle()
                artwork.removeFirst().run()
                shadowOf(main.looper).idle()
                assertTrue(session.artworkReady())
            } else {
                val rejected =
                    assertThrows(IllegalStateException::class.java) { session.receiveArtwork(owner, 7, opened, null) }
                assertEquals("artwork_requires_regular_file", rejected.message)
                File(app.cacheDir, "artwork.png").writeBytes(png.readBytes())
            }
            val version = session.artworkVersion()

            session.publishCarPlay(track("next"))
            assertFalse(session.artworkReady())
            assertTrue(session.artworkVersion() > version)

            val bad = File(app.cacheDir, "bad.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val badFd = ParcelFileDescriptor.open(bad, ParcelFileDescriptor.MODE_READ_ONLY)
            if (android.system.OsConstants.S_ISREG(
                    android.system.Os
                        .fstat(badFd.fileDescriptor)
                        .st_mode,
                )
            ) {
                session.receiveArtwork(owner, 7, badFd, "next")
                shadowOf(main.looper).idle()
                artwork.removeFirst().run()
                shadowOf(main.looper).idle()
            } else {
                val rejected =
                    assertThrows(IllegalStateException::class.java) { session.receiveArtwork(owner, 7, badFd, "next") }
                assertEquals("artwork_requires_regular_file", rejected.message)
            }
            assertFalse(session.artworkReady())

            val pipes = ParcelFileDescriptor.createPipe()
            assertThrows(IllegalStateException::class.java) {
                session.receiveArtwork(owner, 7, pipes[0], null)
            }
            pipes[1].close()

            File(app.cacheDir, "artwork.png").writeBytes(png.readBytes())
            val provider = ArtworkProvider()
            provider.attachInfo(app, ProviderInfo().apply { authority = "com.ecarx.autoservice.artwork" })
            val current = Uri.parse("content://com.ecarx.autoservice.artwork/current")
            provider.openFile(current, "r").use { opened -> assertNotNull(opened) }
            assertThrows(IllegalArgumentException::class.java) { provider.openFile(current, "w") }
            assertThrows(IllegalArgumentException::class.java) {
                provider.openFile(Uri.parse("content://com.ecarx.autoservice.artwork/other"), "r")
            }
        } finally {
            registry.close()
        }
    }

    @Test
    fun `缩放后的独立位图才回收，借来的原图保持可用`() {
        val session = session()
        try {
            session.ensureNative()
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            receiveNative(track("cover"), bitmap)
            artwork.removeFirst().run()
            shadowOf(main.looper).idle()
            assertFalse(bitmap.isRecycled)
            assertTrue(session.artworkReady())
            val queued = artwork.size
            receiveNative(track("cover"), bitmap)
            assertEquals(queued, artwork.size)
            bitmap.recycle()
        } finally {
            registry.close()
        }
    }

    private lateinit var nativeReceive: (Bundle?, Bitmap?) -> Unit

    private fun session(): ServiceMediaSession =
        ServiceMediaSession(
            context = app,
            main = main,
            postArtwork = { artwork += it },
            postCallback = { it.run() },
            clients = registry,
            closed = { false },
            paused = { false },
            featuresRoot = { true },
            automatic = { true },
            onPublish = {},
            onKanziPublished = { _, _ -> },
            sourceFactory = { receive ->
                nativeReceive = receive
                object : ServiceMediaSession.SourcePort {
                    override fun enabled(value: Boolean) {
                        enabled += value
                    }

                    override fun close() = Unit
                }
            },
        )

    private fun receiveNative(
        value: Bundle?,
        bitmap: Bitmap?,
    ) {
        nativeReceive(value, bitmap)
    }

    private fun track(name: String): Bundle = Bundle().apply { putString("track", name) }

    private fun callback(): IAutoCallback =
        object : IAutoCallback.Stub() {
            override fun onSnapshot(snapshot: Bundle) = Unit

            override fun onSurface(
                role: String,
                surface: Surface,
            ) = Unit

            override fun onProjectionState(
                role: String,
                state: String,
            ) = Unit

            override fun onVehicleFeed(values: Bundle) = Unit
        }
}
