package com.shilapi.xcertplay.steering

import org.junit.Assert.*
import org.junit.Test

/** 沿星枢识别与学习规则迁移的纯逻辑回归，不访问Android或车机服务。 */
class SteeringLearningEngineTest {
    private var now = 0L
    private val sent = mutableListOf<SteeringAction>()
    private fun engine(configuration: SteeringConfiguration = SteeringConfiguration()): SteeringLearningEngine =
        SteeringLearningEngine(configuration, { now }) { sent += it; true }.also {
            it.connection(SteeringConnection.REGISTERED, "registered")
        }
    private fun event(code: Int, edge: SteeringEdge, at: Long = now): SteeringKeyEvent {
        now = at
        return SteeringKeyEvent(code, edge, at)
    }
    private fun click(engine: SteeringLearningEngine, code: Int, at: Long) {
        engine.receive(event(code, SteeringEdge.PRESS, at))
        engine.receive(event(code, SteeringEdge.RELEASE, at + 20))
    }

    @Test fun unknownKeyIsObservedButNeverMappedOrDispatched() {
        val engine = engine()
        click(engine, 73, 0)
        now = 1_000
        engine.tick()
        assertEquals(2, engine.snapshot.recent.size)
        assertTrue(engine.snapshot.configuration.bindings.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test fun learningRequiresRegistrationAndCompleteReleaseBeforeConfirmation() {
        val engine = engine()
        engine.connection(SteeringConnection.CONNECTING, "connecting")
        assertFalse(engine.learn())
        engine.connection(SteeringConnection.REGISTERED, "registered")
        assertTrue(engine.learn())
        engine.receive(event(73, SteeringEdge.PRESS, 10))
        assertNull(engine.snapshot.learnedCode)
        assertTrue(engine.snapshot.learning)
        engine.receive(event(73, SteeringEdge.RELEASE, 30))
        assertEquals(73, engine.snapshot.learnedCode)
        assertFalse(engine.snapshot.learning)
        assertTrue(engine.snapshot.configuration.bindings.isEmpty())
        assertTrue(sent.isEmpty())
        val proposed = engine.proposed(SteeringGesture.CLICK, SteeringAction.NEXT)
        assertEquals(listOf(SteeringBinding(73, SteeringGesture.CLICK, SteeringAction.NEXT)), proposed.bindings)
        assertTrue(engine.snapshot.configuration.bindings.isEmpty())
        engine.configure(proposed)
        click(engine, 73, 500)
        now = 1_000
        engine.tick()
        assertEquals(listOf(SteeringAction.NEXT), sent)
    }

    @Test fun learningStrokeDoesNotExecuteAnAlreadySavedBinding() {
        val engine = engine(SteeringConfiguration(listOf(SteeringBinding(73, SteeringGesture.CLICK, SteeringAction.PLAY))))
        assertTrue(engine.learn())
        click(engine, 73, 0)
        now = 1_000
        engine.tick()
        assertTrue(sent.isEmpty())
        assertEquals(73, engine.snapshot.learnedCode)
    }

    @Test fun keyHeldBeforeLearningMustBeReleasedAndPressedAgain() {
        val engine = engine()
        engine.receive(event(73, SteeringEdge.PRESS, 0))
        assertTrue(engine.learn())
        engine.receive(event(73, SteeringEdge.RELEASE, 10))
        assertTrue(engine.snapshot.learning)
        assertNull(engine.snapshot.learnedCode)
        click(engine, 73, 100)
        assertEquals(73, engine.snapshot.learnedCode)
    }

    @Test fun cancelAndTimeoutDoNotCreateMappingOrTriggerLateRelease() {
        val engine = engine()
        engine.learn()
        engine.receive(event(73, SteeringEdge.PRESS, 10))
        engine.receive(event(73, SteeringEdge.CANCEL, 20))
        assertFalse(engine.snapshot.learning)
        assertNull(engine.snapshot.learnedCode)
        engine.learn()
        engine.receive(event(74, SteeringEdge.PRESS, 30))
        now = 15_100
        engine.tick()
        assertFalse(engine.snapshot.learning)
        engine.receive(event(74, SteeringEdge.RELEASE, 15_200))
        assertNull(engine.snapshot.learnedCode)
        assertTrue(sent.isEmpty())
    }

    @Test fun doubleClickWinsAndDoesNotAlsoFireSingleClick() {
        val engine = engine(SteeringConfiguration(listOf(
            SteeringBinding(73, SteeringGesture.CLICK, SteeringAction.PLAY),
            SteeringBinding(73, SteeringGesture.DOUBLE_CLICK, SteeringAction.PAUSE))))
        click(engine, 73, 0)
        click(engine, 73, 150)
        now = 1_000
        engine.tick()
        assertEquals(listOf(SteeringAction.PAUSE), sent)
    }

    @Test fun longPressFiresOnceDespiteRepeatedPressAndRelease() {
        val engine = engine(SteeringConfiguration(listOf(SteeringBinding(73, SteeringGesture.LONG_PRESS, SteeringAction.SIRI))))
        engine.receive(event(73, SteeringEdge.PRESS, 0))
        engine.receive(event(73, SteeringEdge.PRESS, 100))
        now = 800
        engine.tick()
        now = 1_000
        engine.tick()
        engine.receive(event(73, SteeringEdge.RELEASE, 1_200))
        assertEquals(listOf(SteeringAction.SIRI), sent)
    }

    @Test fun disconnectClearsPendingClickAndRejectsLateCallbacks() {
        val engine = engine(SteeringConfiguration(listOf(SteeringBinding(73, SteeringGesture.CLICK, SteeringAction.NEXT))))
        click(engine, 73, 0)
        engine.connection(SteeringConnection.FAILED, "rejected")
        now = 900
        engine.tick()
        click(engine, 73, 1_000)
        assertTrue(sent.isEmpty())
        assertTrue(engine.snapshot.recent.isEmpty())
    }

    @Test fun phoneOwnerReplacementClearsPendingClickAndHeldLongPress() {
        val engine = engine(SteeringConfiguration(listOf(
            SteeringBinding(73, SteeringGesture.CLICK, SteeringAction.NEXT),
            SteeringBinding(74, SteeringGesture.LONG_PRESS, SteeringAction.SIRI))))
        click(engine, 73, 0)
        engine.receive(event(74, SteeringEdge.PRESS, 30))
        engine.sessionChanged()
        now = 1_000
        engine.tick()
        engine.receive(event(74, SteeringEdge.RELEASE, 1_100))
        assertTrue(sent.isEmpty())
        click(engine, 73, 1_200)
        now = 1_800
        engine.tick()
        assertEquals(listOf(SteeringAction.NEXT), sent)
    }

    @Test fun closeIsIdempotentAndRejectsTicksEventsAndLateConnection() {
        val engine = engine(SteeringConfiguration(listOf(SteeringBinding(73, SteeringGesture.CLICK, SteeringAction.NEXT))))
        click(engine, 73, 0)
        engine.close()
        engine.close()
        now = 1_000
        engine.tick()
        engine.connection(SteeringConnection.REGISTERED, "late")
        click(engine, 73, 2_000)
        assertEquals(SteeringConnection.STOPPED, engine.snapshot.connection)
        assertFalse(engine.learn())
        assertTrue(sent.isEmpty())
    }

    @Test fun observationHistoryIsBoundedAndDisabledBindingsNeverExecute() {
        val engine = engine(SteeringConfiguration(listOf(SteeringBinding(73, SteeringGesture.CLICK, SteeringAction.NEXT))))
        engine.setEnabled(false)
        repeat(40) { index -> click(engine, 73, index * 500L) }
        now += 1_000
        engine.tick()
        assertEquals(12, engine.snapshot.recent.size)
        assertTrue(sent.isEmpty())
    }

    @Test fun configurationRejectsDuplicateAndRawEdgeMappings() {
        val binding = SteeringBinding(73, SteeringGesture.CLICK, SteeringAction.NEXT)
        assertThrows(IllegalArgumentException::class.java) { SteeringConfiguration(listOf(binding, binding)).validated() }
        assertThrows(IllegalArgumentException::class.java) {
            SteeringConfiguration(listOf(binding.copy(gesture = SteeringGesture.PRESS))).validated()
        }
        assertEquals(7, SteeringAction.entries.size)
    }

    @Test fun originalCrossOriginDeduplicatorPreservesRapidSameSourceCommands() {
        val gate = SteeringMediaCommandDeduplicator()
        assertTrue(gate.accept(SteeringAction.NEXT, "autolink", 0))
        assertTrue(gate.accept(SteeringAction.NEXT, "autolink", 100))
        assertFalse(gate.accept(SteeringAction.NEXT, "android", 150))
        assertTrue(gate.accept(SteeringAction.NEXT, "android", 500))
    }
}
