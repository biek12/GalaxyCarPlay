package com.ecarx.autoservice

import com.ecarx.autoservice.platform.QnxClusterRemote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

/**
 * 原厂 Hook 远端进程的四条生命周期。
 *
 * 假执行器说的是现行 [QnxClusterRemote] 令牌：版本匹配为
 * `AS_CLUSTER_VERSION_MATCH`，材料匹配为 `AS_CLUSTER_FILE_MATCH`，
 * 存活为 `AS_CLUSTER_LIVE`。`stop()` 看到唯一原厂进程且库清单含 PIE、
 * 不含自有 so 时立即回 `AS_CLUSTER_RESTORED`，测试不会走进 15 秒重试。
 * 不改生产命令和超时。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ClusterHookLifecycleTest {
    private val assets =
        mapOf(
            "g733_hook_runtime.sh" to "script".toByteArray(),
            "health" to "health".toByteArray(),
        )

    /** 按现行命令形状回答；激活命令单独记为 launched。 */
    private class HookCommands(
        private val installed: Boolean,
        private val filesMatch: Boolean = true,
        private val live: Boolean = false,
        private val inspect: (String) -> String = { "ok\n" },
        private val onCommand: (String) -> Unit = {},
    ) {
        val commands = mutableListOf<String>()
        var launched = false
            private set

        fun execute(command: String): String {
            commands += command
            onCommand(command)
            return when {
                command.startsWith("if [ -r ") ->
                    if (installed) "AS_CLUSTER_VERSION_MATCH\n" else "AS_CLUSTER_INSTALL_REQUIRED\n"
                command.contains("echo AS_CLUSTER_FILE_MATCH") ->
                    if (filesMatch) "AS_CLUSTER_FILE_MATCH\n" else "AS_CLUSTER_FILE_MISMATCH\n"
                command.contains("pidin -F") -> "2\n"
                command.contains("pidin -p ") -> "PIE\n"
                command.contains("AS_CLUSTER_RESTORED") -> "AS_CLUSTER_RESTORED\n"
                command.contains("echo AS_CLUSTER_LIVE") ->
                    if (live) "AS_CLUSTER_LIVE\n" else "AS_CLUSTER_HEALTH_PENDING\n"
                command.contains("activate --authorized-runtime") -> {
                    launched = true
                    "G733_RUNTIME_UI_HEARTBEAT test-ticket\n"
                }
                command.endsWith(" inspect") -> inspect(command)
                else -> "ok\n"
            }
        }
    }

    @Test fun matchingLiveProcessReusesWithoutRestartOrStage() {
        val root = Files.createTempDirectory("cluster-reuse").toFile()
        val commands = HookCommands(installed = true, live = true)
        QnxClusterRemote(commands::execute, assets, "version", { true }, root).use {
            assertTrue(it.ensure(1).getBoolean("reused"))
        }
        assertFalse(commands.commands.any { it.contains("kill -") || it.contains("activate --") })
        assertFalse(commands.launched)
        assertEquals(0, root.listFiles()!!.size)
        root.delete()
    }

    @Test fun firstStartStagesVerifiesAndOnlyThenActivates() {
        val root = Files.createTempDirectory("cluster-install").toFile()
        val commands = HookCommands(installed = false)
        QnxClusterRemote(commands::execute, assets, "version", { true }, root).use {
            assertFalse(it.ensure(2).getBoolean("reused"))
        }
        val copy = commands.commands.indexOfFirst { it.contains("cp /otaupdate/") }
        val verify = commands.commands.indexOfLast { it.endsWith(" inspect") }
        val start = commands.commands.indexOfFirst { it.contains("activate --authorized-runtime") }
        assertTrue(copy >= 0 && verify > copy && start > verify)
        assertTrue(commands.launched)
        assertEquals(0, root.listFiles()!!.size)
        root.delete()
    }

    @Test fun lateCancelledLeaseCannotActivateAfterDeployment() {
        var active = true
        val root = Files.createTempDirectory("cluster-cancel").toFile()
        val commands =
            HookCommands(installed = false, onCommand = { command ->
                if (command.contains("cp /otaupdate/")) active = false
            })
        val remote = QnxClusterRemote(commands::execute, assets, "version", { active }, root)
        remote.use {
            val error = assertThrows(IllegalStateException::class.java) { it.ensure(3) }
            assertEquals("cluster_lease_inactive", error.message)
        }
        assertFalse(commands.launched)
        assertEquals(0, root.listFiles()!!.size)
        root.delete()
    }

    @Test fun fixedFirmwareMismatchCannotActivate() {
        val commands =
            HookCommands(installed = true, inspect = { error("firmware_mismatch") })
        QnxClusterRemote(commands::execute, assets, "version", { true }).use {
            val error = assertThrows(IllegalStateException::class.java) { it.ensure(4) }
            assertEquals("firmware_mismatch", error.message)
        }
        assertFalse(commands.launched)
    }
}
