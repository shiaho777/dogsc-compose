package dev.dogsc.core

import dev.dogsc.core.editor.EditorSession
import dev.dogsc.core.model.NormalizedPoint
import dev.dogsc.core.model.RecorderProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorSessionGestureTest {
    private fun session() = EditorSession(RecorderProject(), sourceDuration = 10.0)

    private fun dragTo(session: EditorSession, x: Double) =
        session.previewGesture { it.copy(canvas = it.canvas.copy(contentPosition = NormalizedPoint(x, 0.5))) }

    @Test
    fun manyPreviewEventsProduceOneUndoEntry() {
        val session = session()
        session.beginGesture()
        repeat(200) { i -> dragTo(session, 0.5 + i * 0.001) }
        assertTrue(session.isGestureActive)
        assertFalse(session.canUndo, "mid-drag must not push undo entries")
        val changed = session.endGesture("拖动内容")
        assertTrue(changed)
        assertTrue(session.canUndo)
        assertEquals("拖动内容", session.undoLabel)
        // One undo returns all the way to the pre-drag baseline, not one micro-step.
        session.undo()
        assertEquals(NormalizedPoint.Center, session.project.canvas.contentPosition)
        assertFalse(session.canUndo)
    }

    @Test
    fun dragThatEndsWhereItStartedProducesNoEntry() {
        val session = session()
        session.beginGesture()
        dragTo(session, 0.8)
        dragTo(session, 0.5) // back to center == baseline
        val changed = session.endGesture("拖动内容")
        assertFalse(changed)
        assertFalse(session.canUndo)
    }

    @Test
    fun discreteEditDuringGestureSettlesItFirst() {
        val session = session()
        session.beginGesture()
        dragTo(session, 0.8)
        // A button press mid-drag must close the gesture as one entry, then add its own.
        session.commit("调整缩放") { it.copy(canvas = it.canvas.copy(contentScale = 2.0)) }
        assertFalse(session.isGestureActive)
        assertEquals("调整缩放", session.undoLabel)
        session.undo() // undo the scale edit -> still at dragged position
        assertEquals(0.8, session.project.canvas.contentPosition.x, 0.0001)
        assertEquals("调整", session.undoLabel)
        session.undo() // undo the drag -> baseline
        assertEquals(NormalizedPoint.Center, session.project.canvas.contentPosition)
    }

    @Test
    fun cancelGestureRestoresBaseline() {
        val session = session()
        session.beginGesture()
        dragTo(session, 0.9)
        session.cancelGesture()
        assertFalse(session.isGestureActive)
        assertEquals(NormalizedPoint.Center, session.project.canvas.contentPosition)
        assertFalse(session.canUndo)
    }

    @Test
    fun invalidPreviewIsRejectedWithoutCrashing() {
        val session = session()
        session.beginGesture()
        // contentScale outside 0.05..8 violates the model invariant; drag must survive.
        val ok = session.previewGesture { it.copy(canvas = it.canvas.copy(contentScale = 99.0)) }
        assertFalse(ok)
        assertEquals(1.0, session.project.canvas.contentScale, 0.0001)
    }
}