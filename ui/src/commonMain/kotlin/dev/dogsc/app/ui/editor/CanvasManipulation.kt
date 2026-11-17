package dev.dogsc.app.ui.editor

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.dogsc.app.ui.DogScAppState
import dev.dogsc.app.ui.EditorSelection
import dev.dogsc.core.model.CompositionPoint
import dev.dogsc.core.model.NormalizedPoint
import dev.dogsc.core.scene.CanvasHitTesting
import dev.dogsc.core.scene.FrameScene
import dev.dogsc.core.scene.FrameStickerScene
import dev.dogsc.core.scene.OverlayGeometry
import kotlin.math.max

/**
 * Direct canvas manipulation (stage 4): tap to select, drag to move stickers / camera / mosaic
 * regions / screen content. Drags stream through the transient gesture channel so the whole
 * drag is one undo entry and preview reloads stay single-flight. Touch tolerance is enlarged
 * from upstream's mouse slack; haptics fire on select and on snap engage.
 *
 * This overlay sits above the rendered layers and only intercepts pointers when it has a hit;
 * empty-space taps fall through to deselect. Resize/rotate handles build on the same hit-test
 * geometry in a follow-up pass.
 */
@Composable
fun CanvasManipulationLayer(
    state: DogScAppState,
    scene: FrameScene,
    stickerBox: (FrameStickerScene) -> CanvasHitTesting.StickerBox?,
    feedback: CanvasDragFeedback,
    modifier: Modifier = Modifier,
    dragEnabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val currentScene by rememberUpdatedState(scene)
    val currentState by rememberUpdatedState(state)
    // Drag baseline captured at gesture start, so movement is absolute (no per-event drift).
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragTarget by remember { mutableStateOf<CanvasHitTesting.Hit>(CanvasHitTesting.Hit.Empty) }
    var baselinePosition by remember { mutableStateOf(NormalizedPoint.Center) }
    // Resize mode: when the drag starts on a corner handle of the selected element, the drag
    // scales the element about its center instead of moving it.
    var resizeMode by remember { mutableStateOf(false) }
    var baselineWidth by remember { mutableStateOf(0.0) }
    var baselineHeight by remember { mutableStateOf(0.0) }
    var baselineCenter by remember { mutableStateOf(CompositionPoint(0.0, 0.0)) }
    var baselineDist by remember { mutableStateOf(1.0) }
    // Rotate mode: dragging the sticker's top rotation handle spins it about its center,
    // snapping to right angles (±90°) like upstream.
    var rotateMode by remember { mutableStateOf(false) }
    var baselineRotation by remember { mutableStateOf(0.0) }
    var baselineAngle by remember { mutableStateOf(0.0) }

    fun canvasPoint(px: Offset, width: Float, height: Float): CompositionPoint =
        CompositionPoint(
            px.x / width * currentScene.canvasSize.width,
            px.y / height * currentScene.canvasSize.height,
        )

    fun hitAt(px: Offset, width: Float, height: Float): CanvasHitTesting.Hit =
        CanvasHitTesting.hitTest(
            scene = currentScene,
            point = canvasPoint(px, width, height),
            stickerRect = stickerBox,
            tolerance = CanvasHitTesting.DefaultTouchTolerance * (currentScene.canvasSize.width / width),
        )

    /** Axis-aligned canvas-space box of a hit element, for corner-handle detection. */
    fun elementBox(hit: CanvasHitTesting.Hit): CanvasHitTesting.StickerBox? {
        val project = currentState.project ?: return null
        return when (hit) {
            is CanvasHitTesting.Hit.Sticker -> {
                val sticker = currentScene.stickers.firstOrNull { it.id == hit.id } ?: return null
                stickerBox(sticker)
            }
            is CanvasHitTesting.Hit.Mosaic -> {
                val clip = project.timeline.mosaicClips.firstOrNull { it.id == hit.id } ?: return null
                val mapped = OverlayGeometry.overlayRect(currentScene.screen.finalRect, clip.sourceRect)
                CanvasHitTesting.StickerBox(
                    center = CompositionPoint(mapped.midX, mapped.midY),
                    width = mapped.width, height = mapped.height, rotationRadians = 0.0,
                )
            }
            is CanvasHitTesting.Hit.Camera -> {
                val r = currentScene.camera?.rect ?: return null
                CanvasHitTesting.StickerBox(CompositionPoint(r.midX, r.midY), r.width, r.height, 0.0)
            }
            else -> null
        }
    }

    /** True when [px] (canvas space) is within handle slack of a corner of [box]. */
    fun nearCorner(box: CanvasHitTesting.StickerBox, px: CompositionPoint, slack: Double): Boolean {
        val hw = box.width / 2
        val hh = box.height / 2
        val corners = listOf(
            CompositionPoint(box.center.x - hw, box.center.y - hh),
            CompositionPoint(box.center.x + hw, box.center.y - hh),
            CompositionPoint(box.center.x + hw, box.center.y + hh),
            CompositionPoint(box.center.x - hw, box.center.y + hh),
        )
        return corners.any { kotlin.math.abs(it.x - px.x) <= slack && kotlin.math.abs(it.y - px.y) <= slack }
    }

    fun select(hit: CanvasHitTesting.Hit) {
        val selection = when (hit) {
            is CanvasHitTesting.Hit.Sticker -> EditorSelection.Sticker(hit.id)
            is CanvasHitTesting.Hit.Mosaic -> EditorSelection.Mosaic(hit.id)
            is CanvasHitTesting.Hit.Camera -> EditorSelection.None // camera style edited via inspector
            is CanvasHitTesting.Hit.Screen -> EditorSelection.None
            is CanvasHitTesting.Hit.Empty -> EditorSelection.None
        }
        currentState.selectAndInspect(selection)
    }

    fun beginDrag(hit: CanvasHitTesting.Hit, px: Offset, width: Float, height: Float) {
        dragStart = px
        dragTarget = hit
        resizeMode = false
        val project = currentState.project ?: return
        baselinePosition = when (hit) {
            is CanvasHitTesting.Hit.Sticker ->
                project.timeline.stickerClips.firstOrNull { it.id == hit.id }?.position ?: NormalizedPoint.Center
            is CanvasHitTesting.Hit.Mosaic -> {
                val clip = project.timeline.mosaicClips.firstOrNull { it.id == hit.id }
                if (clip != null) NormalizedPoint(clip.sourceRect.x + clip.sourceRect.width / 2, clip.sourceRect.y + clip.sourceRect.height / 2)
                else NormalizedPoint.Center
            }
            is CanvasHitTesting.Hit.Camera -> project.camera.position
            is CanvasHitTesting.Hit.Screen -> project.canvas.contentPosition
            is CanvasHitTesting.Hit.Empty -> NormalizedPoint.Center
        }
        // If the drag starts on a corner handle of a resizable element, scale about its center.
        val box = elementBox(hit)
        if (box != null && hit !is CanvasHitTesting.Hit.Screen) {
            val cp = canvasPoint(px, width, height)
            val slack = CanvasHitTesting.DefaultTouchTolerance * (currentScene.canvasSize.width / width)
            if (nearCorner(box, cp, slack)) {
                resizeMode = true
                baselineWidth = box.width
                baselineHeight = box.height
                baselineCenter = box.center
                baselineDist = max(kotlin.math.hypot(cp.x - box.center.x, cp.y - box.center.y), 1.0)
            } else if (hit is CanvasHitTesting.Hit.Sticker) {
                // Rotation handle sits above the sticker's top edge (un-rotated local space).
                val handle = CompositionPoint(box.center.x, box.center.y - box.height / 2 - slack * 1.5)
                if (kotlin.math.abs(cp.x - handle.x) <= slack * 1.5 && kotlin.math.abs(cp.y - handle.y) <= slack * 1.5) {
                    rotateMode = true
                    baselineCenter = box.center
                    val clip = project.timeline.stickerClips.firstOrNull { it.id == hit.id }
                    baselineRotation = clip?.rotationDegrees ?: 0.0
                    baselineAngle = kotlin.math.atan2(cp.y - box.center.y, cp.x - box.center.x)
                }
            }
        }
        currentState.beginGesture()
    }

    /** Rotation-handle drag: spin the sticker about its center, snapping to right angles. */
    fun rotateDrag(px: Offset, width: Float, height: Float) {
        val cp = canvasPoint(px, width, height)
        val angle = kotlin.math.atan2(cp.y - baselineCenter.y, cp.x - baselineCenter.x)
        var degrees = baselineRotation + (angle - baselineAngle) * 180.0 / kotlin.math.PI
        val snap = CanvasHitTesting.snapRotationRadians(degrees * kotlin.math.PI / 180.0, 0.09)
        degrees = snap.value * 180.0 / kotlin.math.PI
        feedback.active = true
        feedback.snapped = snap.snapped
        feedback.verticalGuides = emptyList()
        feedback.horizontalGuides = emptyList()
        feedback.capsuleAnchor = baselineCenter
        feedback.label = "${degrees.toInt()}°"
        if (snap.snapped) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val hit = dragTarget
        if (hit is CanvasHitTesting.Hit.Sticker) {
            currentState.previewGesture { p ->
                p.copy(timeline = p.timeline.copy(
                    stickerClips = p.timeline.stickerClips.map { if (it.id == hit.id) it.copy(rotationDegrees = degrees) else it },
                ))
            }
        }
    }

    /** Corner-handle resize: scale the element about its center by the pointer-distance ratio. */
    fun resizeDrag(px: Offset, width: Float, height: Float) {
        val cp = canvasPoint(px, width, height)
        val dist = kotlin.math.hypot(cp.x - baselineCenter.x, cp.y - baselineCenter.y)
        val ratio = (dist / baselineDist).coerceIn(0.1, 8.0)
        val project = currentState.project ?: return
        feedback.active = true
        feedback.snapped = false
        feedback.verticalGuides = emptyList()
        feedback.horizontalGuides = emptyList()
        feedback.capsuleAnchor = baselineCenter
        feedback.label = "${(ratio * 100).toInt()}%"
        when (val hit = dragTarget) {
            is CanvasHitTesting.Hit.Sticker -> {
                // Sticker width is canvas-relative; baselineWidth is canvas pixels.
                val baseFraction = baselineWidth / currentScene.canvasSize.width
                val newWidth = (baseFraction * ratio).coerceIn(0.02, 2.0)
                currentState.previewGesture { p ->
                    p.copy(timeline = p.timeline.copy(
                        stickerClips = p.timeline.stickerClips.map { if (it.id == hit.id) it.copy(width = newWidth) else it },
                    ))
                }
            }
            is CanvasHitTesting.Hit.Mosaic -> {
                val clip = project.timeline.mosaicClips.firstOrNull { it.id == hit.id } ?: return
                val content = currentScene.screen.finalRect
                val baseW = baselineWidth / content.width
                val baseH = baselineHeight / content.height
                val newW = (baseW * ratio).coerceIn(0.02, 1.0)
                val newH = (baseH * ratio).coerceIn(0.02, 1.0)
                val cx = baselinePosition.x
                val cy = baselinePosition.y
                currentState.previewGesture { p ->
                    p.copy(timeline = p.timeline.copy(
                        mosaicClips = p.timeline.mosaicClips.map {
                            if (it.id == hit.id) it.copy(
                                sourceRect = clip.sourceRect.copy(
                                    x = (cx - newW / 2).coerceIn(0.0, 1.0 - newW),
                                    y = (cy - newH / 2).coerceIn(0.0, 1.0 - newH),
                                    width = newW,
                                    height = newH,
                                ).clamped(),
                            ) else it
                        },
                    ))
                }
            }
            is CanvasHitTesting.Hit.Camera -> {
                val baseSize = project.camera.size
                val newSize = (baseSize * ratio).coerceIn(0.05, 1.0)
                currentState.previewGesture { p -> p.copy(camera = p.camera.copy(size = newSize)) }
            }
            else -> Unit
        }
    }

    fun moveDrag(px: Offset, width: Float, height: Float) {
        val start = dragStart ?: return
        val project = currentState.project ?: return
        if (rotateMode) {
            rotateDrag(px, width, height)
            return
        }
        if (resizeMode) {
            resizeDrag(px, width, height)
            return
        }
        val dx = (px.x - start.x) / width
        val dy = (px.y - start.y) / height
        var target = NormalizedPoint(
            (baselinePosition.x + dx).coerceIn(0.0, 1.0),
            (baselinePosition.y + dy).coerceIn(0.0, 1.0),
        )
        // Snap to canvas center / edges within touch tolerance; surface guides + capsule.
        val canvasW = currentScene.canvasSize.width
        val canvasH = currentScene.canvasSize.height
        val tolNorm = CanvasHitTesting.DefaultTouchTolerance / width
        val snapX = CanvasHitTesting.snap(target.x, listOf(0.0, 0.5, 1.0), tolNorm)
        val snapY = CanvasHitTesting.snap(target.y, listOf(0.0, 0.5, 1.0), tolNorm)
        target = NormalizedPoint(snapX.value, snapY.value)
        val didSnap = snapX.snapped || snapY.snapped
        feedback.active = true
        feedback.snapped = didSnap
        feedback.verticalGuides = if (snapX.snapped) listOf(snapX.value * canvasW) else emptyList()
        feedback.horizontalGuides = if (snapY.snapped) listOf(snapY.value * canvasH) else emptyList()
        feedback.capsuleAnchor = CompositionPoint(target.x * canvasW, target.y * canvasH)
        feedback.label = "${(target.x * 100).toInt()}% · ${(target.y * 100).toInt()}%"
        if (didSnap) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        when (val hit = dragTarget) {
            is CanvasHitTesting.Hit.Sticker -> currentState.previewGesture { p ->
                p.copy(timeline = p.timeline.copy(
                    stickerClips = p.timeline.stickerClips.map { if (it.id == hit.id) it.copy(position = target) else it },
                ))
            }
            is CanvasHitTesting.Hit.Mosaic -> {
                val clip = project.timeline.mosaicClips.firstOrNull { it.id == hit.id } ?: return
                currentState.previewGesture { p ->
                    p.copy(timeline = p.timeline.copy(
                        mosaicClips = p.timeline.mosaicClips.map {
                            if (it.id == hit.id) it.copy(
                                sourceRect = clip.sourceRect.copy(
                                    x = (target.x - clip.sourceRect.width / 2).coerceIn(0.0, 1.0 - clip.sourceRect.width),
                                    y = (target.y - clip.sourceRect.height / 2).coerceIn(0.0, 1.0 - clip.sourceRect.height),
                                ).clamped(),
                            ) else it
                        },
                    ))
                }
            }
            is CanvasHitTesting.Hit.Camera -> currentState.previewGesture { p ->
                p.copy(camera = p.camera.copy(position = target))
            }
            is CanvasHitTesting.Hit.Screen -> currentState.previewGesture { p ->
                p.copy(canvas = p.canvas.copy(contentPosition = target))
            }
            is CanvasHitTesting.Hit.Empty -> Unit
        }
    }

    fun endDrag() {
        if (dragStart != null) {
            val label = when {
                rotateMode -> "旋转贴图"
                resizeMode -> "缩放画面元素"
                else -> "移动画面元素"
            }
            currentState.endGesture(label)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        resizeMode = false
        rotateMode = false
        dragStart = null
        dragTarget = CanvasHitTesting.Hit.Empty
        feedback.clear()
    }

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { px ->
                    val hit = hitAt(px, size.width.toFloat(), size.height.toFloat())
                    select(hit)
                    if (hit !is CanvasHitTesting.Hit.Empty) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            }
            .pointerInput(dragEnabled) {
                if (!dragEnabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { px ->
                        val hit = hitAt(px, size.width.toFloat(), size.height.toFloat())
                        if (hit !is CanvasHitTesting.Hit.Empty) {
                            select(hit)
                            beginDrag(hit, px, size.width.toFloat(), size.height.toFloat())
                        } else {
                            dragStart = null
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (dragStart != null) moveDrag(change.position, size.width.toFloat(), size.height.toFloat())
                    },
                    onDragEnd = { endDrag() },
                    onDragCancel = {
                        currentState.cancelGesture()
                        dragStart = null
                        dragTarget = CanvasHitTesting.Hit.Empty
                        resizeMode = false
                        rotateMode = false
                        feedback.clear()
                    },
                )
            },
    )
}

/**
 * Builds a sticker's drawn box in canvas space from the scene + decoded image aspect, matching
 * [StickerLayer]'s transform so hit-testing lands on the visible pixels.
 */
fun stickerBoxFor(scene: FrameScene, sticker: FrameStickerScene, imageAspect: Double?): CanvasHitTesting.StickerBox? {
    val aspect = imageAspect ?: return null
    val width = sticker.width * scene.canvasSize.width * sticker.scale
    val height = width / max(aspect, 0.0001)
    return CanvasHitTesting.StickerBox(
        center = CompositionPoint(
            (sticker.position.x + sticker.offset.x) * scene.canvasSize.width,
            (sticker.position.y + sticker.offset.y) * scene.canvasSize.height,
        ),
        width = width,
        height = height,
        rotationRadians = sticker.rotationRadians,
    )
}