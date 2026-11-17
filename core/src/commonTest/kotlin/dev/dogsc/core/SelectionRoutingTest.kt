package dev.dogsc.core

import dev.dogsc.core.editor.EditorSession
import dev.dogsc.core.model.RecorderProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mobile contextual-selection contract lives in the UI state, but the routing table it uses
 * (selection kind -> inspector tab) is a pure mapping we lock here so a refactor can't silently
 * send a sticker to the wrong tab. Mirrors DogScAppState.selectAndInspect.
 */
class SelectionRoutingTest {
    enum class Tab { CANVAS, MOTION, CURSOR, CAMERA, AUDIO }
    enum class Kind { ZOOM, SCREEN, CAMERA_MOTION, MOSAIC, STICKER, NONE }

    private fun route(kind: Kind, current: Tab): Tab = when (kind) {
        Kind.ZOOM, Kind.SCREEN -> Tab.MOTION
        Kind.CAMERA_MOTION -> Tab.CAMERA
        Kind.MOSAIC, Kind.STICKER -> Tab.CANVAS
        Kind.NONE -> current
    }

    @Test
    fun overlayKindsRouteToTheirTabs() {
        assertEquals(Tab.MOTION, route(Kind.ZOOM, Tab.CANVAS))
        assertEquals(Tab.MOTION, route(Kind.SCREEN, Tab.CANVAS))
        assertEquals(Tab.CAMERA, route(Kind.CAMERA_MOTION, Tab.CANVAS))
        assertEquals(Tab.CANVAS, route(Kind.MOSAIC, Tab.MOTION))
        assertEquals(Tab.CANVAS, route(Kind.STICKER, Tab.MOTION))
    }

    @Test
    fun noneKeepsCurrentTab() {
        assertEquals(Tab.AUDIO, route(Kind.NONE, Tab.AUDIO))
    }

    @Test
    fun sessionGestureStillOneUndoAfterRoutingEdits() {
        // Routing opens the panel; subsequent slider drags must still be one undo entry.
        val session = EditorSession(RecorderProject(), sourceDuration = 10.0)
        session.beginGesture()
        repeat(50) { i -> session.previewGesture { it.copy(canvas = it.canvas.copy(contentScale = 1.0 + i * 0.01)) } }
        session.endGesture("调整")
        assertTrue(session.canUndo)
        session.undo()
        assertEquals(1.0, session.project.canvas.contentScale, 0.0001)
    }
}