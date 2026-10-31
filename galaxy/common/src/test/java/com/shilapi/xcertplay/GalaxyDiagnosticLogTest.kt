package com.shilapi.xcertplay

import com.shilapi.xcertplay.diagnostics.DiagnosticChannel
import com.shilapi.xcertplay.diagnostics.DiagnosticEvent
import com.shilapi.xcertplay.diagnostics.DiagnosticSink
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** 只使用临时合成内容，不读取实际日志、身份或上传端点。 */
class GalaxyDiagnosticLogTest {
    @Test fun structuredMetadataSurvivesWhileRawPhoneAndCredentialLinesRemainRejected() {
        val line = "CORE_TRACE session=42 seq=1 elapsedMs=2 component=VIDEO kind=START state=REQUESTED"
        assertEquals(line, DiagnosticRedactor.redact(line))
        for (raw in listOf("TRACE IAP2 tx data", "PHONE packet", "ok\nprivate", "certificate bytes=12",
            "token=synthetic-marker", "payload=synthetic-marker", "-----BEGIN PRIVATE KEY-----")) {
            assertNull(raw, DiagnosticRedactor.redact(raw))
        }
        assertTrue(DiagnosticRedactor.redact("x".repeat(900))!!.length <= DiagnosticRedactor.MAX_LINE)
    }

    @Test fun customArchivesRotateWithinUtf8ByteBudgetAndClosedTargetRejectsLateLines() {
        val folder = Files.createTempDirectory("galaxy-diagnostic-rotation").toFile()
        val log = SessionLogFile(File(folder, "debug.log"), listOf("debug-previous.log", "debug-previous-2.log"))
        try {
            log.reset("session=first")
            repeat(550) { log.append("stage=音频 count=$it " + "界".repeat(650)) }
            log.append("state=ended")
            log.close()
            log.append("state=late")
            assertTrue(File(folder, "debug-previous.log").isFile)
            assertTrue(folder.listFiles()!!.all { it.length() <= SessionLogFile.MAX_BYTES })
            assertTrue(log.file.readText().contains("state=ended"))
            assertFalse(log.file.readText().contains("state=late"))
            assertFalse(File(folder, "previous.log").exists())
        } finally { log.close(); folder.deleteRecursively() }
    }

