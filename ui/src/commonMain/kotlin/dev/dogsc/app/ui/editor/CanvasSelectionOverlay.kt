package dev.dogsc.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import dev.dogsc.app.ui.DogScAppState
import dev.dogsc.app.ui.EditorSelection
import dev.dogsc.app.ui.theme.TrackPalette
import dev.dogsc.core.scene.CanvasHitTesting
import dev.dogsc.core.scene.FrameScene
import dev.dogsc.core.scene.FrameStickerScene
import dev.dogsc.core.scene.OverlayGeometry

/**
 * Selection chrome for direct canvas manipulation: a colored outline + corner handles around
 * the selected element, drawn in the same geometry the hit-test uses. Colors follow the
 * one-color-per-track palette so the canvas, timeline and inspector agree on identity.
 */
@Composable
fun CanvasSelectionOverlay(
    state: DogScAppState,
    scene: FrameScene,
    stickerBox: (FrameStickerScene) -> CanvasHitTesting.StickerBox?,
    modifier: Modifier = Modifier,
) {
    val selection = state.selection
    if (selection is EditorSelection.None) return
    val handlePx = 10f
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val sx = w / scene.canvasSize.width.toFloat()
        val sy = h / scene.canvasSize.height.toFloat()
        val strokeWidth = 2.dp.toPx()

        fun outline(rect: Rect, color: Color, rotationRadians: Float = 0f, pivot: Offset = rect.center) {
            rotate(rotationRadians * 180f / kotlin.math.PI.toFloat(), pivot) {
                drawRect(
                    color = color,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    style = Stroke(strokeWidth),
                )
                // Corner handles.
                val corners = listOf(
                    rect.topLeft, rect.topRight, rect.bottomRight, rect.bottomLeft,
                )
                for (c in corners) {
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(c.x - handlePx / 2, c.y - handlePx / 2),
                        size = Size(handlePx, handlePx),
                    )
                    drawRect(
                        color = color,
                        topLeft = Offset(c.x - handlePx / 2, c.y - handlePx / 2),
                        size = Size(handlePx, handlePx),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
            }
        }

        when (selection) {
            is EditorSelection.Sticker -> {
                val sticker = scene.stickers.firstOrNull { it.id == selection.clipId } ?: return@Canvas
                val box = stickerBox(sticker) ?: return@Canvas
                val rect = Rect(
                    (box.center.x - box.width / 2).toFloat() * sx,
                    (box.center.y - box.height / 2).toFloat() * sy,
                    (box.center.x + box.width / 2).toFloat() * sx,
                    (box.center.y + box.height / 2).toFloat() * sy,
                )
                val pivot = Offset((box.center.x * sx).toFloat(), (box.center.y * sy).toFloat())
                outline(rect, TrackPalette.Sticker, box.rotationRadians.toFloat(), pivot)
                // Rotation handle above the top edge, connected by a short stem.
                rotate(box.rotationRadians.toFloat() * 180f / kotlin.math.PI.toFloat(), pivot) {
                    val stemTop = Offset(pivot.x, rect.top - 22.dp.toPx())
                    drawLine(TrackPalette.Sticker, Offset(pivot.x, rect.top), stemTop, strokeWidth)
                    drawCircle(Color.White, 7.dp.toPx(), stemTop)
                    drawCircle(TrackPalette.Sticker, 7.dp.toPx(), stemTop, style = Stroke(1.5.dp.toPx()))
                }
            }
            is EditorSelection.Mosaic -> {
                val mosaic = scene.screen.mosaics.firstOrNull { it.id == selection.clipId } ?: return@Canvas
                val mapped = OverlayGeometry.overlayRect(scene.screen.finalRect, mosaic.sourceRect)
                val rect = Rect(
                    (mapped.x * sx).toFloat(), (mapped.y * sy).toFloat(),
                    (mapped.right * sx).toFloat(), (mapped.bottom * sy).toFloat(),
                )
                outline(rect, TrackPalette.Mosaic)
            }
            is EditorSelection.Zoom -> {
                // Zoom focus is a point; mark it with a crosshair ring at the content focus.
                val project = state.project ?: return@Canvas
                val clip = project.timeline.zoomClips.firstOrNull { it.id == selection.clipId } ?: return@Canvas
                val content = scene.screen.finalRect
                val cx = ((content.x + clip.focus.x * content.width) * sx).toFloat()
                val cy = ((content.y + clip.focus.y * content.height) * sy).toFloat()
                val r = 16.dp.toPx()
                drawCircle(TrackPalette.Zoom, r, Offset(cx, cy), style = Stroke(strokeWidth))
                drawLine(TrackPalette.Zoom, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth)
                drawLine(TrackPalette.Zoom, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth)
            }
            else -> Unit
        }
    }
}