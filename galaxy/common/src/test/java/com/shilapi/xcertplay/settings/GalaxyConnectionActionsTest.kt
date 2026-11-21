package com.shilapi.xcertplay.settings

import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 用记录下来的平台和会话边界核对连接顺序，不访问设备或网络。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GalaxyConnectionActionsTest {
    @Test
    fun usbWithoutSessionSavesTransportAfterChecksThenOpensProjection() {
        val trace = Trace()
        trace.actions.connect(wireless = false)
        assertEquals(
            listOf("reporting", "codec", "log:请求 USB 有线连接", "refresh", "save:false", "open"),
            trace.events,
        )
    }

    @Test
    fun manualGateRunsBeforeSavingAndPreparedRetryDoesNotAskAgain() {
        val trace = Trace()
        trace.actions.connect(wireless = true)
        assertEquals(listOf("reporting", "codec", "prerequisite", "gate"), trace.events)
        trace.ready!!.invoke()
        assertEquals(
            listOf(
                "reporting",
                "codec",
                "prerequisite",
                "gate",
                "clear-hotspot",
                "reporting",
                "codec",
                "prerequisite",
                "log:请求无线连接",
                "refresh",
                "save:true",
                "open",
            ),
            trace.events,
        )
        assertEquals(1, trace.events.count { it == "gate" })
    }

    @Test
    fun directModeSkipsHotspotGateAndUnavailableDirectStops() {
        val blocked = Trace()
        blocked.direct = true
        blocked.directReady = false
        blocked.actions.connect(wireless = true)
        assertEquals(listOf("reporting", "codec", "prerequisite", "direct"), blocked.events)
        val ready = Trace()
        ready.direct = true
        ready.actions.connect(wireless = true)
        assertFalse(ready.events.contains("gate"))
        assertTrue(ready.events.contains("save:true"))
        assertEquals(ready.events.last(), "open")
    }

    @Test
    fun notificationPromptContinuesForBothDenialAndGrant() {
        val denied = Trace()
        denied.notification = true
        denied.actions.connect(wireless = false)
        assertEquals(
            listOf("reporting", "codec", "log:请求 USB 有线连接", "notification-asked", "notification"),
            denied.events,
        )
        denied.notification = false
        denied.actions.connect(wireless = denied.actions.notificationTransport)
        assertTrue(denied.events.contains("save:false"))
        assertEquals("open", denied.events.last())
        val granted = Trace()
        granted.notification = true
        granted.actions.connect(wireless = false)
        granted.notification = false
        granted.actions.connect(wireless = granted.actions.notificationTransport)
        assertTrue(granted.events.contains("save:false"))
        assertEquals(1, granted.events.count { it == "notification" })
    }

    @Test
    fun sessionStopCompletesBeforeTransportIsSavedAndPageOpens() {
        val trace = Trace()
        trace.hasSession = true
        trace.actions.connect(wireless = false)
        assertTrue(trace.events.contains("stop"))
        assertFalse(trace.events.any { it.startsWith("save") || it == "open" })
        trace.finishStop()
        val stopAt = trace.events.indexOf("stop")
        val saveAt = trace.events.indexOf("save:false")
        val openAt = trace.events.indexOf("open")
        assertTrue(stopAt < saveAt && saveAt < openAt)
    }

    @Test
    fun repeatedConnectWhileStopIsPendingDoesNotStopTwice() {
        val trace = Trace()
        trace.hasSession = true
        trace.actions.connect(wireless = false)
        trace.actions.connect(wireless = false)
        assertEquals(1, trace.events.count { it == "stop" })
        assertFalse(trace.events.any { it.startsWith("save") })
    }

    @Test
    fun activeStopWaitsForConfirmAndWaitingStopCancelsImmediately() {
        val confirmed = Trace()
        confirmed.active = true
        confirmed.actions.stopFromHome()
        assertEquals(listOf("confirm"), confirmed.events)
        confirmed.confirm!!.invoke()
        assertTrue(confirmed.events.contains("disable"))
        assertTrue(confirmed.events.contains("stop"))
        val cancelled = Trace()
        cancelled.active = true
        cancelled.actions.stopFromHome()
        assertEquals(listOf("confirm"), cancelled.events)
        val waiting = Trace()
        waiting.hasSession = true
        waiting.actions.stopFromHome()
        assertFalse(waiting.events.contains("confirm"))
        assertEquals(listOf("disable", "stop"), waiting.events)
    }

    @Test
    fun abandonedAndDestroyedCallbacksDoNotSaveOrRepeatPrerequisites() {
        val stale = Trace()
        stale.hasSession = true
        stale.actions.connect(wireless = false)
        stale.actions.abandon()
        stale.finishStop()
        assertFalse(stale.events.any { it.startsWith("save") || it == "open" })
        val destroyed = Trace()
        destroyed.actions.connect(wireless = true)
        assertEquals(listOf("reporting", "codec", "prerequisite", "gate"), destroyed.events)
        destroyed.alive = false
        destroyed.ready!!.invoke()
        assertEquals(1, destroyed.events.count { it == "prerequisite" })
        assertFalse(destroyed.events.any { it.startsWith("save") || it == "open" })
    }

    @Test
    fun reconnectRejectsPendingStoppingAndMissingSessions() {
        val pending = Trace()
        pending.hasSession = true
        pending.actions.connect(wireless = false)
        pending.events.clear()
        pending.actions.reconnectCurrent()
        assertTrue(pending.events.isEmpty())
        val stopping = Trace()
        stopping.stopping = true
        stopping.actions.reconnectCurrent()
        assertTrue(stopping.events.isEmpty())
        val missing = Trace()
        missing.actions.reconnectCurrent()
        assertEquals(listOf("refresh", "problem:no-session"), missing.events)
        val closed = Trace()
        closed.reconnectable = false
        closed.wireless = null
        closed.actions.reconnectCurrent()
        assertEquals(listOf("refresh", "problem:no-session"), closed.events)
    }

    @Test
    fun reconnectUsesTheCurrentTransport() {
        val trace = Trace()
        trace.reconnectable = true
        trace.wireless = false
        trace.actions.reconnectCurrent()
        assertTrue(trace.events.first() == "log:手动重连当前会话 transport=WIRED")
        assertTrue(trace.events.contains("save:false"))
    }

    private class Trace {
        val events = mutableListOf<String>()
        var alive = true
        var direct = false
        var directReady = true
        var notification = false
        var hasSession = false
        var stopping = false
        var active = false
        var reconnectable = false
        var wireless: Boolean? = null
        var ready: (() -> Unit)? = null
        var confirm: (() -> Unit)? = null
        private val stops = mutableListOf<() -> Unit>()
        val actions = GalaxyConnectionActions(session(), platform())

        fun finishStop() {
            val pending = stops.toList()
            stops.clear()
            pending.forEach { it() }
        }

        private fun session() =
            GalaxyConnectionSession(
                hasSession = { hasSession },
                isStopping = { stopping },
                active = { active },
                reconnectable = { reconnectable && wireless != null },
                currentWireless = { wireless },
                stop = { completion ->
                    events += "stop"
                    stops += completion
                },
            )

        private fun platform() =
            GalaxyConnectionPlatform().apply {
                alive = { this@Trace.alive }
                requireAgreement = { true }
                canUse = { true }
                stopReporting = { events += "reporting" }
                stopCodecProbe = { events += "codec" }
                confirmAudio = { false }
                ensurePrerequisite = {
                    events += "prerequisite"
                    true
                }
                directMode = { direct }
                ensureDirect = {
                    events += "direct"
                    directReady
                }
                hotspotBusy = { false }
                problem =
                    { events += if (it == R.string.l7_reconnect_no_session) "problem:no-session" else "problem:$it" }
                problemText = { events += "problem-text" }
                setupError = { null }
                clearHotspotSetupIfValid = { events += "clear-hotspot" }
                startGate = { onReady, _ ->
                    events += "gate"
                    ready = onReady
                }
                hotspotSetupPending = { false }
                openWirelessPage = { events += "wireless-page" }
                markHotspotSetup = { events += "mark-hotspot" }
                hotspotInvalid = { false }
                carHotspotOff = { false }
                showHotspotOff = { events += "hotspot-off" }
                phoneMissing = { false }
                choosePhone = { events += "phone" }
                notificationNeeded = { notification }
                markNotificationAsked = { events += "notification-asked" }
                requestNotification = { events += "notification" }
                saveWireless = { wireless -> events += "save:$wireless" }
                openProjection = { events += "open" }
                refresh = { events += "refresh" }
                confirmDisconnect = { onConfirm ->
                    events += "confirm"
                    confirm = onConfirm
                }
                disableDisconnect = { events += "disable" }
                runOnMain = { action -> action() }
                log = { events += "log:$it" }
            }
    }
}
