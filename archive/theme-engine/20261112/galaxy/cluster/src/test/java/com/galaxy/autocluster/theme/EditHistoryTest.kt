package com.galaxy.autocluster.theme

import org.junit.Assert.*
import org.junit.Test

class EditHistoryTest {
    @Test fun oneDragIsOneUndoStepAndSavedStateIsIndependent() {
        val history = EditHistory(0, { it })
        history.beginGesture(); repeat(200) { history.change(it + 1) }; history.finishGesture()
        assertEquals(200, history.value()); assertTrue(history.dirty())
        history.undo(); assertEquals(0, history.value()); assertFalse(history.canUndo())
        history.redo(); history.markSaved(); assertFalse(history.dirty())
        history.change(300); history.undo(); assertFalse(history.dirty())
    }
    @Test fun cancelledDragPreservesRedoAndDoesNotDirtyTheDocument() {
        val history = EditHistory(0, { it })
        history.change(2); history.undo()
        history.beginGesture(); history.change(3); history.cancelGesture()
        assertFalse(history.dirty()); assertTrue(history.canRedo())
        history.redo(); assertEquals(2, history.value())
    }
    @Test fun mutationAtCallerCannotRewriteHistoryAndHistoryIsBounded() {
        data class Value(var x: Int)
        val original = Value(0)
        val history = EditHistory(original, { it.copy() }, limit = 2)
        original.x = 10; history.value().x = 20; assertEquals(0, history.value().x)
        history.change(Value(1)); history.change(Value(2)); history.change(Value(3))
        history.undo(); history.undo(); assertEquals(1, history.value().x); assertFalse(history.canUndo())
    }
    @Test fun branchingAndDocumentReplacementInvalidateOnlyTheirOwnHistory() {
        val history = EditHistory("a", { it })
        history.change("b"); history.undo(); history.change("c"); assertFalse(history.canRedo())
        history.beginGesture(); history.change("d"); history.replace("next")
        assertEquals("next", history.value()); assertFalse(history.dirty()); assertFalse(history.canUndo())
    }
}
