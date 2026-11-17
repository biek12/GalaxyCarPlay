package com.galaxy.auto.client.ipc

import android.os.Bundle
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.auto.contract.ProjectionPolicy
import com.galaxy.auto.contract.SceneConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 待发合并、重连补发、封面/元素FD与角色边界的真实行为用例。
 *
 * 所有状态都通过真实AutoClient公开API写入，再由真实握手与flush投递给记录型Stub；
 * 不读取owner私有字段，也不另造一份连接或重放模型。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AutoClientReplayTest {
    private fun service() = AutoClientConnectionTest.RecordingService()

    private fun subject(
        service: AutoClientConnectionTest.RecordingService,
        role: String = AutoContract.THEME,
        onState: (String) -> Unit = {},
        onReady: () -> Unit = {},
        replayVehicleFeedOnConnect: Boolean = true,
    ): AutoClient =
        AutoClientConnectionTest.createClient(
            service,
            role = role,
            onState = onState,
            onReady = onReady,
            replayVehicleFeedOnConnect = replayVehicleFeedOnConnect,
        )

    private fun ready(subject: AutoClient, service: AutoClientConnectionTest.RecordingService) {
        subject.connect()
        AutoClientConnectionTest.await { service.callback != null && service.names().contains("attach") }
        AutoClientConnectionTest.proveClientWorker(subject, service.callback!!)
    }

    private fun media(track: String) =
        Bundle().apply {
            putString("track", track)
            putString("title", "标题")
        }

    @Test
    fun `未连接时保留待发并按scene策略媒体导航封面顺序合并`() {
        val service = service()
        val client = subject(service)
        try {
            client.configureScene(SceneConfiguration(token = 1L, themeId = "theme", map = null))
            client.setProjectionPolicy(ProjectionPolicy())
            client.publishMedia(media("T1"))
            client.publishNavigation(Bundle().apply { putString("road", "环路") })
            val bytes = "art-payload".toByteArray()
            client.publishArtwork(bytes)
            bytes.fill(0)
            ready(client, service)
            val flushed =
                service.names().filter {
                    it in
                        setOf(
                            "configureScene",
                            "setProjectionPolicy",
                            "publishMedia",
                            "publishNavigation",
                            "publishTrackArtwork",
                        )
                }
            assertEquals(
                listOf(
                    "configureScene",
                    "setProjectionPolicy",
                    "publishMedia",
                    "publishNavigation",
                    "publishTrackArtwork",
                ),
                flushed,
            )
            assertEquals("T1", service.last("publishTrackArtwork").args[1])
            assertEquals("art-payload", service.fdText.last())
            assertEquals(1, service.names().count { it == "publishNavigation" })
            assertEquals("环路", (service.last("publishNavigation").args[1] as Bundle).getString("road"))
            assertFalse(
                AutoClientConnectionTest
                    .app()
                    .cacheDir
                    .listFiles { _, name -> name.startsWith("auto-art-") }
                    ?.isNotEmpty() ?: false,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `默认track取调用时lastMedia且字节是独立拷贝`() {
        val service = service()
        val client = subject(service)
        try {
            ready(client, service)
            client.publishMedia(media("TRACK-9"))
            val original = "copy-me".toByteArray()
            client.publishArtwork(original)
            original[0] = 'X'.code.toByte()
            AutoClientConnectionTest.await { service.names().contains("publishTrackArtwork") }
            assertEquals("TRACK-9", service.last("publishTrackArtwork").args[1])
            assertEquals("copy-me", service.fdText.last())
        } finally {
            client.close()
        }
    }

    @Test
    fun `断线期间的更新槽不被重连补发覆盖`() {
        val service = service()
        val readyCount = AtomicInteger()
        val client = subject(service, onReady = { readyCount.incrementAndGet() })
        try {
            ready(client, service)
            client.publishMedia(media("A"))
            AutoClientConnectionTest.await { service.names().contains("publishMedia") }
            AutoClientConnectionTest.connection().onBindingDied(AutoClientConnectionTest.SERVICE_NAME)
            AutoClientConnectionTest.pumpAll()
            client.publishMedia(media("B"))
            AutoClientConnectionTest.advance(1000)
            AutoClientConnectionTest.await { readyCount.get() >= 2 }
            val sent =
                service.calls
                    .filter { it.name == "publishMedia" }
                    .map { (it.args[1] as Bundle).getString("track") }
            assertEquals(listOf("A", "B"), sent)
        } finally {
            client.close()
        }
    }

    @Test
    fun `active订阅与元素按原顺序重放且关闭自动补发时不再重放订阅`() {
        fun run(replay: Boolean): Int {
            val service = service()
            val readyCount = AtomicInteger()
            val client =
                subject(service, role = AutoContract.ELEMENTS, onReady = {
                    readyCount.incrementAndGet()
                }, replayVehicleFeedOnConnect = replay)
            try {
                ready(client, service)
                AutoClientConnectionTest.await { readyCount.get() == 1 }
                client.setActive(true)
                client.configureClusterElements(ClusterDisplayConfiguration())
                client.setVehicleFeed(listOf("gear"), 500)
                AutoClientConnectionTest.await {
                    service.names().contains("setVehicleFeed") &&
                        service.names().contains("configureClusterElements") &&
                        service.names().count { it == "setActive" } >= 2
                }
                AutoClientConnectionTest.connection().onBindingDied(AutoClientConnectionTest.SERVICE_NAME)
                AutoClientConnectionTest.pumpAll()
                AutoClientConnectionTest.advance(1000)
                AutoClientConnectionTest.await { readyCount.get() >= 2 }
                val names = service.names()
                val secondAttach = names.withIndex().last { it.value == "attach" }.index
                val after = names.drop(secondAttach + 1)
                val elements = after.indexOf("configureClusterElements")
                val active = after.indexOf("setActive")
                val feed = after.indexOf("setVehicleFeed")
                assertTrue(elements >= 0 && active > elements)
                if (replay) assertTrue(feed > active) else assertEquals(-1, feed)
                return names.count { it == "setVehicleFeed" }
            } finally {
                client.close()
            }
        }
        assertTrue(run(true) >= 2)
        assertEquals(1, run(false))
    }

    @Test
    fun `空集合取消订阅与自定义间隔原样发送`() {
        val service = service()
        val client = subject(service, role = AutoContract.OBSERVER)
        try {
            ready(client, service)
            client.setVehicleFeed(listOf("gear", "displaySpeed", "gear"), 500)
            AutoClientConnectionTest.await { service.names().contains("setVehicleFeed") }
            assertEquals(listOf("displaySpeed", "gear"), service.last("setVehicleFeed").args[1])
            assertEquals(500, service.last("setVehicleFeed").args[2])
            client.setVehicleFeed(emptyList(), 300)
            AutoClientConnectionTest.await { service.names().count { it == "setVehicleFeed" } == 2 }
            assertEquals(emptyList<String>(), service.last("setVehicleFeed").args[1])
            assertEquals(300, service.last("setVehicleFeed").args[2])
        } finally {
            client.close()
        }
    }

    @Test
    fun `图片方案用真实只读FD保留输入文件且旧代次与旧revision回执丢弃`() {
        val states = CopyOnWriteArrayList<String>()
        val service = service()
        val client = subject(service, role = AutoContract.ELEMENTS, onState = { states.add(it) })
        val app = AutoClientConnectionTest.app()
        val image = File(app.cacheDir, "a2-bg.png").apply { writeText("IMAGE-BYTES") }
        val rejected = Bundle().apply { putBoolean("accepted", false) }
        try {
            val configuration =
                ClusterDisplayConfiguration(
                    mode = ClusterDisplayConfiguration.OEM_ELEMENTS,
                    background = ClusterDisplayConfiguration.IMAGE_BACKGROUND,
                    image = "a2-bg.png",
                )
            client.configureClusterElements(configuration, image)
            ready(client, service)
            AutoClientConnectionTest.await {
                service.names().contains("configureClusterImage") && service.fdReads.get() >= 1
            }
            assertEquals("IMAGE-BYTES", service.fdText.last())
            assertTrue(image.exists())
            assertFalse(service.heldFd.last().fileDescriptor.valid())
            val current = service.results.last()
            current.onResult(rejected)
            AutoClientConnectionTest.await { states.count { it == "background_import_failed" } == 1 }

            val link = AutoClientConnectionTest.connection()
            link.onBindingDied(AutoClientConnectionTest.SERVICE_NAME)
            AutoClientConnectionTest.pumpAll()
            current.onResult(rejected)
            AutoClientConnectionTest.pumpAll()
            assertEquals(1, states.count { it == "background_import_failed" })
            assertTrue(states.contains("binding_died"))
            val disconnects = states.filter { it == "binding_died" || it == "service_disconnected" }
            assertTrue(disconnects.contains("binding_died"))
            assertFalse(disconnects.contains("background_import_failed"))

            AutoClientConnectionTest.advance(1000)
            AutoClientConnectionTest.await { service.names().count { it == "attach" } >= 2 }
            val replayImages = service.names().count { it == "configureClusterImage" }
            client.configureClusterElements(configuration, image)
            AutoClientConnectionTest.await { service.names().count { it == "configureClusterImage" } == replayImages + 1 }
            val resultA = service.results.last()
            client.configureClusterElements(configuration, image)
            AutoClientConnectionTest.await {
                service.names().count { it == "configureClusterImage" } == replayImages + 2 &&
                    service.fdReads.get() >= replayImages + 2
            }
            val resultB = service.results.last()
            assertNotSame(resultA, resultB)
            val failedBeforeRevision = states.count { it == "background_import_failed" }
            resultA.onResult(rejected)
            AutoClientConnectionTest.pumpAll()
            assertEquals(failedBeforeRevision, states.count { it == "background_import_failed" })
            resultB.onResult(rejected)
            AutoClientConnectionTest.await {
                states.count { it == "background_import_failed" } == failedBeforeRevision + 1
            }
            assertTrue(image.exists())
            assertTrue(service.heldFd.all { !it.fileDescriptor.valid() })
        } finally {
            AutoClientConnectionTest.pumpAll()
            client.close()
            image.delete()
        }
    }

    @Test
    fun `flush异常不预先清空后续槽且无缓存的policy在重连后仍发出`() {
        val states = CopyOnWriteArrayList<String>()
        val service = service()
        val client = subject(service, onState = { states.add(it) })
        try {
            ready(client, service)
            service.throwOn = "configureScene"
            client.configureScene(SceneConfiguration(token = 1L, themeId = "theme", map = null))
            client.setProjectionPolicy(ProjectionPolicy())
            client.publishMedia(media("KEEP"))
            AutoClientConnectionTest.await { states.any { it == "ipc:IllegalStateException" } }
            service.throwOn = null
            AutoClientConnectionTest.connection().onBindingDied(AutoClientConnectionTest.SERVICE_NAME)
            AutoClientConnectionTest.advance(1000)
            AutoClientConnectionTest.await { service.names().count { it == "attach" } >= 2 }
            AutoClientConnectionTest.pumpAll()
            assertEquals(1, service.names().count { it == "setProjectionPolicy" })
            val tracks =
                service.calls
                    .filter { it.name == "publishMedia" }
                    .map { (it.args[1] as Bundle).getString("track") }
            assertTrue(tracks.contains("KEEP"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `图片远端异常关闭FD并保留输入文件且不发导入失败`() {
        val states = CopyOnWriteArrayList<String>()
        val service = service()
        val client = subject(service, role = AutoContract.ELEMENTS, onState = { states.add(it) })
        val image = File(AutoClientConnectionTest.app().cacheDir, "a2-bg-error.png").apply { writeText("IMAGE-ERR") }
        try {
            ready(client, service)
            service.throwOn = "configureClusterImage"
            client.configureClusterElements(
                ClusterDisplayConfiguration(
                    mode = ClusterDisplayConfiguration.OEM_ELEMENTS,
                    background = ClusterDisplayConfiguration.IMAGE_BACKGROUND,
                    image = "a2-bg-error.png",
                ),
                image,
            )
            AutoClientConnectionTest.await {
                service.fdReads.get() >= 1 && states.any { it == "ipc:IllegalStateException" }
            }
            assertEquals("IMAGE-ERR", service.fdText.last())
            assertFalse(service.heldFd.last().fileDescriptor.valid())
            assertTrue(image.exists())
            assertFalse(states.contains("background_import_failed"))
        } finally {
            AutoClientConnectionTest.pumpAll()
            client.close()
            image.delete()
        }
    }

    @Test
    fun `角色边界与policy可视媒体不建立重连保留缓存`() {
        val service = service()
        val mapClient = AutoClientConnectionTest.createClient(service, role = AutoContract.MAP)
        assertTrue(
            runCatching {
                mapClient.configureClusterElements(ClusterDisplayConfiguration())
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { mapClient.publishVisualMedia(media("V")) }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                mapClient.configureScene(SceneConfiguration(token = 1L, themeId = "theme", map = null))
            }.exceptionOrNull() is IllegalArgumentException,
        )
        mapClient.close()

        val visual = service()
        val visualClient = subject(visual, role = AutoContract.VISUAL_MUSIC)
        try {
            visualClient.connect()
            AutoClientConnectionTest.await { visual.names().contains("attach") }
            visualClient.setProjectionPolicy(ProjectionPolicy())
            visualClient.publishVisualMedia(media("V1"))
            AutoClientConnectionTest.await {
                visual.names().contains("publishVisualMedia") && visual.names().contains("setProjectionPolicy")
            }
            AutoClientConnectionTest.connection().onBindingDied(AutoClientConnectionTest.SERVICE_NAME)
            AutoClientConnectionTest.pumpAll()
            AutoClientConnectionTest.advance(1000)
            AutoClientConnectionTest.await { visual.names().count { it == "attach" } >= 2 }
            AutoClientConnectionTest.pumpAll()
            assertEquals(1, visual.names().count { it == "publishVisualMedia" })
            assertEquals(1, visual.names().count { it == "setProjectionPolicy" })
        } finally {
            visualClient.close()
        }
    }
}
