package com.ecarx.autoservice.platform

import org.junit.Assert.*
import org.junit.Test

class QnxReceiverLifecycleTest {
    private class Vehicle(var installed: Boolean, var listening: Boolean) : QnxReceiverLifecycle.Port {
        val calls = mutableListOf<String>()
        var foreignPid = false
        var checksumFailure = false
        override fun inspect(): QnxReceiverLifecycle.State {
            calls.add("inspect")
            return when { !installed -> QnxReceiverLifecycle.State.NEEDS_INSTALL
                listening -> QnxReceiverLifecycle.State.LISTENING
                else -> QnxReceiverLifecycle.State.INSTALLED }
        }
        override fun stopOwned() { calls.add(if (foreignPid) "discardStalePid" else "stopOwned"); listening = false }
        override fun install() { calls.add("install"); installed = true }
        override fun verify() { calls.add("verify"); check(!checksumFailure) { "checksum_failed" } }
        override fun start() { calls.add("start"); check(installed); listening = true }
    }
    @Test fun stoppedReceiverIsWokenFromExistingInstallationOnProjectionDemand() {
        val vehicle = Vehicle(installed = true, listening = false)
        assertEquals(QnxReceiverLifecycle.Action.WOKEN, QnxReceiverLifecycle(vehicle).ensureRunning())
        assertEquals(listOf("inspect", "stopOwned", "verify", "start"), vehicle.calls)
        assertTrue(vehicle.listening)
    }
    @Test fun qnxRebootAndLaterIdleExitBothWakeWithoutReinstalling() {
        val vehicle = Vehicle(installed = true, listening = false)
        val lifecycle = QnxReceiverLifecycle(vehicle)
        assertEquals(QnxReceiverLifecycle.Action.WOKEN, lifecycle.ensureRunning())
        vehicle.listening = false // 原有有限监听退出；新需求必须再次拉起。
        assertEquals(QnxReceiverLifecycle.Action.WOKEN, lifecycle.ensureRunning())
        assertEquals(2, vehicle.calls.count { it == "start" })
        assertFalse(vehicle.calls.contains("install"))
    }
    @Test fun firstInstallationPreparesProgramBeforeStartingAndNextDemandReusesIt() {
        val vehicle = Vehicle(installed = false, listening = false)
        val lifecycle = QnxReceiverLifecycle(vehicle)
        assertEquals(QnxReceiverLifecycle.Action.INSTALLED, lifecycle.ensureRunning())
        assertEquals(listOf("inspect", "stopOwned", "install", "verify", "start"), vehicle.calls)
        vehicle.calls.clear()
        assertEquals(QnxReceiverLifecycle.Action.REUSED, lifecycle.ensureRunning())
        assertEquals(listOf("inspect"), vehicle.calls)
    }
    @Test fun pidReusedAfterRebootDiscardsOnlyOurStaleRecordAndStillWakesReceiver() {
        val vehicle = Vehicle(installed = true, listening = false).apply { foreignPid = true }
        assertEquals(QnxReceiverLifecycle.Action.WOKEN, QnxReceiverLifecycle(vehicle).ensureRunning())
        assertEquals(listOf("inspect", "discardStalePid", "verify", "start"), vehicle.calls)
        assertFalse(vehicle.calls.contains("stopOwned"))
        assertTrue(vehicle.listening)
    }
    @Test fun invalidInstalledProgramCannotBeReportedRunning() {
        val vehicle = Vehicle(installed = true, listening = false).apply { checksumFailure = true }
        try { QnxReceiverLifecycle(vehicle).ensureRunning(); fail() }
        catch (error: IllegalStateException) { assertEquals("checksum_failed", error.message) }
        assertFalse(vehicle.listening)
        assertFalse(vehicle.calls.contains("start"))
    }
}
