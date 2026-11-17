package dev.dogsc.app.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.dogsc.app.ui.DogScAppState
import dev.dogsc.app.ui.theme.DogScMotion
import dev.dogsc.app.ui.theme.TrackPalette
import dev.dogsc.app.ui.theme.rememberReducedMotion

/**
 * Magnetic-snap pulse ring (upstream EditorTimelineMagneticSnap): when a drag snaps to a clip
 * boundary, a ring expands and fades over [DogScMotion.SnapPulseMs] at the snap position, with
 * a haptic tick. Touch adaptation of the desktop magnetic-snap feedback.
 */
@Composable
fun TimelineSnapPulse(
    state: DogScAppState,
    contentWidthPx: Float,
    modifier: Modifier = Modifier,
) {
    val pulseId = state.snapPulseId
    val pulseTime = state.snapPulseTime ?: return
    if (pulseId == 0L) return
    val reduced = rememberReducedMotion()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(pulseId) {
        progress.snapTo(0f)
        if (reduced) {
            progress.snapTo(1f)
        } else {
            progress.animateTo(1f, tween(DogScMotion.SnapPulseMs))
        }
    }
    val duration = state.outputDuration.coerceAtLeast(0.001)
    val x = (pulseTime / duration * contentWidthPx).toFloat()
    Canvas(modifier = modifier.fillMaxSize()) {
        val p = progress.value
        if (p >= 1f) return@Canvas
        val centerY = size.height * 0.12f
        val radius = (8f + p * 26f).dp.toPx()
        val alpha = (1f - p) * 0.8f
        drawCircle(
            color = TrackPalette.Zoom.copy(alpha = alpha),
            radius = radius,
            center = Offset(x, centerY),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}