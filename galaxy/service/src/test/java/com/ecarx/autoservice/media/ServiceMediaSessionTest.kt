package com.ecarx.autoservice.media

import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.view.Surface
import com.ecarx.autoservice.ipc.ClientRegistry
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.IAutoCallback
import org.junit.Assert.assertArrayEquals
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowApplicationPackageManager
import org.robolectric.shadows.ShadowOs
import org.robolectric.shadows.ShadowParcelFileDescriptor
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 用生产 [ServiceMediaSession] 和迁包后的 [ArtworkProvider] 核对媒体优先级、封面 FD 和位图所有权。
 * 系统媒体会话只记录 enabled，不连接真实会话。
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [30],
    manifest = Config.NONE,
    shadows = [
        ServiceMediaSessionTest.RegularFileStatShadow::class,
        ServiceMediaSessionTest.ArtworkDupShadow::class,
        ServiceMediaSessionTest.SignatureQueryShadow::class,
    ],
)
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
        SignatureQueryShadow.signatureResult = PackageManager.SIGNATURE_MATCH
        try {
            session.publishCarPlay(track("song"))
            val source = png("source.png", Color.RED)
            val opened = openRegular(source)
            session.receiveArtwork(owner, 7, opened, null)
            assertFalse(opened.fileDescriptor.valid())
            shadowOf(main.looper).idle()
            artwork.removeFirst().run()
            shadowOf(main.looper).idle()
            assertTrue(session.artworkReady())
            assertArrayEquals(source.readBytes(), artworkFile().readBytes())

            val oldFile = png("old.png", Color.GREEN)
            val newFile = png("new.png", Color.BLUE)
            session.publishCarPlay(track("old"))
            val oldFd = openRegular(oldFile)
            session.receiveArtwork(owner, 7, oldFd, null)
            shadowOf(main.looper).idle()
            session.publishCarPlay(track("new"))
            val newFd = openRegular(newFile)
            session.receiveArtwork(owner, 7, newFd, "new")
            shadowOf(main.looper).idle()
            val oldJob = artwork.removeFirst()
            val newJob = artwork.removeFirst()
            newJob.run()
            shadowOf(main.looper).idle()
            val written = artworkFile().readBytes()
            assertArrayEquals(newFile.readBytes(), written)
            oldJob.run()
            shadowOf(main.looper).idle()
            assertArrayEquals(written, artworkFile().readBytes())
            assertTrue(ArtworkDupShadow.copies.isNotEmpty())
            assertTrue(ArtworkDupShadow.copies.all { !it.fileDescriptor.valid() })

            val bad = File(app.cacheDir, "bad.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val badFd = openRegular(bad)
            session.receiveArtwork(owner, 7, badFd, "new")
            shadowOf(main.looper).idle()
            artwork.removeFirst().run()
            shadowOf(main.looper).idle()
            assertArrayEquals(written, artworkFile().readBytes())

            val huge = File(app.cacheDir, "huge.bin")
            huge.outputStream().use { output ->
                val chunk = ByteArray(1024)
                var remaining = 2 * 1024 * 1024 + 1
                while (remaining > 0) {
                    val count = minOf(chunk.size, remaining)
                    output.write(chunk, 0, count)
                    remaining -= count
                }
            }
            val hugeFd = openRegular(huge)
            session.receiveArtwork(owner, 7, hugeFd, "new")
            shadowOf(main.looper).idle()
            artwork.removeFirst().run()
            shadowOf(main.looper).idle()
            assertArrayEquals(written, artworkFile().readBytes())
            assertFalse(hugeFd.fileDescriptor.valid())

            val pipes = ParcelFileDescriptor.createPipe()
            RegularFileStatShadow.pipe(pipes[0].fileDescriptor)
            val rejected =
                assertThrows(IllegalStateException::class.java) {
                    session.receiveArtwork(owner, 7, pipes[0], null)
                }
            assertEquals("artwork_requires_regular_file", rejected.message)
            assertFalse(pipes[0].fileDescriptor.valid())
            pipes[1].close()

            val provider = ArtworkProvider()
            provider.attachInfo(app, ProviderInfo().apply { authority = "com.ecarx.autoservice.artwork" })
            val current = Uri.parse("content://com.ecarx.autoservice.artwork/current")
            assertEquals(PackageManager.SIGNATURE_MATCH, app.packageManager.checkSignatures(11, 22))
            provider.openFile(current, "r").use { handle -> assertNotNull(handle) }
            assertThrows(IllegalArgumentException::class.java) { provider.openFile(current, "w") }
            assertThrows(IllegalArgumentException::class.java) {
                provider.openFile(Uri.parse("content://com.ecarx.autoservice.artwork/other"), "r")
            }
            SignatureQueryShadow.signatureResult = PackageManager.SIGNATURE_NO_MATCH
            assertEquals(PackageManager.SIGNATURE_NO_MATCH, app.packageManager.checkSignatures(11, 22))
            assertThrows(IllegalStateException::class.java) { provider.openFile(current, "r") }
        } finally {
            SignatureQueryShadow.signatureResult = PackageManager.SIGNATURE_MATCH
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

    private fun png(
        name: String,
        color: Int,
    ): File {
        val file = File(app.cacheDir, name)
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        return file
    }

    private fun openRegular(file: File): ParcelFileDescriptor {
        val opened = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        RegularFileStatShadow.regular(opened.fileDescriptor, file.length())
        return opened
    }

    private fun artworkFile(): File = File(app.cacheDir, "artwork.png")

    /** 只替换普通文件和管道的 fstat，长度来自测试已经创建的文件。 */
    @Implements(Os::class)
    class RegularFileStatShadow {
        companion object {
            private val lengths = IdentityHashMap<FileDescriptor, Long>()
            private val pipes = Collections.newSetFromMap(IdentityHashMap<FileDescriptor, Boolean>())

            fun regular(
                fd: FileDescriptor,
                length: Long,
            ) {
                lengths[fd] = length
            }

            fun pipe(fd: FileDescriptor) {
                pipes.add(fd)
            }

            @JvmStatic
            @Implementation
            @Throws(ErrnoException::class)
            fun fstat(fd: FileDescriptor): StructStat {
                val length = lengths[fd]
                if (length != null) {
                    return stat(OsConstants.S_IFREG, length)
                }
                if (pipes.contains(fd)) {
                    return stat(OsConstants.S_IFIFO, 0)
                }
                return Shadow.directlyOn<StructStat, Os>(
                    Os::class.java,
                    "fstat",
                    ReflectionHelpers.ClassParameter.from(FileDescriptor::class.java, fd),
                )
            }

            private fun stat(
                mode: Int,
                size: Long,
            ): StructStat = StructStat(0, 0, mode, 1, 0, 0, 0, size, 0, 0, 0, 512, 0)

            @JvmStatic
            @Implementation
            @Throws(ErrnoException::class)
            fun lseek(
                fd: FileDescriptor,
                offset: Long,
                whence: Int,
            ): Long {
                val method =
                    ShadowOs::class.java.getDeclaredMethod(
                        "lseek",
                        FileDescriptor::class.java,
                        Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                method.isAccessible = true
                return method.invoke(null, fd, offset, whence) as Long
            }
        }
    }

    /** 记录封面 dup 副本，打开和关闭仍走原来的文件边界。 */
    @Implements(ParcelFileDescriptor::class)
    class ArtworkDupShadow : ShadowParcelFileDescriptor() {
        companion object {
            val copies = mutableListOf<ParcelFileDescriptor>()

            @JvmStatic
            @Implementation(methodName = "dup")
            @Throws(IOException::class)
            fun recordDup(fd: FileDescriptor): ParcelFileDescriptor {
                val method =
                    ShadowParcelFileDescriptor::class.java.getDeclaredMethod(
                        "dup",
                        FileDescriptor::class.java,
                    )
                method.isAccessible = true
                val copy = method.invoke(null, fd) as ParcelFileDescriptor
                copies += copy
                return copy
            }
        }
    }

    /** 测试可改签名查询结果。openFile 仍走生产 Provider。 */
    @Implements(className = "android.app.ApplicationPackageManager")
    class SignatureQueryShadow : ShadowApplicationPackageManager() {
        @Implementation
        public override fun checkSignatures(
            uid1: Int,
            uid2: Int,
        ): Int = signatureResult

        companion object {
            var signatureResult: Int = PackageManager.SIGNATURE_MATCH
        }
    }
}
