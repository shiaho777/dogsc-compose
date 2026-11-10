package dev.dogsc.app.ui.editor

/** Width at which the inspector moves from a bottom sheet to a side rail. */
const val WideLayoutBreakpointDp: Float = 880f

fun isWideLayout(widthDp: Float): Boolean = widthDp >= WideLayoutBreakpointDp

/** Per-event pinch multiplier, clamped so one noisy frame cannot jump the timeline scale. */
fun pinchScaleFactor(previousDistance: Float, distance: Float): Float {
    if (previousDistance <= 0f || distance <= 0f) return 1f
    return (distance / previousDistance).coerceIn(0.8f, 1.25f)
}
