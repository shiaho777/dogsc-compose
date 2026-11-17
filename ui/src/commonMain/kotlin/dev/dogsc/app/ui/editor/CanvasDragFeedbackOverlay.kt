package dev.dogsc.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dogsc.app.ui.theme.TrackPalette
import dev.dogsc.core.scene.FrameScene

/**
 * Draws the live drag feedback: dashed snap reference lines across the canvas and a numeric
 * capsule near the dragged element. Touch adaptation of upstream's EditorCanvasDragFeedback.
 */
@Composable
fun CanvasDragFeedbackOverlay(
    scene: FrameScene,
    feedback: CanvasDragFeedback,
    modifier: Modifier = Modifier,
) {
    if (!feedback.active) return
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val sx = w / scene.canvasSize.width.toFloat()
        val sy = h / scene.canvasSize.height.toFloat()
        val guideColor = TrackPalette.Zoom.copy(alpha = 0.9f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
        for (x in feedback.verticalGuides) {
            val px = (x * sx).toFloat()
            drawLine(guideColor, Offset(px, 0f), Offset(px, h), 1.5.dp.toPx(), pathEffect = dash)
        }
        for (y in feedback.horizontalGuides) {
            val py = (y * sy).toFloat()
            drawLine(guideColor, Offset(0f, py), Offset(w, py), 1.5.dp.toPx(), pathEffect = dash)
        }
        // Numeric capsule near the dragged element.
        val anchor = feedback.capsuleAnchor ?: return@Canvas
        val label = feedback.label
        if (label.isEmpty()) return@Canvas
        val measured = textMeasurer.measure(label, TextStyle(fontSize = 12.sp))
        val padX = 8.dp.toPx()
        val padY = 4.dp.toPx()
        val capsuleW = measured.size.width + padX * 2
        val capsuleH = measured.size.height + padY * 2
        var cx = (anchor.x * sx).toFloat() - capsuleW / 2
        var cy = (anchor.y * sy).toFloat() - capsuleH - 16.dp.toPx()
        cx = cx.coerceIn(4f, w - capsuleW - 4f)
        cy = cy.coerceIn(4f, h - capsuleH - 4f)
        drawRoundRect(
            color = Color(0xE61A1A1A),
            topLeft = Offset(cx, cy),
            size = Size(capsuleW, capsuleH),
            cornerRadius = CornerRadius(capsuleH / 2, capsuleH / 2),
        )
        drawText(
            textLayoutResult = measured,
            color = Color.White,
            topLeft = Offset(cx + padX, cy + padY),
        )
    }
}