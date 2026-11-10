package dev.dogsc.app.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorLayoutTest {
    @Test
    fun wideLayoutStartsAtBreakpoint() {
        assertFalse(isWideLayout(879.9f))
        assertTrue(isWideLayout(880f))
        assertTrue(isWideLayout(1200f))
    }

    @Test
    fun pinchFactorIsClampedAndSafe() {
        assertEquals(1f, pinchScaleFactor(0f, 100f))
        assertEquals(1f, pinchScaleFactor(100f, 0f))
        assertEquals(1.25f, pinchScaleFactor(100f, 500f))
        assertEquals(0.8f, pinchScaleFactor(500f, 100f))
        assertEquals(1.1f, pinchScaleFactor(100f, 110f))
    }
}
