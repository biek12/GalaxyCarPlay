package com.ecarx.autoservice.remote

import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.ecarx.autoservice.ipc.ClientRegistry
import com.galaxy.auto.contract.AutoContract
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
    private val registry = ClientRegistry {}
    private var failUpload = false
    private var holdUpload = false
    private var holdRequest = false
    private var heldUpload: ((Bundle) -> Unit)? = null
    private var heldRequest: ((Bundle) -> Unit)? = null
    private val uploadEntered = mutableListOf<String>()
    private val order = mutableListOf<String>()
    private var closed = false
    private var paused = false
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

            val owner = Binder()
            val lease = registry.attach(owner, 1, AutoContract.OBSERVER, callback())
            val publishedBefore = published
            session.request(lease, "state", Bundle(), result())
            shadowOf(main.looper).idle()
            assertEquals(listOf("publish", "result"), order.takeLast(2))
            assertEquals("1", results.last().getString("ok"))
            assertTrue(published > publishedBefore)

            calls.clear()
            session.closeWeb()
            session.request(lease, "state", Bundle(), result())
            assertEquals("AutoService 自动运行已关闭", results.last().getString("error"))
            assertTrue(uploadEntered.isEmpty())

            val missing = copy("missing.bin")
            session.upload(Binder(), 1, "clip", "front", missing, 16, result())
            assertEquals("媒体上传不可用", results.last().getString("error"))
            assertFalse(missing.fileDescriptor.valid())
            assertTrue(uploadEntered.isEmpty())

            val mismatch = copy("mismatch.bin")
            session.upload(owner, 2, "clip", "front", mismatch, 16, result())
            assertEquals("媒体上传不可用", results.last().getString("error"))
            assertFalse(mismatch.fileDescriptor.valid())
            assertTrue(uploadEntered.isEmpty())

            val absent = copy("absent.bin")
            session.upload(owner, 1, "clip", "front", absent, 16, result())
            assertEquals("媒体上传不可用", results.last().getString("error"))
            assertFalse(absent.fileDescriptor.valid())

            session.update()
            failUpload = true
            val failed = copy("failed.bin")
            session.upload(owner, 1, "clip", "front", failed, 16, result())
            assertEquals(listOf("clip"), uploadEntered)
            assertEquals("媒体上传不可用", results.last().getString("error"))
            assertFalse(failed.fileDescriptor.valid())

            failUpload = false
            holdUpload = true
            val held = copy("held.bin")
            val publishedAtUpload = published
            val resultsAtUpload = results.size
            session.upload(owner, 1, "held", "front", held, 16, result())
            assertEquals(listOf("clip", "held"), uploadEntered)
            assertFalse(held.fileDescriptor.valid())
            registry.remove(owner)
            heldUpload?.invoke(Bundle().apply { putBoolean("accepted", true) })
            shadowOf(main.looper).idle()
            assertEquals(publishedAtUpload, published)
            assertEquals(resultsAtUpload, results.size)

            val replacement = registry.attach(Binder(), 1, AutoContract.OBSERVER, callback())
            holdRequest = true
            session.request(replacement, "state", Bundle(), result())
            val publishedAtRequest = published
            val resultsAtRequest = results.size
            registry.remove(replacement.owner)
            heldRequest?.invoke(Bundle().apply { putString("ok", "late") })
            shadowOf(main.looper).idle()
            assertEquals(publishedAtRequest, published)
            assertEquals(resultsAtRequest, results.size)
            closed = false
        } finally {
            session.closeWeb()
            session.closeRemote()
            registry.close()
        }
    }

    private fun session(): ServiceRemoteSession =
        ServiceRemoteSession(
            context = app,
            main = main,
            postCallback = { it.run() },
            closed = { closed },
            paused = { paused },
            onPublish = {
                published += 1
                order += "publish"
            },
            clients = registry,
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
                        if (holdRequest) {
                            heldRequest = reply
                            return
                        }
                        reply(Bundle().apply { putString("ok", "1") })
                    }

                    override fun upload(
                        clipId: String,
                        direction: String,
                        fd: ParcelFileDescriptor,
                        length: Long,
                        reply: (Bundle) -> Unit,
                    ) {
                        uploadEntered += clipId
                        if (failUpload) {
                            error("upload")
                        }
                        fd.close()
                        if (holdUpload) {
                            heldUpload = reply
                            return
                        }
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
                order += "result"
                results += Bundle(value)
            }
        }

    private fun copy(name: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(
            File(app.cacheDir, name).apply { writeBytes(ByteArray(16)) },
            ParcelFileDescriptor.MODE_READ_WRITE,
        )

    private fun callback() =
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
