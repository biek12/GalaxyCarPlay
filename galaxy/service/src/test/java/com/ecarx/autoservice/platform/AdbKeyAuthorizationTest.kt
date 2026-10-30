package com.ecarx.autoservice.platform

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class AdbKeyAuthorizationTest {
    private val own = "A".repeat(700) + " autoservice@local"
    private fun fixture(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("adb-key-test").toFile()
        try { block(File(directory, "adb_keys")) } finally { directory.deleteRecursively() }
    }
    @Test fun appendPreservesOtherClientsAndBacksUpBeforeRestart() = fixture { file ->
        val original = "B".repeat(700) + " existing@client\n"
        file.writeText(original)
        var restarted = 0
        val result = AdbKeyAuthorization.install(own, file, {}, {
            assertEquals(original + own + "\n", file.readText())
            assertEquals(original, file.parentFile!!.listFiles()!!.single { it.name.startsWith("adb_keys.bak.") }.readText())
            ++restarted
        })
        assertEquals("authorized", result.getString("state")); assertEquals(1, restarted)
    }
    @Test fun existingKeyStillVerifiesMetadataAndDoesNotRestartHealthyConnection() = fixture { file ->
        file.writeText(own + "\n")
        var repaired = 0
        assertEquals("already_authorized", AdbKeyAuthorization.install(own, file, { ++repaired }, { fail() }).getString("state"))
        assertEquals(1, repaired); assertEquals(own + "\n", file.readText())
    }
    @Test fun rejectedMetadataRestoresOtherClientsAndNeverRestarts() = fixture { file ->
        val original = "previous key bytes\n"
        file.writeText(original)
        var rejected = false
        try {
            AdbKeyAuthorization.install(own, file, {
                if (it == file && !rejected) { rejected = true; error("mode_failed") }
            }, { fail("must not restart before readback") })
            fail()
        } catch (_: IllegalStateException) { assertEquals(original, file.readText()) }
    }
    @Test fun authorizationRecoveryCanReloadAlreadyPresentKeyWithoutDuplicatingIt() = fixture { file ->
        file.writeText(own + "\n")
        var restarted = 0
        AdbKeyAuthorization.install(own, file, {}, { ++restarted }, forceReload = true)
        assertEquals(1, restarted); assertEquals(own + "\n", file.readText())
    }
}
