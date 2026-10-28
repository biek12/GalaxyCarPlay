package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.steering.GalaxySteeringMediaDispatch
import com.shilapi.xcertplay.steering.GalaxySteeringMediaWindow
import com.shilapi.xcertplay.steering.SteeringAction
import com.shilapi.xcertplay.steering.SteeringActionDispatch
import com.shilapi.xcertplay.steering.SteeringBinding
import com.shilapi.xcertplay.steering.SteeringConfiguration
import com.shilapi.xcertplay.steering.SteeringConnection
import com.shilapi.xcertplay.steering.SteeringEdge
import com.shilapi.xcertplay.steering.SteeringGesture
import com.shilapi.xcertplay.steering.SteeringKeyEvent
import com.shilapi.xcertplay.steering.SteeringLearningEngine
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 方控运行时出口回归：按键→CarPlay动作的七个映射、跨源250ms去重、蓝牙等待闸门之后的
 * 真实返回值，以及关闭/会话更替后不再派发。控制器是mock，不连接车机或手机。
 *
 * 断言只用具体实参的 verify：`any()`/`eq()` 会给 Kotlin 非空参数传null，
 * 在 `sendMediaButton(index, owner)` 这种签名上会直接抛NPE而不是完成匹配。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GalaxySteeringCommandTest {
    private var now = 0L
    private val owner: Any = Any()
    private lateinit var controller: CarPlayController
    private lateinit var media: GalaxySteeringMediaDispatch
    private val mediaIndices = listOf(CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE,
        CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS)

    @Before fun storeSession() {
        GalaxySteeringMediaWindow.deduplicator.clear()
        controller = mock(CarPlayController::class.java).also {
            `when`(it.activeMediaSessionOwner()).thenReturn(owner)
            `when`(it.hasActiveSession()).thenReturn(true)
            `when`(it.isClosed()).thenReturn(false)
            listOf(CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE, CarPlayMediaButton.PREVIOUS,
                CarPlayMediaButton.NEXT).forEach { index -> doReturn(true).`when`(it).sendMediaButton(index, owner) }
            `when`(it.requestSiri()).thenReturn(true)
            `when`(it.answerCall()).thenReturn(true)
            `when`(it.endCall()).thenReturn(true)
        }
        media = GalaxySteeringMediaDispatch(controller, GalaxySteeringMediaWindow.deduplicator) { now }
        // 媒体动作要经上游蓝牙等待闸门，因此必须有当前会话快照（真实运行时由sessionStored建立）。
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 10, 10, Any()) {}
    }

    @After fun clearSession() {
        if (::media.isInitialized) media.close()
        CarPlayBackgroundSession.clear()
        GalaxySteeringMediaWindow.deduplicator.clear()
    }

    private fun engine(configuration: SteeringConfiguration): SteeringLearningEngine =
        SteeringLearningEngine(configuration, { now }) { action ->
            SteeringActionDispatch.dispatch(controller, owner, media::send, action)
        }.also { it.connection(SteeringConnection.REGISTERED, "registered") }

    private fun click(engine: SteeringLearningEngine, code: Int, at: Long) {
        now = at
        engine.receive(SteeringKeyEvent(code, SteeringEdge.PRESS, at))
        engine.receive(SteeringKeyEvent(code, SteeringEdge.RELEASE, at + 20))
        now = at + 500
        engine.tick()
    }

    /** 断言没有任何媒体/Siri/电话动作到达控制器（逐个索引验证，避免给非空参数传匹配器）。 */
    private fun assertNothingDispatched() {
        mediaIndices.forEach { index ->
            verify(controller, never()).sendMediaButton(index, owner)
            verify(controller, never()).sendMediaButton(index)
        }
        verify(controller, never()).requestSiri()
        verify(controller, never()).answerCall()
        verify(controller, never()).endCall()
    }

    @Test fun sevenLearnedActionsReachTheMatchingCarPlayEntry() {
        val bindings = listOf(
            SteeringBinding(71, SteeringGesture.CLICK, SteeringAction.PLAY),
            SteeringBinding(72, SteeringGesture.CLICK, SteeringAction.PAUSE),
            SteeringBinding(73, SteeringGesture.CLICK, SteeringAction.PREVIOUS),
            SteeringBinding(74, SteeringGesture.CLICK, SteeringAction.NEXT),
            SteeringBinding(75, SteeringGesture.CLICK, SteeringAction.SIRI),
            SteeringBinding(76, SteeringGesture.CLICK, SteeringAction.ANSWER_CALL),
            SteeringBinding(77, SteeringGesture.CLICK, SteeringAction.END_CALL),
        )
        val engine = engine(SteeringConfiguration(bindings))
        bindings.forEachIndexed { index, binding -> click(engine, binding.keyCode, index * 1_000L) }

        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY, owner)
        verify(controller).sendMediaButton(CarPlayMediaButton.PAUSE, owner)
        verify(controller).sendMediaButton(CarPlayMediaButton.PREVIOUS, owner)
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT, owner)
        verify(controller, times(1)).requestSiri()
        verify(controller, times(1)).answerCall()
        verify(controller, times(1)).endCall()
        assertEquals(true, engine.snapshot.lastAccepted)
    }

    @Test fun mediaResultOnlyReportsCommandsThatWereActuallyHandedToThePhone() {
        doReturn(false).`when`(controller).sendMediaButton(CarPlayMediaButton.NEXT, owner)
        val engine = engine(SteeringConfiguration(listOf(
            SteeringBinding(74, SteeringGesture.CLICK, SteeringAction.NEXT))))
        click(engine, 74, 0)
        // 控制器拒绝（旧会话/事件通道不可用）时学习页显示的状态必须是“未生效”。
        assertEquals(false, engine.snapshot.lastAccepted)
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT, owner)
    }

    @Test fun crossSourceDuplicateInside250msIsDroppedForBothDirections() {
        // 原车媒体中心先回送一次下一曲（真实来源由 GalaxyMediaKeys 在接入口记录）。
        assertTrue(GalaxySteeringMediaWindow.deduplicator.accept(
            SteeringAction.NEXT, GalaxySteeringMediaWindow.VEHICLE, 1_000))
        now = 1_100
        assertFalse(media.send(CarPlayMediaButton.NEXT))
        verify(controller, never()).sendMediaButton(CarPlayMediaButton.NEXT, owner)
        // 窗口过后同一动作重新放行。
        now = 1_400
        assertTrue(media.send(CarPlayMediaButton.NEXT))
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT, owner)
        // 反向：方控刚发出后，原车入口的同动作在窗口内被抑制。
        var vehicleSent = false
        now = 1_500
        assertFalse(GalaxySteeringMediaWindow.acceptVehicleMedia(CarPlayMediaButton.NEXT, { now }) {
            vehicleSent = true; true
        })
        assertFalse(vehicleSent)
        now = 2_000
        assertTrue(GalaxySteeringMediaWindow.acceptVehicleMedia(CarPlayMediaButton.NEXT, { now }) {
            vehicleSent = true; true
        })
        assertTrue(vehicleSent)
    }

    @Test fun rebuiltPhoneSessionStopsSendingToTheOldOwner() {
        val engine = engine(SteeringConfiguration(listOf(
            SteeringBinding(74, SteeringGesture.CLICK, SteeringAction.NEXT))))
        // 手机重连后 owner 变了：派发必须丢弃，不能把旧动作打到新会话。
        `when`(controller.activeMediaSessionOwner()).thenReturn(Any())
        click(engine, 74, 0)
        assertEquals(false, engine.snapshot.lastAccepted)
        assertNothingDispatched()
    }

    @Test fun closedControllerAndClosedDispatcherDoNotDispatch() {
        now = 0
        `when`(controller.isClosed()).thenReturn(true)
        assertFalse(media.send(CarPlayMediaButton.PLAY))
        `when`(controller.isClosed()).thenReturn(false)
        media.close()
        assertFalse(media.send(CarPlayMediaButton.PLAY))
        assertNothingDispatched()
    }

    @Test fun emptySessionSnapshotDropsMediaInsteadOfPretendingToSend() {
        CarPlayBackgroundSession.clear()
        assertFalse(media.send(CarPlayMediaButton.PLAY))
        assertNothingDispatched()
    }

    @Test fun unknownMediaIndexIsNotForwarded() {
        assertFalse(media.send(CarPlayMediaButton.PLAY_PAUSE))
        assertNothingDispatched()
    }
}