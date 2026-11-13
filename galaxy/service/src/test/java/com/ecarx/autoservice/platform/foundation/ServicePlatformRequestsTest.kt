package com.ecarx.autoservice.platform.foundation

import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import com.ecarx.autoservice.SerialWorker
import com.ecarx.autoservice.ipc.ClientRegistry
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.IAutoCallback
import com.galaxy.auto.contract.IAutoResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 用生产 [ServiceShellRequests] 和 [ClientPermissionRequests] 核对 busy、平台关闭、回执和授权命令。
 * 只替换 executeShell、ensureRoot 和系统权限查询。排队仍走真实 [SerialWorker]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ServicePlatformRequestsTest {
    private val shellName = "platform-shell-${System.nanoTime()}"
    private val callbackName = "platform-callbacks-${System.nanoTime()}"
    private val main = Handler(Looper.getMainLooper())
    private val shell = SerialWorker(shellName)
    private val callbacks = SerialWorker(callbackName)

    @Test
    fun `Shell返回原字段，忙时拒绝，平台关闭不执行，旧租约不回执`() {
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val results = Collections.synchronizedList(mutableListOf<Bundle>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var paused = false
        var alive = true
        val requests =
            ServiceShellRequests(
                postShell = shell::post,
                postMain = { action -> main.post(action) },
                postCallback = callbacks::post,
                executeShell = { command, timeout, transport ->
                    if (command == "one") {
                        entered.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                    }
                    commands += "$transport:$timeout:$command"
                    JSONObject()
                        .put("output", "ok")
                        .put("exitCode", 3)
                        .put("timedOut", false)
                        .put("truncated", true)
                },
                closed = { false },
                paused = { paused },
                automatic = { true },
                leaseAlive = { alive },
            )
        val lease = lease()
        try {
            requests.execute(lease, "echo", 1_000, "ADB", result(results))
            assertTrue(await { results.size == 1 })
            val ok = results.single()
            assertEquals(listOf("ADB:1000:echo"), commands.toList())
            assertEquals("ok", ok.getString("output"))
            assertEquals(3, ok.getInt("exitCode"))
            assertFalse(ok.getBoolean("timedOut"))
            assertTrue(ok.getBoolean("truncated"))
            assertFalse(ok.containsKey("error"))

            requests.execute(lease, "one", 1_000, "ROOT", result(results))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            requests.execute(lease, "two", 1_000, "ROOT", result(results))
            assertTrue(await { results.any { it.getString("error") == "shell_busy" } })
            release.countDown()
            assertTrue(await { results.size == 3 })
            assertEquals("shell_busy", results[1].getString("error"))
            assertEquals(listOf("ADB:1000:echo", "ROOT:1000:one"), commands.toList())
            assertEquals("ok", results[2].getString("output"))

            paused = true
            requests.execute(lease, "stopped", 1_000, "ROOT", result(results))
            assertTrue(await { results.size == 4 })
            assertEquals("IllegalStateException", results[3].getString("error"))
            assertEquals(listOf("ADB:1000:echo", "ROOT:1000:one"), commands.toList())

            paused = false
            alive = false
            requests.execute(lease, "late", 1_000, "ROOT", result(results))
            assertTrue(await { commands.size == 3 })
            idleMain()
            Thread.sleep(50)
            assertEquals(4, results.size)
            assertEquals("ROOT:1000:late", commands[2])
        } finally {
            release.countDown()
            shell.close()
            callbacks.close()
        }
    }

    @Test
    fun `Shell异常只回类名，结果里的error原样保留`() {
        val results = Collections.synchronizedList(mutableListOf<Bundle>())
        var fail = true
        val requests =
            ServiceShellRequests(
                postShell = shell::post,
                postMain = { action -> main.post(action) },
                postCallback = callbacks::post,
                executeShell = { _, _, _ ->
                    if (fail) {
                        throw IllegalArgumentException("hidden")
                    }
                    JSONObject()
                        .put("output", "x")
                        .put("exitCode", 1)
                        .put("timedOut", true)
                        .put("truncated", false)
                        .put("error", "denied")
                },
                closed = { false },
                paused = { false },
                automatic = { true },
                leaseAlive = { true },
            )
        try {
            requests.execute(lease(), "cmd", 200, "ROOT", result(results))
            assertTrue(await { results.size == 1 })
            assertEquals("IllegalArgumentException", results[0].getString("error"))
            assertFalse(results[0].containsKey("output"))
            fail = false
            requests.execute(lease(), "cmd", 200, "ROOT", result(results))
            assertTrue(await { results.size == 2 })
            assertEquals("denied", results[1].getString("error"))
            assertEquals("x", results[1].getString("output"))
            assertTrue(results[1].getBoolean("timedOut"))
        } finally {
            shell.close()
            callbacks.close()
        }
    }

    @Test
    fun `缺权限才准备Root，OVERLAY和pm grant使用原命令，回读失败不放行`() {
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val results = Collections.synchronizedList(mutableListOf<Bundle>())
        val granted = mutableSetOf<String>()
        var rootCalls = 0
        var rootEnabled = true
        var paused = false
        var alive = true
        var exitCode = 0
        val policy = ClientPermissionPolicy
        val requests =
            ClientPermissionRequests(
                postShell = shell::post,
                postMain = { action -> main.post(action) },
                postCallback = callbacks::post,
                executeShell = { command, timeout, transport ->
                    commands += "$transport:$timeout:$command"
                    if (command.startsWith("pm grant")) {
                        granted += policy.CAMERA
                    }
                    if (command.startsWith("cmd appops")) {
                        granted += policy.OVERLAY
                    }
                    JSONObject().put("exitCode", exitCode).put("timedOut", false)
                },
                ensureRoot = { interactive ->
                    check(!interactive)
                    rootCalls += 1
                    "ready"
                },
                closed = { false },
                paused = { paused },
                automatic = { true },
                rootEnabled = { rootEnabled },
                overlayAllowed = { _, target ->
                    assertEquals(policy.RECORD_PACKAGE, target)
                    policy.OVERLAY in granted
                },
                permissionGranted = { permission, _ -> permission in granted },
                leaseAlive = { alive },
            )
        val calling = 200_123
        val requested = listOf(policy.CAMERA, policy.OVERLAY)
        try {
            exitCode = 0
            granted.clear()
            // 这条用例不让命令本身改回读结果，专门看回读失败。
            val readback =
                ClientPermissionRequests(
                    postShell = shell::post,
                    postMain = { action -> main.post(action) },
                    postCallback = callbacks::post,
                    executeShell = { command, timeout, transport ->
                        commands += "$transport:$timeout:$command"
                        JSONObject().put("exitCode", 0).put("timedOut", false)
                    },
                    ensureRoot = { interactive ->
                        check(!interactive)
                        rootCalls += 1
                        "ready"
                    },
                    closed = { false },
                    paused = { false },
                    automatic = { true },
                    rootEnabled = { true },
                    overlayAllowed = { _, _ -> false },
                    permissionGranted = { _, _ -> false },
                    leaseAlive = { true },
                )
            readback.request(lease(), calling, requested, result(results))
            assertTrue(await { results.size == 1 })
            assertEquals(1, rootCalls)
            assertEquals(
                listOf(
                    "ROOT:10000:pm grant --user 2 com.galaxy.autorecord android.permission.CAMERA",
                    "ROOT:10000:cmd appops set --user 2 com.galaxy.autorecord SYSTEM_ALERT_WINDOW allow",
                ),
                commands.toList(),
            )
            assertFalse(results.single().getBoolean("granted"))
            assertEquals("permission_readback_failed", results.single().getString("error"))

            commands.clear()
            results.clear()
            rootCalls = 0
            granted.clear()
            requests.request(lease(), calling, requested, result(results))
            assertTrue(await { results.size == 1 })
            assertEquals(1, rootCalls)
            assertTrue(results.single().getBoolean("granted"))
            assertFalse(results.single().containsKey("error"))

            commands.clear()
            results.clear()
            rootCalls = 0
            requests.request(lease(), calling, requested, result(results))
            assertTrue(await { results.size == 1 })
            assertEquals(0, rootCalls)
            assertTrue(commands.isEmpty())
            assertTrue(results.single().getBoolean("granted"))

            granted.clear()
            commands.clear()
            results.clear()
            exitCode = 7
            requests.request(lease(), calling, listOf(policy.CAMERA), result(results))
            assertTrue(await { results.size == 1 })
            assertEquals(1, rootCalls)
            assertEquals(1, commands.size)
            assertEquals("permission_grant_failed", results.single().getString("error"))

            commands.clear()
            results.clear()
            rootCalls = 0
            granted.clear()
            rootEnabled = false
            requests.request(lease(), calling, listOf(policy.CAMERA), result(results))
            assertTrue(await { results.size == 1 })
            assertEquals(0, rootCalls)
            assertTrue(commands.isEmpty())
            assertEquals("root_disabled", results.single().getString("error"))

            rootEnabled = true
            exitCode = 0
            granted.clear()
            paused = true
            results.clear()
            rootCalls = 0
            requests.request(lease(), calling, requested, result(results))
            assertTrue(await { results.size == 1 })
            assertEquals("platform_stopped", results.single().getString("error"))
            assertEquals(0, rootCalls)

            paused = false
            alive = false
            granted.clear()
            results.clear()
            commands.clear()
            requests.request(lease(), calling, requested, result(results))
            assertTrue(await { commands.size == 2 })
            idleMain()
            Thread.sleep(80)
            assertTrue(results.isEmpty())
        } finally {
            shell.close()
            callbacks.close()
        }
    }

    private fun lease(): ClientRegistry.Lease =
        ClientRegistry.Lease(
            owner = Binder(),
            uid = 1,
            role = AutoContract.OBSERVER,
            callback =
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
                },
            death = IBinder.DeathRecipient {},
        )

    private fun result(into: MutableList<Bundle>): IAutoResult =
        object : IAutoResult.Stub() {
            override fun onResult(value: Bundle) {
                into += Bundle(value)
            }
        }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun await(check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            idleMain()
            if (check()) {
                return true
            }
            Thread.sleep(20)
        }
        idleMain()
        return check()
    }
}