    @Test fun archiveNamesCannotEscapeTheOwnedLogDirectoryOrOverwriteCurrentFile() {
        val folder = Files.createTempDirectory("galaxy-diagnostic-archive-names").toFile()
        try {
            for (names in listOf(emptyList(), listOf("../elsewhere.log"), listOf("debug.log"),
                listOf("same.log", "same.log"))) {
                assertTrue(runCatching { SessionLogFile(File(folder, "debug.log"), names) }.isFailure)
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun clearInvalidatesAlreadyQueuedGenerationWithoutChangingTheCapturedTarget() {
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        val folder = Files.createTempDirectory("galaxy-diagnostic-generation").toFile()
        val old = SessionLogFile(File(folder, "debug.log"))
        val current = SessionLogFile(File(folder, "diplay.log"))
        try {
            synchronized(SessionLogFile.storageLock) {
                AsyncDiagnosticLog.append(old, "stage=before-clear", 0)
                AsyncDiagnosticLog.clearGalaxy(folder)
            }
            assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
            assertFalse(old.file.exists())
            AsyncDiagnosticLog.append(current, "stage=after-clear", 1)
            assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
            assertTrue(current.file.readText().contains("stage=after-clear"))
            assertFalse(current.file.readText().contains("before-clear"))
            assertFalse(old.file.exists())
            old.close()
            AsyncDiagnosticLog.append(old, "stage=closed-target", 2)
            assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
            assertFalse(old.file.exists())
        } finally { old.close(); current.close(); folder.deleteRecursively() }
    }

    @Test fun defaultClearKeepsOnlyOfficialInventoryAndLeavesUnregisteredProductFilesAlone() {
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        val folder = Files.createTempDirectory("galaxy-diagnostic-core-inventory").toFile()
        try {
            assertEquals((7 downTo 2).map { "previous-$it.log" } + listOf("previous.log", "diplay.log"),
                SessionLogFile.REPORT_NAMES)
            assertEquals(8, SessionLogFile.REPORT_NAMES.size)
            File(folder, "diplay.log").writeText("synthetic connection")
            File(folder, "previous.log").writeText("synthetic archive")
            File(folder, "debug.log").writeText("synthetic product")
            File(folder, "unregistered.log").writeText("synthetic unrelated")
            AsyncDiagnosticLog.clear(folder)
            assertEquals("", File(folder, "diplay.log").readText())
            assertFalse(File(folder, "previous.log").exists())
            assertEquals("synthetic product", File(folder, "debug.log").readText())
            assertEquals("synthetic unrelated", File(folder, "unregistered.log").readText())
            AsyncDiagnosticLog.clearGalaxy(folder)
            assertFalse(File(folder, "debug.log").exists())
            assertEquals("synthetic unrelated", File(folder, "unregistered.log").readText())
            assertEquals(17, GalaxyDiagnosticFiles.reportNames.distinct().size)
        } finally { folder.deleteRecursively() }
    }

    @Test fun directFileQueueCannotRestoreClearedOrPreviousSessionLines() {
        val folder = Files.createTempDirectory("galaxy-direct-log-generation").toFile()
        val log = SessionLogFile(File(folder, "debug.log"))
        try {
            synchronized(SessionLogFile.storageLock) {
                log.append("stage=before-clear")
                AsyncDiagnosticLog.clearGalaxy(folder)
            }
            assertTrue(log.awaitIdle(2_000))
            assertFalse(log.file.exists())
            synchronized(SessionLogFile.storageLock) {
                log.append("stage=before-reset")
                log.reset("session=current")
                log.append("stage=current")
            }
            assertTrue(log.awaitIdle(2_000))
            val content = log.file.readText()
            assertTrue(content.contains("session=current"))
            assertTrue(content.contains("stage=current"))
            assertFalse(content.contains("before-clear"))
            assertFalse(content.contains("before-reset"))
        } finally { log.close(); folder.deleteRecursively() }
    }

    @Test fun clearRejectsNonLeafNamesBeforeTouchingAnyOwnedFile() {
        val folder = Files.createTempDirectory("galaxy-diagnostic-clear-input").toFile()
        try {
            val current = File(folder, "diplay.log").apply { writeText("keep current") }
            val extra = File(folder, "registered.log").apply { writeText("keep registered") }
            val invalid = listOf("", " ", ".", "..", "../escape.log", "nested/file.log", "nested\\file.log", "/absolute.log")
            for (name in invalid) {
                assertTrue(name, runCatching { AsyncDiagnosticLog.clear(folder, listOf("registered.log", name)) }.isFailure)
                assertEquals("keep current", current.readText())
                assertEquals("keep registered", extra.readText())
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun clearDoesNotFollowRegisteredSymbolicLinks() {
        val folder = Files.createTempDirectory("galaxy-diagnostic-clear-symlink").toFile()
        try {
            val logs = File(folder, "logs").apply { mkdir() }
            val outside = File(folder, "outside.log").apply { writeText("keep outside") }
            val current = File(logs, "diplay.log").apply { writeText("keep current") }
            Files.createSymbolicLink(File(logs, "registered.log").toPath(), outside.toPath())
            assertTrue(runCatching { AsyncDiagnosticLog.clear(logs, listOf("registered.log")) }.isFailure)
            assertEquals("keep outside", outside.readText())
            assertEquals("keep current", current.readText())
        } finally { folder.deleteRecursively() }
    }

    @Test fun injectedContextIsCapturedByTheSingleQueueAndItsHeaderSurvivesRotation() {
        val folder = Files.createTempDirectory("galaxy-diagnostic-context").toFile()
        val log = SessionLogFile(File(folder, "diplay.log"))
        val first = object : DiagnosticLogContext {
            override val id = "cfg_first"
            override val header = "CONFIG_HEADER first=true"
        }
        val next = object : DiagnosticLogContext {
            override val id = "cfg_next"
            override val header = "CONFIG_HEADER next=true"
        }
        try {
            synchronized(SessionLogFile.storageLock) {
                log.context = first
                AsyncDiagnosticLog.append(log, "stage=captured", 0, eventPrefix = "diag_event=synthetic event_ms=0 ")
                log.context = next
            }
            assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
            assertTrue(log.file.readText().contains(first.header))
            assertFalse(log.file.readText().contains(next.header))
            repeat(800) { log.append("stage=rotate count=$it " + "界".repeat(650), first) }
            assertTrue(log.awaitIdle(10_000))
            assertTrue(log.file.length() <= SessionLogFile.MAX_BYTES)
            assertEquals(first.header, log.file.readLines().first())
            assertTrue(File(folder, "previous.log").isFile)
        } finally { log.close(); folder.deleteRecursively() }
    }

    @Test fun injectedEventPrefixCannotBypassCredentialFiltering() {
        val folder = Files.createTempDirectory("galaxy-diagnostic-prefix").toFile()
        val log = SessionLogFile(File(folder, "diplay.log"))
        try {
            AsyncDiagnosticLog.append(log, "stage=ok", 0, eventPrefix = "token=synthetic-marker ")
            assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
            assertFalse(log.file.exists())
        } finally { log.close(); folder.deleteRecursively() }
    }

    @Test fun structuredChannelStopsBusinessCallbacksButAllowsFinalReleaseBeforeClose() {
        val events = mutableListOf<DiagnosticEvent>()
        val channel = DiagnosticChannel(42, DiagnosticSink(events::add)) { 0 }
        channel.emit(DiagnosticEvent.Component.VIDEO, DiagnosticEvent.Kind.START, DiagnosticEvent.State.REQUESTED)
        channel.stopRequested()
        channel.emit(DiagnosticEvent.Component.VIDEO, DiagnosticEvent.Kind.STATE, DiagnosticEvent.State.RUNNING)
        channel.emit(DiagnosticEvent.Component.VIDEO, DiagnosticEvent.Kind.RELEASE, DiagnosticEvent.State.ENDED)
        channel.close()
        channel.emit(DiagnosticEvent.Component.VIDEO, DiagnosticEvent.Kind.RELEASE, DiagnosticEvent.State.ENDED)
        assertEquals(3, events.size)
        assertEquals(DiagnosticEvent.Kind.STOP, events[1].kind)
        assertEquals(DiagnosticEvent.Kind.RELEASE, events[2].kind)
        assertEquals(listOf(1L, 2L, 3L), events.map { it.sequence })
    }

    @Test(timeout = 5_000) fun queueRemainsBoundedAndCloseDoesNotWaitForBlockedStorage() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = mutableListOf<Int>()
        val writer = BoundedDiagnosticWriter<Int>(2) { value ->
            if (value == -1) { entered.countDown(); release.await() }
            written.add(value)
        }
        try {
            writer.enqueue(-1)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(5) { writer.enqueue(it) }
            assertEquals(2, writer.pendingCount)
            assertEquals(3L, writer.droppedCount)
            writer.close()
            assertFalse(writer.enqueue(9))
            release.countDown()
            assertTrue(writer.awaitIdle(2_000))
            assertEquals(listOf(-1, 3, 4), written)
        } finally { release.countDown(); writer.close() }
    }
}
