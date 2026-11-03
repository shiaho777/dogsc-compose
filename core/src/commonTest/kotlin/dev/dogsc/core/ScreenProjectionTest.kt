package dev.dogsc.core

import dev.dogsc.core.model.CompositionPoint
import dev.dogsc.core.model.CompositionRect
import dev.dogsc.core.scene.ScreenProjection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScreenProjectionTest {
    private val rect = CompositionRect(120.0, 80.0, 1280.0, 720.0)

    private fun apply(h: DoubleArray, x: Double, y: Double): CompositionPoint {
        val w = h[6] * x + h[7] * y + h[8]
        return CompositionPoint((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
    }

    private fun assertClose(expected: CompositionPoint, actual: CompositionPoint) {
        assertTrue(abs(expected.x - actual.x) < 1e-6 && abs(expected.y - actual.y) < 1e-6, "expected $expected, got $actual")
    }

    @Test
    fun homographyMapsSourceCornersOntoProjectedQuad() {
        val quad = ScreenProjection.project(rect, 7.0, -14.0, 3.0, 0.7)
        val h = assertNotNull(quad.homography(rect))
        assertClose(quad.topLeft, apply(h, rect.x, rect.y))
        assertClose(quad.topRight, apply(h, rect.right, rect.y))
        assertClose(quad.bottomRight, apply(h, rect.right, rect.bottom))
        assertClose(quad.bottomLeft, apply(h, rect.x, rect.bottom))
    }

    @Test
    fun homographyAgreesWithPointProjection() {
        val quad = ScreenProjection.project(rect, -20.0, 25.0, 0.0, 1.2)
        val h = assertNotNull(quad.homography(rect))
        val inside = CompositionPoint(rect.x + rect.width * 0.3, rect.y + rect.height * 0.8)
        assertClose(assertNotNull(quad.project(inside, rect)), apply(h, inside.x, inside.y))
    }

    @Test
    fun flatProjectionIsIdentity() {
        val quad = ScreenProjection.project(rect, 0.0, 0.0, 0.0, 0.7)
        val h = assertNotNull(quad.homography(rect))
        val p = CompositionPoint(400.0, 300.0)
        assertClose(p, apply(h, p.x, p.y))
    }
}