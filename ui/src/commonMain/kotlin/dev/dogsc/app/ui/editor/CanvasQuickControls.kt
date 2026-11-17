package dev.dogsc.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.FlipToFront
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.dogsc.app.ui.DogScAppState
import dev.dogsc.app.ui.EditorSelection
import dev.dogsc.app.ui.components.SoftIconButton
import dev.dogsc.app.ui.theme.DogScColors
import dev.dogsc.core.editor.TimelineEditor
import dev.dogsc.core.scene.CanvasHitTesting
import dev.dogsc.core.scene.FrameScene
import dev.dogsc.core.scene.FrameStickerScene

/**
 * Floating quick-edit bar anchored above the selected element (mobile adaptation of upstream
 * EditorCanvasOverlayQuickControls): copy / delete / layer order / open-settings within thumb
 * reach, so common edits don't require opening the settings panel.
 */
@Composable
fun CanvasQuickControls(
    state: DogScAppState,
    scene: FrameScene,
    stickerBox: (FrameStickerScene) -> CanvasHitTesting.StickerBox?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selection = state.selection
    val project = state.project ?: return
    // Resolve the selected element's canvas-space box.
    val resolved: CanvasHitTesting.StickerBox? = when (selection) {
        is EditorSelection.Sticker -> scene.stickers.firstOrNull { it.id == selection.clipId }?.let(stickerBox)
        is EditorSelection.Mosaic -> {
            val clip = project.timeline.mosaicClips.firstOrNull { it.id == selection.clipId }
            clip?.let {
                val r = dev.dogsc.core.scene.OverlayGeometry.overlayRect(scene.screen.finalRect, it.sourceRect)
                CanvasHitTesting.StickerBox(
                    dev.dogsc.core.model.CompositionPoint(r.midX, r.midY), r.width, r.height, 0.0,
                )
            }
        }
        else -> null
    }
    if (resolved == null) return
    val box = resolved

    Box(modifier = modifier.fillMaxSize()) {
        // Anchor above the element, clamped inside the canvas; fall back below near the top.
        val anchorXFraction = (box.center.x / scene.canvasSize.width).toFloat()
        val anchorYFraction = (box.center.y / scene.canvasSize.height).toFloat()
        val above = anchorYFraction > 0.22f
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(0.dp)
                    .offsetFraction(anchorXFraction, anchorYFraction, above)
                    .shadow(8.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(DogScColors.Panel)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selection is EditorSelection.Sticker) {
                    SoftIconButton(Icons.Outlined.FlipToFront, onClick = {
                        state.updateProject("置顶贴图") { TimelineEditor.reorderSticker(it, selection.clipId, toFront = true) }
                    }, contentDescription = "置顶", size = 40.dp, iconSize = 18.dp)
                    SoftIconButton(Icons.Outlined.FlipToBack, onClick = {
                        state.updateProject("置底贴图") { TimelineEditor.reorderSticker(it, selection.clipId, toFront = false) }
                    }, contentDescription = "置底", size = 40.dp, iconSize = 18.dp)
                    SoftIconButton(Icons.Outlined.ContentCopy, onClick = {
                        state.updateProject("复制贴图") { TimelineEditor.duplicateSticker(it, selection.clipId) }
                    }, contentDescription = "复制贴图", size = 40.dp, iconSize = 18.dp)
                }
                SoftIconButton(Icons.Outlined.Tune, onClick = onOpenSettings, contentDescription = "更多设置", size = 40.dp, iconSize = 18.dp)
                SoftIconButton(Icons.Outlined.DeleteOutline, onClick = { state.deleteSelection() }, contentDescription = "删除", size = 40.dp, iconSize = 18.dp, tint = DogScColors.Danger)
            }
        }
    }
}

/** Places the bar centered horizontally on the element, above (or below) its box. */
private fun Modifier.offsetFraction(x: Float, y: Float, above: Boolean): Modifier =
    this.then(
        layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                // parent size known via constraints max
                val pw = constraints.maxWidth
                val ph = constraints.maxHeight
                var px = (x * pw - placeable.width / 2f).toInt()
                px = px.coerceIn(4, (pw - placeable.width - 4).coerceAtLeast(4))
                // y is element center fraction; put bar above the element top (~center - 12% h)
                val elemTop = (y * ph - ph * 0.10f).toInt()
                val py = (if (above) (elemTop - placeable.height - 8) else (y * ph + ph * 0.10f + 8).toInt())
                    .coerceIn(4, (ph - placeable.height - 4).coerceAtLeast(4))
                placeable.place(px, py)
            }
        },
    )