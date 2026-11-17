package dev.dogsc.core

import dev.dogsc.core.model.CompositionPoint
import dev.dogsc.core.model.CompositionRect
import dev.dogsc.core.model.NormalizedOverlayRect
import dev.dogsc.core.scene.OverlayGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayGeometryTest {
    // A letterboxed screen card inside a 1920x1080 canvas.
    private val content = CompositionRect(x = 240.0, y = 60.0, width = 1440.0, height = 960.0)

    @Test
    fun overlayRectMapsContentRelativeToCanvas() {
        val rect = NormalizedOverlayRect(x = 0.25, y = 0.5, width = 0.5, height = 0.25)
        val mapped = OverlayGeometry.overlayRect(content, rect)
        assertEquals(240.0 + 0.25 * 1440.0, mapped.x, 0.0001)
        assertEquals(60.0 + 0.5 * 960.0, mapped.y, 0.0001)
        assertEquals(0.5 * 1440.0, mapped.width, 0.0001)
        assertEquals(0.25 * 960.0, mapped.height, 0.0001)
    }

    @Test
    fun fullContentOverlayCoversTheCardExactly() {
        val mapped = OverlayGeometry.overlayRect(content, NormalizedOverlayRect(0.0, 0.0, 1.0, 1.0))
        assertEquals(content.x, mapped.x, 0.0001)
        assertEquals(content.y, mapped.y, 0.0001)
        assertEquals(content.right, mapped.right, 0.0001)
        assertEquals(content.bottom, mapped.bottom, 0.0001)
    }

    @Test
    fun focusCenterMapsContentRelativeToCanvas() {
        val center = OverlayGeometry.focusCenter(content, 0.5, 0.5)
        assertEquals(content.midX, center.x, 0.0001)
        assertEquals(content.midY, center.y, 0.0001)
    }

    @Test
    fun focusHalfWidthScalesWithContentWidth() {
        // clearHalfWidth returns a fraction; both renderers multiply by content width.
        assertEquals(0.2 * 1440.0, OverlayGeometry.focusHalfWidth(content, 0.2), 0.0001)
    }

    @Test
    fun focusClearRegionRadialMapsCenterAndFeather() {
        val effect = dev.dogsc.core.model.FocusEffect(
            shape = dev.dogsc.core.model.FocusEffectShape.RADIAL,
            size = 0.4,
            softness = 0.5,
            blur = 0.35,
        )
        val region = OverlayGeometry.focusClearRegion(content, effect, 0.5, 0.5)
        assertFalse(region.isLinear)
        assertEquals(content.midX, region.center.x, 0.0001)
        assertEquals(content.midY, region.center.y, 0.0001)
        // clearHalfWidth = shortEdge * size / 2 = 960 * 0.4 / 2 = 192; halfWidth = 192 * (1440/960)? No:
        // focusHalfWidth multiplies the normalized fraction by content.width.
        val shortEdge = 960.0
        val expectedHalf = effect.clearHalfWidth(shortEdge) * content.width
        assertEquals(expectedHalf, region.halfWidth, 0.0001)
        assertTrue(region.feather > 0.0, "softness must produce a non-zero feather band")
    }

    @Test
    fun focusClearRegionLinearCarriesAngle() {
        val effect = dev.dogsc.core.model.FocusEffect.linear().copy(angleDegrees = 30.0)
        val region = OverlayGeometry.focusClearRegion(content, effect, 0.25, 0.75)
        assertTrue(region.isLinear)
        assertEquals(30.0, region.angleDegrees, 0.0001)
        assertEquals(content.x + 0.25 * content.width, region.center.x, 0.0001)
        assertEquals(content.y + 0.75 * content.height, region.center.y, 0.0001)
    }

    @Test
    fun originContentMatchesFullCanvasSemantics() {
        // When the card fills the canvas (x=y=0), content-relative == canvas-relative.
        val full = CompositionRect(0.0, 0.0, 1920.0, 1080.0)
        val rect = NormalizedOverlayRect(0.1, 0.2, 0.3, 0.4)
        val mapped = OverlayGeometry.overlayRect(full, rect)
        assertEquals(0.1 * 1920.0, mapped.x, 0.0001)
        assertEquals(0.2 * 1080.0, mapped.y, 0.0001)
    }
}