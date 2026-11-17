package dev.dogsc.core

import dev.dogsc.core.model.CompositionPoint
import dev.dogsc.core.model.CompositionRect
import dev.dogsc.core.scene.CanvasHitTesting
import dev.dogsc.core.scene.ProjectedScreenQuad
import dev.dogsc.core.scene.ScreenProjection
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CanvasHitTestingTest {
    @Test
    fun rectContainsWithTolerance() {
        val rect = CompositionRect(100.0, 100.0, 200.0, 100.0)
        assertTrue(CanvasHitTesting.rectContains(rect, CompositionPoint(150.0, 150.0)))
        assertFalse(CanvasHitTesting.rectContains(rect, CompositionPoint(50.0, 150.0)))
        // 10px outside, within 24px touch slack.
        assertTrue(CanvasHitTesting.rectContains(rect, CompositionPoint(90.0, 150.0), tolerance = 24.0))
        assertFalse(CanvasHitTesting.rectContains(rect, CompositionPoint(50.0, 150.0), tolerance = 24.0))
    }

    @Test
    fun rotatedRectUnrotatesThePoint() {
        val center = CompositionPoint(500.0, 500.0)
        // A 200x100 box rotated 90°: its long axis is now vertical.
        val rot = PI / 2
        // Point 80px above center lies along the (now vertical) long half-axis (100).
        assertTrue(CanvasHitTesting.rotatedRectContains(center, 200.0, 100.0, rot, CompositionPoint(500.0, 420.0)))
        // Point 80px right of center is along the short half-axis (50) -> outside.
        assertFalse(CanvasHitTesting.rotatedRectContains(center, 200.0, 100.0, rot, CompositionPoint(580.0, 500.0)))
    }

    @Test
    fun snapEngagesWithinToleranceOnly() {
        val guides = listOf(0.0, 960.0, 1920.0)
        val near = CanvasHitTesting.snap(955.0, guides, tolerance = 24.0)
        assertTrue(near.snapped)
        assertEquals(960.0, near.value, 0.0001)
        val far = CanvasHitTesting.snap(900.0, guides, tolerance = 24.0)
        assertFalse(far.snapped)
        assertEquals(900.0, far.value, 0.0001)
    }

    @Test
    fun rotationSnapsToRightAngles() {
        val near = CanvasHitTesting.snapRotationRadians(PI / 2 + 0.05, toleranceRadians = 0.1)
        assertTrue(near.snapped)
        assertEquals(PI / 2, near.value, 0.0001)
        val free = CanvasHitTesting.snapRotationRadians(0.6, toleranceRadians = 0.1)
        assertFalse(free.snapped)
        assertEquals(0.6, free.value, 0.0001)
    }

    @Test
    fun unprojectInvertsProjectOnATiltedQuad() {
        val rect = CompositionRect(200.0, 100.0, 1200.0, 800.0)
        val quad = ScreenProjection.project(rect, rotationX = 18.0, rotationY = -24.0, rotationZ = 6.0, perspective = 0.8)
        val authored = CompositionPoint(rect.x + rect.width * 0.3, rect.y + rect.height * 0.7)
        val projected = quad.project(authored, rect)
        assertNotNull(projected)
        val back = quad.unproject(projected, rect)
        assertNotNull(back)
        assertEquals(authored.x, back.x, 0.001)
        assertEquals(authored.y, back.y, 0.001)
    }

    @Test
    fun unprojectInvertsProjectOnAFlatQuad() {
        val rect = CompositionRect(0.0, 0.0, 1920.0, 1080.0)
        val quad = ProjectedScreenQuad(
            topLeft = CompositionPoint(0.0, 0.0),
            topRight = CompositionPoint(1920.0, 0.0),
            bottomRight = CompositionPoint(1920.0, 1080.0),
            bottomLeft = CompositionPoint(0.0, 1080.0),
        )
        val authored = CompositionPoint(640.0, 360.0)
        val projected = quad.project(authored, rect)
        assertNotNull(projected)
        val back = quad.unproject(projected, rect)
        assertNotNull(back)
        assertEquals(authored.x, back.x, 0.001)
        assertEquals(authored.y, back.y, 0.001)
    }
}