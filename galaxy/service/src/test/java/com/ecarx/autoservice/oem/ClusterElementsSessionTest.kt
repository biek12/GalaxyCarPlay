package com.ecarx.autoservice.oem

import android.graphics.Bitmap
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.ecarx.autoservice.ipc.ClientRegistry
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.auto.contract.IAutoCallback
import com.galaxy.auto.contract.IAutoResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 用生产 [ClusterElementsSession]、真实 [com.ecarx.autoservice.ClusterBackgroundStore] 和租约核对图片导入。
 * Hook 只记录具名调用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterElementsSessionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val main = Handler(Looper.getMainLooper())
    private val background = ArrayDeque<Runnable>()
    private val results = mutableListOf<Bundle>()
    private val statuses = mutableListOf<String>()
    private val hookCalls = mutableListOf<String>()

    @Test
    fun `相同配置直接接受，成功失败和迟到都不覆盖下一代`() {
        val registry = ClientRegistry {}
        val session = session(registry)
        val owner = Binder()
        val lease = registry.attach(owner, 3, AutoContract.ELEMENTS, callback())
        val same =
            ClusterDisplayConfiguration(
                ClusterDisplayConfiguration.OEM_ELEMENTS,
                ClusterDisplayConfiguration.IMAGE_BACKGROUND,
                true,
                "same.png",
            )
        lease.elements = same
        val generation = session.imageGeneration
        try {
            session.submitImage(owner, 3, same, file("same.png"), result())
            shadowOf(main.looper).idle()
            assertTrue(background.isEmpty())
            assertTrue(results.single().getBoolean("accepted"))
            assertEquals(generation, session.imageGeneration)

            val good = image("good.png")
            session.submitImage(owner, 3, good, file("good.png"), result())
            shadowOf(main.looper).idle()
            assertEquals("pending", statuses.last())
            background.removeFirst().run()
            shadowOf(main.looper).idle()
            assertEquals("ready", session.configurationStatus)
            assertEquals(good, lease.elements)
            assertTrue(results.last().getBoolean("accepted"))
            assertTrue(hookCalls.isEmpty())

            val before = session.revision
            session.submitImage(owner, 3, image("bad.png"), text("bad.png"), result())
            shadowOf(main.looper).idle()
            val late = background.removeFirst()
            session.configure(lease, image("newer.png"))
            val current = session.configurationStatus
            val revision = session.revision
            late.run()
            shadowOf(main.looper).idle()
            assertEquals(current, session.configurationStatus)
            assertEquals(revision, session.revision)
            assertFalse(results.last().getBoolean("accepted"))
            assertEquals("background_import_failed", results.last().getString("error"))
            assertTrue(session.revision >= before)

            val packet =
                Bundle().apply {
                    putBoolean("clusterElementsRequested", true)
                    putBundle("clusterElements", image("newer.png").bundle())
                }
            session.applyHook(packet, true)
            assertEquals("update", hookCalls.single())
            session.published(4L, 1)
            assertEquals(listOf("update", "published"), hookCalls)
            session.closeHook()
            assertEquals(listOf("update", "published", "close"), hookCalls)
        } finally {
            registry.close()
        }
    }

    private lateinit var sessionStatusHolder: ClusterElementsSession

    private fun session(registry: ClientRegistry): ClusterElementsSession {
        val created =
            ClusterElementsSession(
                context = app,
                main = main,
                postBackground = { background += it },
                postCallback = { it.run() },
                clients = registry,
                closed = { false },
                onPublish = { statuses += sessionStatusHolder.configurationStatus },
                hookFactory = {
                    object : ClusterElementsSession.HookPort {
                        override fun update(
                            active: Boolean,
                            ready: Boolean,
                            config: ClusterDisplayConfiguration?,
                        ) {
                            hookCalls += "update"
                        }

                        override fun published(
                            generation: Long,
                            flags: Int,
                        ) {
                            hookCalls += "published"
                        }

                        override fun snapshot(): Bundle = Bundle().apply { putString("state", "idle") }

                        override fun close() {
                            hookCalls += "close"
                        }
                    }
                },
            )
        sessionStatusHolder = created
        return created
    }

    private fun image(name: String) =
        ClusterDisplayConfiguration(
            ClusterDisplayConfiguration.OEM_ELEMENTS,
            ClusterDisplayConfiguration.IMAGE_BACKGROUND,
            true,
            name,
        )

    private fun file(name: String): ParcelFileDescriptor {
        val png = File(app.cacheDir, name)
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap ->
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        return ParcelFileDescriptor.open(png, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun text(name: String): ParcelFileDescriptor {
        val file = File(app.cacheDir, name).apply { writeText("not-an-image") }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun result(): IAutoResult =
        object : IAutoResult.Stub() {
            override fun onResult(value: Bundle) {
                results += Bundle(value)
            }
        }

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
