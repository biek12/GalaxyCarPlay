package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 产品后台会话沿用新版显示快照与回收所有权，不连接手机或OEM服务。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyHostSessionTest {
    private fun display() = CarPlaySessionDisplay(1440, 1800, 0, false, true, 1440, 1800,
        statusBarShown = true)

    @After fun reset() {
        CarPlayBackgroundSession.clear()
    }

    @Test fun snapshotKeepsNewStatusBarStateAndOwnerWithoutAnotherSession() {
        val controller = mock(CarPlayController::class.java)
        val sink = mock(AndroidMediaSink::class.java)
        val owner = Any()
        val display = display()
        CarPlayBackgroundSession.store(controller, sink, 1440, 1800, owner, display) { it() }
        CarPlayBackgroundSession.active = true
        CarPlayBackgroundSession.callPopupReady = true

        val snapshot = requireNotNull(CarPlayBackgroundSession.snapshot())
        assertSame(controller, snapshot.controller)
        assertSame(sink, snapshot.sink)
        assertSame(display, snapshot.display)
        assertEquals(true, snapshot.display.statusBarShown)
        assertTrue(CarPlayBackgroundSession.isOwner(owner))
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertFalse(CarPlayBackgroundSession.isStopping())
    }

    @Test fun staleClearCannotDropReplacementControllerOrCallPopupReadiness() {
        val previous = mock(CarPlayController::class.java)
        val current = mock(CarPlayController::class.java)
        val sink = mock(AndroidMediaSink::class.java)
        val owner = Any()
        CarPlayBackgroundSession.store(previous, sink, 1440, 1800, owner, display()) { it() }
        CarPlayBackgroundSession.callPopupReady = true
        CarPlayBackgroundSession.store(current, sink, 1440, 1800, owner, display()) { it() }
        assertFalse(CarPlayBackgroundSession.callPopupReady)
        CarPlayBackgroundSession.callPopupReady = true
        CarPlayBackgroundSession.clear(previous)

        assertSame(current, CarPlayBackgroundSession.snapshot()?.controller)
        assertTrue(CarPlayBackgroundSession.callPopupReady)
        assertTrue(CarPlayBackgroundSession.isOwner(owner))
    }

    @Test fun adoptingSameControllerRetainsCallPopupReadiness() {
        val controller = mock(CarPlayController::class.java)
        val sink = mock(AndroidMediaSink::class.java)
        CarPlayBackgroundSession.store(controller, sink, 1440, 1800, Any(), display()) { it() }
        CarPlayBackgroundSession.callPopupReady = true
        val adoptedOwner = Any()
        CarPlayBackgroundSession.store(controller, sink, 1440, 1800, adoptedOwner, display()) { it() }
        assertTrue(CarPlayBackgroundSession.callPopupReady)
        assertTrue(CarPlayBackgroundSession.isOwner(adoptedOwner))
    }

    @Test fun twoStopRequestsShareReleaseAndCompleteOnlyAfterOwnerClosed() {
        val controller = mock(CarPlayController::class.java)
        val sink = mock(AndroidMediaSink::class.java)
        var requests = 0
        var completions = 0
        var release: (() -> Unit)? = null
        CarPlayBackgroundSession.store(controller, sink, 1440, 1800, Any(), display()) {
            requests++
            release = it
        }
        CarPlayBackgroundSession.stop { completions++ }
        CarPlayBackgroundSession.stop { completions++ }
        assertEquals(1, requests)
        assertEquals(0, completions)
        assertTrue(CarPlayBackgroundSession.isStopping())
        CarPlayBackgroundSession.clear(controller)
        requireNotNull(release).invoke()
        assertEquals(2, completions)
        assertFalse(CarPlayBackgroundSession.isStopping())
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertNull(CarPlayBackgroundSession.snapshot())
    }

    @Test fun originalCarOverlayOnlyHidesStatusBarWithoutChangingSessionCanvas() {
        val display = display()
        assertTrue(statusBarHiddenByAnotherWindow(display, 1440, 1850, false))
        assertFalse(statusBarHiddenByAnotherWindow(display, 1440, 1850, true))
        assertFalse(statusBarHiddenByAnotherWindow(display, 1000, 1850, false))
        assertFalse(statusBarHiddenByAnotherWindow(display, 1440, 1800, false))
    }
}
