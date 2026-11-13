package com.ecarx.autoservice.remote

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.ecarx.autoservice.ipc.ClientRegistry
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
import java.io.File

/**
 * 用生产 [ServiceRemoteSession] 核对网页和限时连接的开关、代次和上传副本。
 * WebPort 与 CommandPort 只代替 HTTP 资源。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ServiceRemoteSessionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val main = Handler(Looper.getMainLooper())
    private val calls = mutableListOf<String>()
    private val results = mutableListOf<Bundle>()
    private var report: ((String) -> Unit)? = null
    private var failUpload = false
    private var closed = false
    private var paused = false
    private var alive = true
    private var published = 0

    @Test
    fun `网页和远程独立开关，旧回调不复活，缺桥和异常会关闭副本`() {
        val session = session()
        try {
            session.update()
            assertEquals(listOf("web-start"), calls.toList())
            assertEquals("disabled", session.state())

            session.authorize(12)
            assertTrue(calls.contains("web-close"))
            session.update()
            assertTrue(calls.contains("command-start"))
            val first = report
            first?.invoke("connected")
            shadowOf(main.looper).idle()
            assertEquals("connected", session.state())
            assertEquals(1, published)

            calls.clear()
            session.disable()
            assertEquals(listOf("command-revoke"), calls.toList())
            session.update()
            assertEquals("disabled", session.state())

            session.authorize(24)
            session.update()
            val second = report
            second?.invoke("connected")
            shadowOf(main.looper).idle()
            val publishedAfter = published
            session.authorize(12)
            second?.invoke("connected")
            shadowOf(main.looper).idle()
            assertEquals(publishedAfter, published)
            session.update()
            paused = true
            calls.clear()
            session.update()
            assertTrue(calls.contains("web-close"))
            assertTrue(calls.contains("command-close"))
            session.disable()
            paused = false
            calls.clear()
            session.update()
            assertEquals(listOf("web-start"), calls.toList())

            val lease =
                ClientRegistry.Lease(
                    android.os.Binder(),
                    1,
                    "observer",
                    callback(),
                    android.os.IBinder.DeathRecipient {},
                )
            session.request(lease, "state", Bundle(), result())
            shadowOf(main.looper).idle()
            assertEquals("1", results.last().getString("ok"))
            assertTrue(published >= 2)

            calls.clear()
            session.closeWeb()
            session.request(lease, "state", Bundle(), result())
            assertEquals("AutoService 自动运行已关闭", results.last().getString("error"))

            failUpload = true
            session.update()
            val copy =
                ParcelFileDescriptor.open(
                    File(app.cacheDir, "clip.bin").apply {
                        writeBytes(ByteArray(16))
                    },
                    ParcelFileDescriptor.MODE_READ_WRITE,
                )
            session.upload(lease, "clip", "front", copy, 16, result())
            assertEquals("媒体上传不可用", results.last().getString("error"))
            assertFalse(copy.fileDescriptor.valid())
            alive = false
            closed = false
        } finally {
            session.closeWeb()
            session.closeRemote()
        }
    }

    private fun session(): ServiceRemoteSession =
        ServiceRemoteSession(
            context = app,
            main = main,
            postCallback = { it.run() },
            closed = { closed },
            paused = { paused },
            onPublish = { published += 1 },
            leaseAlive = { alive },
            webFactory = {
                object : ServiceRemoteSession.WebPort {
                    override fun start() {
                        calls += "web-start"
                    }

                    override fun close() {
                        calls += "web-close"
                    }

                    override fun request(
                        action: String,
                        payload: Bundle,
                        reply: (Bundle) -> Unit,
                    ) {
                        reply(Bundle().apply { putString("ok", "1") })
                    }

                    override fun upload(
                        clipId: String,
                        direction: String,
                        fd: ParcelFileDescriptor,
                        length: Long,
                        reply: (Bundle) -> Unit,
                    ) {
                        if (failUpload) {
                            error("upload")
                        }
                        fd.close()
                        reply(Bundle())
                    }
                }
            },
            commandFactory = { callback ->
                report = callback
                object : ServiceRemoteSession.CommandPort {
                    override fun start() {
                        calls += "command-start"
                    }

                    override fun close() {
                        calls += "command-close"
                    }

                    override fun revoke() {
                        calls += "command-revoke"
                    }
                }
            },
        )

    private fun result(): IAutoResult =
        object : IAutoResult.Stub() {
            override fun onResult(value: Bundle) {
                results += Bundle(value)
            }
        }

    private fun callback() =
        object : com.galaxy.auto.contract.IAutoCallback.Stub() {
            override fun onSnapshot(snapshot: Bundle) = Unit

            override fun onSurface(
                role: String,
                surface: android.view.Surface,
            ) = Unit

            override fun onProjectionState(
                role: String,
                state: String,
            ) = Unit

            override fun onVehicleFeed(values: Bundle) = Unit
        }
}
