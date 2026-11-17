package dev.dogsc.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.dogsc.app.ui.DogScAppState
import dev.dogsc.app.ui.theme.DogScColors
import dev.dogsc.core.model.BackgroundPatternPreset
import dev.dogsc.core.model.BackgroundSource
import dev.dogsc.core.model.CompositionSize
import dev.dogsc.core.model.CursorAssetID
import dev.dogsc.core.model.DynamicBackgroundPreset
import dev.dogsc.core.model.HexColor
import dev.dogsc.core.model.MosaicEffectStyle
import dev.dogsc.core.model.ProjectPointerTrack
import dev.dogsc.core.model.ScreenMotionTrack
import dev.dogsc.core.model.TimelineMap
import dev.dogsc.core.model.ZoomAnimationTrack
import dev.dogsc.core.model.CameraMotionTrack
import dev.dogsc.core.model.StickerTransitionTrack
import dev.dogsc.core.scene.CameraContentFill
import dev.dogsc.core.scene.CursorClickGeometryFrame
import dev.dogsc.core.scene.CursorRenderGeometry
import dev.dogsc.core.scene.FrameCursorScene
import dev.dogsc.core.scene.FrameScene
import dev.dogsc.core.scene.FrameSceneEvaluator
import dev.dogsc.core.scene.FrameScreenChromeKind
import dev.dogsc.core.scene.FrameScreenChromeScene
import dev.dogsc.core.scene.FrameScreenDecoration
import dev.dogsc.core.scene.FrameStickerScene
import dev.dogsc.core.scene.OverlayGeometry
import dev.dogsc.core.scene.ProjectedScreenQuad
import dev.dogsc.core.scene.RenderParams
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

@Composable
fun ScenePreview(
    state: DogScAppState,
    modifier: Modifier = Modifier,
    showSafeGuides: Boolean = false,
) {
    val project = state.project ?: return
    val canvasAspect = state.canvasAspectRatio
    val pointerTrack = remember(project.timeline, state.sourceDuration, state.pointerEvents) {
        runCatching {
            ProjectPointerTrack(
                TimelineMap(project.timeline.sourceSequence, state.sourceDuration),
                state.pointerEvents,
            )
        }.getOrNull()
    }
    // The preview only has new pixels when a frame arrives (~30 fps); quantising the scene time
    // avoids re-composing and re-recording the whole canvas on every 60 Hz playhead tick.
    val sceneTime = (state.playhead * 30).toInt() / 30.0
    val scene = remember(
        project, sceneTime, canvasAspect, state.sourceAspectRatio, state.cameraAspectRatio,
        state.previewFrame, state.cameraPreviewFrame,
    ) {
        runCatching {
            FrameSceneEvaluator.scene(
                project = project,
                time = sceneTime,
                outputDuration = state.outputDuration,
                canvasSize = CompositionSize(1920.0, 1920.0 / canvasAspect),
                sourceAspectRatio = state.sourceAspectRatio,
                cameraSourceSize = state.cameraAspectRatio?.let {
                    CompositionSize(it, 1.0)
                },
                pointerTrack = pointerTrack,
                zoomTrack = ZoomAnimationTrack(project.timeline.zoomClips, state.outputDuration),
                screenMotionTrack = ScreenMotionTrack(project.timeline.screenMotionClips),
                cameraMotionTrack = CameraMotionTrack(project.timeline.cameraMotionClips),
                stickerTrack = StickerTransitionTrack(project.timeline.stickerClips),
            )
        }.getOrNull()
    }

    // View transform (pinch to inspect detail on small screens). Kept separate from element
    // manipulation: at 1x a single finger drags elements; zoomed in it pans the view.
    var viewScale by remember { mutableStateOf(1f) }
    var viewOffset by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(Size.Zero) }

    fun clampOffset(off: Offset, s: Float): Offset {
        val mx = (viewSize.width * (s - 1f)) / 2f
        val my = (viewSize.height * (s - 1f)) / 2f
        return Offset(off.x.coerceIn(-mx, mx), off.y.coerceIn(-my, my))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(canvasAspect.toFloat())
            .clip(RoundedCornerShape(4.dp))
            .onSizeChanged { viewSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(viewScale > 1.01f) {
                // Only attach view-navigation gestures while zoomed; at 1x the single finger
                // belongs to element manipulation (child), so the parent must not intercept.
                if (viewScale <= 1.01f) return@pointerInput
                detectTapGestures(onDoubleTap = { viewScale = 1f; viewOffset = Offset.Zero })
            }
            .pointerInput(viewScale > 1.01f) {
                if (viewScale <= 1.01f) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (viewScale * zoom).coerceIn(1f, 4f)
                            viewOffset = clampOffset(viewOffset + pan, newScale)
                            viewScale = newScale
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1) {
                            val delta = pressed[0].positionChange()
                            viewOffset = clampOffset(viewOffset + delta, viewScale)
                            pressed[0].consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = viewScale
                    scaleY = viewScale
                    translationX = viewOffset.x
                    translationY = viewOffset.y
                    transformOrigin = TransformOrigin.Center
                },
        ) {
        if (scene == null) {
            Box(Modifier.fillMaxSize().background(DogScColors.Raised))
        } else {
            val canvasWidth = scene.canvasSize.width
            val canvasHeight = scene.canvasSize.height
            Canvas(Modifier.fillMaxSize()) {
                val scale = size.width / canvasWidth.toFloat()
                val canvasSize = Size(canvasWidth.toFloat() * scale, canvasHeight.toFloat() * scale)
                drawBackground(scene, state.backgroundPreviewFrame, canvasSize)
                if (showSafeGuides) {
                    drawSafeGuides(canvasSize)
                }
            }
            BackgroundBlurLayer(scene, state.backgroundPreviewFrame)
            // Draw in the scene's layer order so preview matches export exactly:
            // SCREEN (incl. content-clipped mosaic blur) -> CURSOR -> SPOTLIGHT -> CAMERA -> STICKERS.
            for (role in scene.layerOrder) {
                when (role) {
                    dev.dogsc.core.scene.FrameLayerRole.BACKGROUND -> Unit // already drawn above
                    dev.dogsc.core.scene.FrameLayerRole.SCREEN -> {
                        ScreenLayer(
                            scene = scene,
                            frame = state.previewFrame,
                            scale = 1f,
                            canvasWidth = canvasWidth,
                            canvasHeight = canvasHeight,
                        )
                        StickerBackdropBlurLayer(scene, state.previewFrame)
                        MosaicBlurLayer(scene, state.previewFrame)
                        FocusBlurLayer(scene, state.previewFrame)
                    }
                    dev.dogsc.core.scene.FrameLayerRole.CURSOR -> CursorLayer(scene)
                    dev.dogsc.core.scene.FrameLayerRole.SPOTLIGHT -> MosaicLayer(scene)
                    dev.dogsc.core.scene.FrameLayerRole.CAMERA -> CameraLayer(scene, state.cameraPreviewFrame)
                    dev.dogsc.core.scene.FrameLayerRole.STICKERS -> StickerLayer(scene, state.stickerFrames)
                }
            }
            // Selection chrome + direct manipulation overlay. Sticker hit boxes use the decoded
            // image aspect so taps land on the visible pixels.
            val stickerBox: (dev.dogsc.core.scene.FrameStickerScene) -> dev.dogsc.core.scene.CanvasHitTesting.StickerBox? = { sticker ->
                val img = state.stickerFrames[sticker.relativePath]
                stickerBoxFor(scene, sticker, img?.let { it.width.toDouble() / it.height.coerceAtLeast(1) })
            }
            val dragFeedback = remember { CanvasDragFeedback() }
            CanvasSelectionOverlay(state = state, scene = scene, stickerBox = stickerBox, modifier = Modifier.fillMaxSize())
            CanvasQuickControls(
                state = state, scene = scene, stickerBox = stickerBox,
                onOpenSettings = { state.selectAndInspect(state.selection) },
                modifier = Modifier.fillMaxSize(),
            )
            CanvasDragFeedbackOverlay(scene = scene, feedback = dragFeedback, modifier = Modifier.fillMaxSize())
            CanvasManipulationLayer(
                state = state, scene = scene, stickerBox = stickerBox, feedback = dragFeedback,
                modifier = Modifier.fillMaxSize(),
                dragEnabled = viewScale <= 1.01f,
            )
        }
        }
        // View-zoom control: enter zoom to inspect detail on small screens; once zoomed, pinch
        // to adjust, single-finger to pan, double-tap (or this button) to reset.
        dev.dogsc.app.ui.components.SoftIconButton(
            icon = if (viewScale > 1.01f) Icons.Outlined.Close else Icons.Outlined.Fullscreen,
            onClick = {
                if (viewScale > 1.01f) { viewScale = 1f; viewOffset = Offset.Zero }
                else viewScale = 2f
            },
            contentDescription = if (viewScale > 1.01f) "复位视图" else "放大视图",
            size = 32.dp,
            iconSize = 17.dp,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        )
    }
}

@Composable
private fun ScreenLayer(
    scene: FrameScene,
    frame: ImageBitmap?,
    scale: Float,
    canvasWidth: Double,
    canvasHeight: Double,
) {
    val screen = scene.screen
    val outerRect = screen.decoration.projectionRect ?: screen.finalRect
    val canvas = scene.canvasSize
    val chrome = (screen.decoration as? FrameScreenDecoration.Chrome)?.scene
    val outerLeft = (outerRect.x / canvas.width).toFloat()
    val outerTop = (outerRect.y / canvas.height).toFloat()
    val outerWidth = (outerRect.width / canvas.width).toFloat()
    val outerHeight = (outerRect.height / canvas.height).toFloat()
    val contentLeft = ((screen.finalRect.x - outerRect.x) / outerRect.width).toFloat()
    val contentTop = ((screen.finalRect.y - outerRect.y) / outerRect.height).toFloat()
    val contentWidth = (screen.finalRect.width / outerRect.width).toFloat()
    val contentHeight = (screen.finalRect.height / outerRect.height).toFloat()
    val outerRadius = if (chrome != null) {
        (chrome.outerCornerRadius / outerRect.width).toFloat()
    } else {
        (screen.cornerRadius / max(screen.finalRect.width, 1.0)).toFloat()
    }
    val contentRadius = if (chrome != null) {
        (chrome.contentCornerRadius / max(screen.finalRect.width, 1.0)).toFloat()
    } else {
        (screen.cornerRadius / max(screen.finalRect.width, 1.0)).toFloat()
    }
    val textMeasurer = rememberTextMeasurer()
    val shadow = screen.shadow
    if (shadow != null) {
        // True soft shadow matching export's BlurMaskFilter. A full-resolution BlurEffect cost
        // ~35 ms/frame, so the shadow is drawn into a downscaled layer (÷4) and blurred there,
        // then scaled back up — bounded GPU cost with a genuinely soft edge instead of strokes.
        val shadowAlpha = (shadow.opacity * RenderParams.ScreenShadowAlpha / 255.0).toFloat()
            .coerceIn(0f, 1f) * screen.opacity.toFloat()
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 0.25f
                    scaleY = 0.25f
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    val sigma = max(shadow.radius.toFloat(), RenderParams.MinShadowRadius.toFloat()) * 0.25f
                    renderEffect = BlurEffect(sigma.coerceAtLeast(1f), sigma.coerceAtLeast(1f), TileMode.Clamp)
                },
        ) {
            // Draw at 4× coordinates so the ÷4 layer scale lands the shadow back on the card.
            val sx = size.width / canvas.width.toFloat() * 4f
            val sy = size.height / canvas.height.toFloat() * 4f
            val quad = screen.projectedQuad
            val dx = shadow.offset.x
            val dy = shadow.offset.y
            val path = Path().apply {
                moveTo(((quad.topLeft.x + dx) * sx).toFloat(), ((quad.topLeft.y + dy) * sy).toFloat())
                lineTo(((quad.topRight.x + dx) * sx).toFloat(), ((quad.topRight.y + dy) * sy).toFloat())
                lineTo(((quad.bottomRight.x + dx) * sx).toFloat(), ((quad.bottomRight.y + dy) * sy).toFloat())
                lineTo(((quad.bottomLeft.x + dx) * sx).toFloat(), ((quad.bottomLeft.y + dy) * sy).toFloat())
                close()
            }
            drawPath(path, Color.Black.copy(alpha = shadowAlpha))
        }
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Sticker "仅背景" suppresses the screen card; fold it into the layer alpha.
                alpha = (screen.opacity * (1.0 - scene.stickerBackdrop.screenSuppression)).toFloat().coerceIn(0f, 1f)
            },
    ) {
        val w = size.width
        val h = size.height
        val outer = Rect(
            outerLeft * w,
            outerTop * h,
            (outerLeft + outerWidth) * w,
            (outerTop + outerHeight) * h,
        )
        val content = Rect(
            outer.left + contentLeft * outer.width,
            outer.top + contentTop * outer.height,
            outer.left + (contentLeft + contentWidth) * outer.width,
            outer.top + (contentTop + contentHeight) * outer.height,
        )
        if (outer.width <= 1f || outer.height <= 1f) return@Canvas
        val sx = w / canvas.width.toFloat()
        val sy = h / canvas.height.toFloat()
        val homography = screen.projectedQuad.homography(outerRect)
        val warp = homography?.let { m -> pixelHomography(m, sx, sy) }
        withTransform({ if (warp != null) transform(warp) }) {
            if (chrome != null) {
                drawChrome(chrome, outer, content, contentRadius, textMeasurer)
            } else {
                drawRoundRect(
                    color = Color(0xFF111318),
                    topLeft = Offset(outer.left, outer.top),
                    size = Size(outer.width, outer.height),
                    cornerRadius = CornerRadius(outer.width * outerRadius, outer.width * outerRadius),
                )
            }
            val contentPath = Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        content,
                        CornerRadius(content.width * contentRadius, content.width * contentRadius),
                    )
                )
            }
            clipPath(contentPath) {
                if (frame != null) {
                    drawScreenFrame(frame, screen, content)
                } else {
                    drawRect(Color(0xFF23252B), content.topLeft, content.size)
                    drawGrid(content, Color.White.copy(alpha = 0.05f), 24f)
                }
            }
            if (screen.borderWidth > 0) {
                drawRoundRect(
                    color = screen.borderColor.toCompose().copy(alpha = screen.borderOpacity.toFloat()),
                    topLeft = content.topLeft,
                    size = content.size,
                    cornerRadius = CornerRadius(content.width * contentRadius, content.width * contentRadius),
                    style = Stroke(width = max(1f, (screen.borderWidth / screen.finalRect.width * content.width).toFloat())),
                )
            }
            if (chrome == null) {
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.12f),
                    topLeft = content.topLeft,
                    size = content.size,
                    cornerRadius = CornerRadius(content.width * contentRadius, content.width * contentRadius),
                    style = Stroke(1f),
                )
            }
        }
    }
}

/**
 * Converts the composition-space homography from [ProjectedScreenQuad.homography] into a
 * pixel-space Compose matrix: P = S · H · S⁻¹ with S = diag(sx, sy, 1).
 */
private fun pixelHomography(h: DoubleArray, sx: Float, sy: Float): Matrix {
    val s = floatArrayOf(sx, sy, 1f)
    fun p(row: Int, col: Int): Float = (h[row * 3 + col] * s[row] / s[col]).toFloat()
    val matrix = Matrix()
    matrix.values[Matrix.ScaleX] = p(0, 0)
    matrix.values[Matrix.SkewX] = p(0, 1)
    matrix.values[Matrix.TranslateX] = p(0, 2)
    matrix.values[Matrix.SkewY] = p(1, 0)
    matrix.values[Matrix.ScaleY] = p(1, 1)
    matrix.values[Matrix.TranslateY] = p(1, 2)
    matrix.values[Matrix.Perspective0] = p(2, 0)
    matrix.values[Matrix.Perspective1] = p(2, 1)
    matrix.values[Matrix.Perspective2] = p(2, 2)
    return matrix
}

private fun DrawScope.drawScreenFrame(
    frame: ImageBitmap,
    screen: dev.dogsc.core.scene.FrameScreenScene,
    content: Rect,
) {
    val crop = screen.sourceCrop.clamped()
    val viewport = screen.viewport
    val z = viewport.scale.coerceAtLeast(1.0)
    val tx = viewport.translation.x
    val ty = viewport.translation.y
    val local0x = ((-0.5 - tx) / z + 0.5).coerceIn(0.0, 1.0)
    val local1x = ((0.5 - tx) / z + 0.5).coerceIn(0.0, 1.0)
    val local0y = ((-0.5 - ty) / z + 0.5).coerceIn(0.0, 1.0)
    val local1y = ((0.5 - ty) / z + 0.5).coerceIn(0.0, 1.0)
    val sx = ((crop.x + local0x * crop.width) * frame.width).toInt().coerceIn(0, frame.width - 1)
    val sy = ((crop.y + local0y * crop.height) * frame.height).toInt().coerceIn(0, frame.height - 1)
    val sw = (((local1x - local0x) * crop.width) * frame.width).toInt().coerceAtLeast(1)
    val sh = (((local1y - local0y) * crop.height) * frame.height).toInt().coerceAtLeast(1)
    drawImage(
        image = frame,
        srcOffset = IntOffset(sx, sy),
        srcSize = IntSize(min(sw, frame.width - sx), min(sh, frame.height - sy)),
        dstOffset = IntOffset(content.left.toInt(), content.top.toInt()),
        dstSize = IntSize(content.width.toInt().coerceAtLeast(1), content.height.toInt().coerceAtLeast(1)),
    )
}

private fun DrawScope.drawChrome(
    chrome: FrameScreenChromeScene,
    outer: Rect,
    content: Rect,
    contentRadius: Float,
    textMeasurer: TextMeasurer,
) {
    val surface = chrome.surfaceColor.toCompose()
    val toolbar = chrome.toolbarColor.toCompose()
    val radius = (chrome.outerCornerRadius / max(chrome.outerRect.width, 1.0) * outer.width).toFloat()
    drawRoundRect(surface, outer.topLeft, outer.size, CornerRadius(radius, radius))
    val toolbarRect = Rect(
        outer.left,
        outer.top,
        outer.right,
        content.top + (content.top - outer.top) * 0.08f,
    )
    drawRoundRect(
        toolbar,
        toolbarRect.topLeft,
        toolbarRect.size,
        CornerRadius(radius, radius),
    )
    drawRect(toolbar, Offset(toolbarRect.left, toolbarRect.bottom - toolbarRect.height * 0.35f), Size(toolbarRect.width, toolbarRect.height * 0.35f))
    when (chrome.kind) {
        FrameScreenChromeKind.WINDOW -> {
            val dotRadius = max(outer.width * 0.006f, 2f)
            val cy = toolbarRect.center.y
            val startX = outer.left + outer.width * 0.018f
            val gap = dotRadius * 2.8f
            val colors = listOf(Color(0xFFFF5F57), Color(0xFFFEBC2E), Color(0xFF28C840))
            colors.forEachIndexed { index, color ->
                drawCircle(color, dotRadius, Offset(startX + gap * index, cy))
            }
            if (chrome.title.isNotBlank()) {
                val layout = textMeasurer.measure(
                    chrome.title,
                    style = TextStyle(
                        color = chrome.glyphColor.toCompose().copy(alpha = 0.72f),
                        fontSize = max(outer.width * 0.012f, 7f).sp,
                    ),
                    maxLines = 1,
                )
                drawText(
                    layout,
                    topLeft = Offset(outer.center.x - layout.size.width / 2, cy - layout.size.height / 2),
                )
            }
        }
        FrameScreenChromeKind.BROWSER -> {
            val dotRadius = max(outer.width * 0.0055f, 2f)
            val cy = toolbarRect.center.y
            val startX = outer.left + outer.width * 0.016f
            val gap = dotRadius * 2.8f
            listOf(Color(0xFFFF5F57), Color(0xFFFEBC2E), Color(0xFF28C840)).forEachIndexed { index, color ->
                drawCircle(color, dotRadius, Offset(startX + gap * index, cy))
            }
            val field = Rect(
                outer.left + outer.width * 0.09f,
                toolbarRect.top + toolbarRect.height * 0.24f,
                outer.right - outer.width * 0.03f,
                toolbarRect.bottom - toolbarRect.height * 0.22f,
            )
            drawRoundRect(
                chrome.fieldColor.toCompose(),
                field.topLeft,
                field.size,
                CornerRadius(field.height / 2, field.height / 2),
            )
            if (chrome.browserAddress.isNotBlank()) {
                val layout = textMeasurer.measure(
                    chrome.browserAddress,
                    style = TextStyle(
                        color = chrome.glyphColor.toCompose().copy(alpha = 0.80f),
                        fontSize = max(outer.width * 0.011f, 6f).sp,
                    ),
                    maxLines = 1,
                )
                drawText(
                    layout,
                    topLeft = Offset(field.left + field.width * 0.03f, field.center.y - layout.size.height / 2),
                )
            }
        }
        else -> {
            // Device bezel: a speaker slot / camera dot at the top.
            val cy = (outer.top + content.top) / 2
            if (chrome.kind.isPhone) {
                drawRoundRect(
                    Color(0xFF0E0F12),
                    Offset(outer.center.x - outer.width * 0.055f, cy - outer.height * 0.006f),
                    Size(outer.width * 0.11f, outer.height * 0.012f),
                    CornerRadius(outer.height * 0.006f),
                )
            } else {
                drawCircle(Color(0xFF0E0F12), max(outer.width * 0.0035f, 1.5f), Offset(outer.center.x, cy))
            }
        }
    }
    // Content aperture is darker than the chrome, so the card reads as a screen.
    drawRoundRect(
        Color(0xFF0B0C0F),
        content.topLeft,
        content.size,
        CornerRadius(content.width * contentRadius, content.width * contentRadius),
    )
}

@Composable
private fun CameraLayer(scene: FrameScene, frame: ImageBitmap?) {
    val camera = scene.camera ?: return
    if (camera.opacity <= 0.001) return
    val canvas = scene.canvasSize
    val left = (camera.rect.x / canvas.width).toFloat()
    val top = (camera.rect.y / canvas.height).toFloat()
    val width = (camera.rect.width / canvas.width).toFloat()
    val height = (camera.rect.height / canvas.height).toFloat()
    val radiusFraction = (camera.cornerRadius / max(camera.rect.width, 1.0)).toFloat()
    Box(modifier = Modifier.fillMaxSize()) {
        val shadow = camera.shadow
        if (shadow != null) {
            // True soft shadow (matches export's BlurMaskFilter): blur only this small shadow
            // layer, not the whole frame, so the per-frame GPU cost stays bounded.
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val canvasScale = size.width / canvas.width.toFloat()
                        val sigma = (max(shadow.radius.toFloat(), RenderParams.MinShadowRadius.toFloat()) * canvasScale)
                            .coerceIn(1f, 48f)
                        renderEffect = BlurEffect(sigma, sigma, TileMode.Clamp)
                    },
            ) {
                val w = size.width
                val h = size.height
                val rect = Rect(left * w, top * h, (left + width) * w, (top + height) * h)
                val dy = (shadow.offset.y / canvas.height * h).toFloat()
                val dx = (shadow.offset.x / canvas.width * w).toFloat()
                drawRoundRect(
                    Color.Black.copy(alpha = (shadow.opacity * RenderParams.CameraShadowAlpha / 255.0).toFloat()),
                    Offset(rect.left + dx, rect.top + dy),
                    rect.size,
                    CornerRadius(rect.width * radiusFraction, rect.width * radiusFraction),
                )
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val rect = Rect(left * w, top * h, (left + width) * w, (top + height) * h)
            if (rect.width <= 1f || rect.height <= 1f) return@Canvas
            val path = Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        rect,
                        CornerRadius(rect.width * radiusFraction, rect.width * radiusFraction),
                    )
                )
            }
            clipPath(path) {
                val fill = camera.contentFill
                if (frame != null && fill != null) {
                    // Match the exporter: crop visibleSource (source units) and draw it at
                    // destination (canvas units), so preview and export frame identically.
                    val sourceScaleX = frame.width / fill.source.width.coerceAtLeast(0.0001)
                    val sourceScaleY = frame.height / fill.source.height.coerceAtLeast(0.0001)
                    val canvasScale = w / canvas.width.toFloat()
                    val srcOffset = IntOffset(
                        (fill.visibleSource.x * sourceScaleX).toInt().coerceIn(0, frame.width - 1),
                        (fill.visibleSource.y * sourceScaleY).toInt().coerceIn(0, frame.height - 1),
                    )
                    val srcSize = IntSize(
                        (fill.visibleSource.width * sourceScaleX).toInt().coerceAtLeast(1),
                        (fill.visibleSource.height * sourceScaleY).toInt().coerceAtLeast(1),
                    )
                    val dstOffset = IntOffset(
                        (fill.destination.x * canvasScale).toInt(),
                        (fill.destination.y * canvasScale).toInt(),
                    )
                    val dstSize = IntSize(
                        (fill.destination.width * canvasScale).toInt().coerceAtLeast(1),
                        (fill.destination.height * canvasScale).toInt().coerceAtLeast(1),
                    )
                    withTransform({
                        if (camera.isMirrored) {
                            scale(-1f, 1f, pivot = Offset(rect.center.x, rect.center.y))
                        }
                    }) {
                        drawImage(
                            image = frame,
                            srcOffset = srcOffset,
                            srcSize = srcSize,
                            dstOffset = dstOffset,
                            dstSize = dstSize,
                        )
                    }
                } else if (frame != null) {
                    withTransform({
                        if (camera.isMirrored) {
                            scale(-1f, 1f, pivot = Offset(rect.center.x, rect.center.y))
                        }
                    }) {
                        drawImage(
                            image = frame,
                            dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
                            dstSize = IntSize(rect.width.toInt().coerceAtLeast(1), rect.height.toInt().coerceAtLeast(1)),
                        )
                    }
                } else {
                    drawRect(Color(0xFF31353D), rect.topLeft, rect.size)
                    drawCircle(Color(0xFF8E939C), rect.width * 0.12f, rect.center)
                }
            }
            if (camera.borderWidth > 0) {
                drawRoundRect(
                    camera.borderColor.toCompose(),
                    rect.topLeft,
                    rect.size,
                    CornerRadius(rect.width * radiusFraction, rect.width * radiusFraction),
                    style = Stroke(max(1f, (camera.borderWidth / camera.rect.width * rect.width).toFloat())),
                )
            }
        }
    }
}

@Composable
private fun CursorLayer(scene: FrameScene) {
    val cursor = scene.cursor ?: return
    val canvas = scene.canvasSize
    Canvas(Modifier.fillMaxSize()) {
        val scale = size.width / canvas.width.toFloat()
        val projected = scene.screen.projectedQuad.project(
            dev.dogsc.core.model.CompositionPoint(cursor.layout.pointer.x, cursor.layout.pointer.y),
            scene.screen.finalRect,
        )
        val pointer = Offset(
            (projected?.x ?: cursor.layout.pointer.x).toFloat() * scale,
            (projected?.y ?: cursor.layout.pointer.y).toFloat() * scale,
        )
        val height = cursor.layout.height.toFloat() * scale
        val width = cursor.layout.width.toFloat() * scale
        val origin = Offset(
            pointer.x - width * cursor.metrics.hotspot.x.toFloat(),
            pointer.y - height * cursor.metrics.hotspot.y.toFloat(),
        )
        val click = cursor.clickPhase?.let { phase ->
            CursorRenderGeometry.clickGeometry(
                cursor.clickStyle,
                phase.progress,
                cursor.layout.clickEffectHeight,
                cursor.clickOpacity,
                cursor.clickScale,
            )
        }
        if (click != null) drawClickEffect(click, pointer, scale, cursor.effectiveClickColor.toCompose())
        rotate(cursor.rotationRadians.toFloat() * 180f / PI.toFloat(), pointer) {
            drawCursorShape(cursor, origin, width, height, scale)
        }
    }
}

private fun DrawScope.drawCursorShape(
    cursor: FrameCursorScene,
    origin: Offset,
    width: Float,
    height: Float,
    scale: Float,
) {
    val fill = Color(0xFF1B1C20)
    val stroke = Color.White
    when (cursor.assetID) {
        CursorAssetID.TouchDot -> {
            val center = Offset(origin.x + width / 2, origin.y + height / 2)
            drawCircle(Color.White.copy(alpha = 0.30f), width * 0.55f, center)
            drawCircle(Color.White.copy(alpha = 0.92f), width * 0.36f, center)
            drawCircle(Color(0xFF1B1C20).copy(alpha = 0.85f), width * 0.16f, center)
        }
        CursorAssetID.SystemCrosshair -> {
            val center = Offset(origin.x + width / 2, origin.y + height / 2)
            drawLine(stroke, Offset(center.x, center.y - height / 2), Offset(center.x, center.y + height / 2), width * 0.09f)
            drawLine(stroke, Offset(center.x - width / 2, center.y), Offset(center.x + width / 2, center.y), width * 0.09f)
            drawCircle(fill, width * 0.08f, center)
        }
        CursorAssetID.SystemPointingHand, CursorAssetID.SystemOpenHand, CursorAssetID.SystemClosedHand -> {
            val path = Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        origin.x + width * 0.22f,
                        origin.y + height * 0.28f,
                        origin.x + width * 0.78f,
                        origin.y + height,
                        CornerRadius(width * 0.2f),
                    )
                )
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        origin.x + width * 0.34f,
                        origin.y,
                        origin.x + width * 0.50f,
                        origin.y + height * 0.55f,
                        CornerRadius(width * 0.08f),
                    )
                )
            }
            drawPath(path, fill)
            drawPath(path, stroke, style = Stroke(max(1f, width * 0.06f)))
        }
        CursorAssetID.SystemIBeam -> {
            drawLine(fill, Offset(origin.x + width * 0.5f, origin.y), Offset(origin.x + width * 0.5f, origin.y + height), width * 0.12f)
            drawLine(fill, Offset(origin.x + width * 0.25f, origin.y), Offset(origin.x + width * 0.75f, origin.y), width * 0.12f)
            drawLine(fill, Offset(origin.x + width * 0.25f, origin.y + height), Offset(origin.x + width * 0.75f, origin.y + height), width * 0.12f)
        }
        CursorAssetID.SystemNotAllowed -> {
            val center = Offset(origin.x + width / 2, origin.y + height / 2)
            drawCircle(Color(0xFFD94A44), width * 0.44f, center)
            drawCircle(Color.White, width * 0.44f, center, style = Stroke(width * 0.09f))
            drawLine(Color.White, Offset(center.x - width * 0.28f, center.y - height * 0.28f), Offset(center.x + width * 0.28f, center.y + height * 0.28f), width * 0.09f)
        }
        else -> {
            val path = Path().apply {
                moveTo(origin.x, origin.y)
                lineTo(origin.x, origin.y + height * 0.78f)
                lineTo(origin.x + width * 0.26f, origin.y + height * 0.60f)
                lineTo(origin.x + width * 0.44f, origin.y + height)
                lineTo(origin.x + width * 0.62f, origin.y + height * 0.92f)
                lineTo(origin.x + width * 0.44f, origin.y + height * 0.56f)
                lineTo(origin.x + width * 0.78f, origin.y + height * 0.54f)
                close()
            }
            drawPath(path, fill)
            drawPath(path, stroke, style = Stroke(max(1f, width * 0.055f)))
        }
    }
}

private fun DrawScope.drawClickEffect(
    click: CursorClickGeometryFrame,
    center: Offset,
    scale: Float,
    color: Color,
) {
    val c = color
    fun ring(diameter: Double, lineWidth: Double, opacity: Double, filled: Boolean) {
        if (diameter <= 0 || opacity <= 0) return
        val radius = (diameter / 2 * scale).toFloat()
        if (filled) {
            drawCircle(c.copy(alpha = opacity.toFloat()), radius, center)
        } else {
            drawCircle(c.copy(alpha = opacity.toFloat()), radius, center, style = Stroke(max(1f, (lineWidth * scale).toFloat())))
        }
    }
    ring(click.primaryDiameter, click.primaryLineWidth, click.primaryOpacity, click.primaryIsFilled)
    ring(click.secondaryDiameter, click.secondaryLineWidth, click.secondaryOpacity, click.secondaryIsFilled)
}

@Composable
private fun StickerLayer(scene: FrameScene, bitmaps: Map<String, ImageBitmap>) {
    if (scene.stickers.isEmpty()) return
    val canvas = scene.canvasSize
    val ordered = scene.stickers.sortedBy { it.layerIndex }
    // Soft shadows first (under all stickers), blurred with each sticker's authored radius and
    // offset — matches export's BlurMaskFilter instead of the old hard offset block.
    Box(Modifier.fillMaxSize()) {
        for (sticker in ordered) {
            val image = bitmaps[sticker.relativePath] ?: continue
            if (sticker.shadowOpacity <= 0 || sticker.opacity <= 0.001) continue
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val canvasScale = size.width / canvas.width.toFloat()
                        val sigma = (max(sticker.shadowRadius.toFloat(), 1f) * canvasScale).coerceIn(1f, 48f)
                        renderEffect = BlurEffect(sigma, sigma, TileMode.Clamp)
                    },
            ) {
                val w = size.width
                val h = size.height
                val targetWidth = (sticker.width * canvas.width * sticker.scale / canvas.width * w).toFloat()
                val targetHeight = targetWidth * image.height / image.width
                val cx = ((sticker.position.x + sticker.offset.x) * w).toFloat()
                val cy = ((sticker.position.y + sticker.offset.y) * h).toFloat()
                val radius = (sticker.cornerRadius / max(canvas.width, 1.0) * w).toFloat()
                val dx = (sticker.shadowOffset.x / canvas.width * w).toFloat()
                val dy = (sticker.shadowOffset.y / canvas.height * h).toFloat()
                rotate(sticker.rotationRadians.toFloat() * 180f / PI.toFloat(), Offset(cx, cy)) {
                    drawRoundRect(
                        Color.Black.copy(alpha = (sticker.shadowOpacity * RenderParams.StickerShadowAlpha / 255.0).toFloat()),
                        Offset(cx - targetWidth / 2 + dx, cy - targetHeight / 2 + dy),
                        Size(targetWidth, targetHeight),
                        CornerRadius(radius, radius),
                    )
                }
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        for (sticker in ordered) {
            val image = bitmaps[sticker.relativePath] ?: continue
            val targetWidth = (sticker.width * scene.canvasSize.width * sticker.scale / canvas.width * w).toFloat()
            val targetHeight = targetWidth * image.height / image.width
            val cx = ((sticker.position.x + sticker.offset.x) * w).toFloat()
            val cy = ((sticker.position.y + sticker.offset.y) * h).toFloat()
            rotate(sticker.rotationRadians.toFloat() * 180f / PI.toFloat(), Offset(cx, cy)) {
                val left = cx - targetWidth / 2
                val top = cy - targetHeight / 2
                val radius = (sticker.cornerRadius / max(scene.canvasSize.width, 1.0) * w).toFloat()
                val path = Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left, top, left + targetWidth, top + targetHeight,
                            CornerRadius(radius, radius),
                        )
                    )
                }
                clipPath(path) {
                    drawImage(
                        image = image,
                        dstOffset = IntOffset(left.toInt(), top.toInt()),
                        dstSize = IntSize(targetWidth.toInt().coerceAtLeast(1), targetHeight.toInt().coerceAtLeast(1)),
                        alpha = sticker.opacity.toFloat(),
                    )
                }
                if (sticker.borderWidth > 0) {
                    drawRoundRect(
                        sticker.borderColor.toCompose(),
                        Offset(left, top),
                        Size(targetWidth, targetHeight),
                        CornerRadius(radius, radius),
                        style = Stroke(max(1f, sticker.borderWidth.toFloat())),
                    )
                }
            }
        }
    }
}

/**
 * Custom image/video backgrounds blur the same way as export: a downscaled copy (×18) drawn over
 * the sharp image with the blur-radius alpha, before the shared dimming overlay.
 */
@Composable
private fun BackgroundBlurLayer(scene: FrameScene, bitmap: ImageBitmap?) {
    val blur = scene.background.blurRadius
    if (bitmap == null || blur <= 0.1) return
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val block = 18f * size.width / bitmap.width.coerceAtLeast(1)
                val sigma = (block * 0.22f).coerceIn(1.2f, 48f)
                renderEffect = BlurEffect(sigma, sigma, TileMode.Clamp)
                alpha = (blur / 96.0).toFloat().coerceIn(0f, 1f)
            },
    ) {
        drawImageCover(bitmap, 0f, 0f, size.width, size.height)
    }
}

/** Center-crop cover draw, matching the export renderer's `drawCover`. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawImageCover(
    image: ImageBitmap,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
) {
    if (width <= 0f || height <= 0f) return
    val scale = max(width / image.width, height / image.height)
    val srcW = (width / scale).coerceAtMost(image.width.toFloat())
    val srcH = (height / scale).coerceAtMost(image.height.toFloat())
    val srcX = (image.width - srcW) / 2f
    val srcY = (image.height - srcH) / 2f
    drawImage(
        image = image,
        srcOffset = IntOffset(srcX.toInt(), srcY.toInt()),
        srcSize = IntSize(srcW.toInt().coerceAtLeast(1), srcH.toInt().coerceAtLeast(1)),
        dstOffset = IntOffset(left.toInt(), top.toInt()),
        dstSize = IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)),
    )
}

/**
 * Blur mosaics must show the same blurred screen content as export. Export downsamples the frame
 * by 12 and clips it to the mosaic rect; the preview applies an equivalent Gaussian blur to the
 * same rect and alpha, instead of the old flat translucent overlay.
 */
@Composable
private fun MosaicBlurLayer(scene: FrameScene, frame: ImageBitmap?) {
    if (frame == null) return
    val mosaics = scene.screen.mosaics.filter {
        it.style == MosaicEffectStyle.BLUR && it.intensity * it.transitionProgress > 0.01
    }
    if (mosaics.isEmpty()) return
    val canvas = scene.canvasSize
    val content = scene.screen.finalRect
    for (mosaic in mosaics) {
        val alpha = (mosaic.intensity * mosaic.transitionProgress * RenderParams.MosaicAlphaCeiling).toFloat().coerceIn(0f, 1f)
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val previewScale = size.width / canvas.width.toFloat()
                    // Shared with export: downscale factor + sigma coefficient keep the preview's
                    // detail loss in the same range as the export's downscale/upscale blur.
                    val block = RenderParams.MosaicBlurDivisor.toFloat() * content.width.toFloat() * previewScale / frame.width.coerceAtLeast(1)
                    val sigma = (block * RenderParams.PreviewBlurSigmaFactor.toFloat())
                        .coerceIn(RenderParams.PreviewBlurSigmaMin.toFloat(), RenderParams.PreviewBlurSigmaMax.toFloat())
                    renderEffect = BlurEffect(sigma, sigma, TileMode.Clamp)
                    this.alpha = alpha
                },
        ) {
            val sx = size.width / canvas.width.toFloat()
            val sy = size.height / canvas.height.toFloat()
            val left = ((content.x + mosaic.sourceRect.x * content.width) * sx).toFloat()
            val top = ((content.y + mosaic.sourceRect.y * content.height) * sy).toFloat()
            val right = ((content.x + (mosaic.sourceRect.x + mosaic.sourceRect.width) * content.width) * sx).toFloat()
            val bottom = ((content.y + (mosaic.sourceRect.y + mosaic.sourceRect.height) * content.height) * sy).toFloat()
            val radius = (mosaic.cornerRadius * min(right - left, bottom - top)).toFloat().coerceAtLeast(0f)
            val clip = Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left, top, right, bottom, CornerRadius(radius, radius),
                    )
                )
            }
            clipPath(clip) {
                withTransform({ scale(sx, sy) }) {
                    drawScreenFrame(
                        frame,
                        scene.screen,
                        Rect(content.x.toFloat(), content.y.toFloat(), content.right.toFloat(), content.bottom.toFloat()),
                    )
                }
            }
        }
    }
}

/**
 * Sticker backdrop blur: a sticker's "录屏虚化" blurs the whole screen card behind it.
 * Activates FrameStickerBackdropScene.screenBlur, previously computed but never drawn.
 */
@Composable
private fun StickerBackdropBlurLayer(scene: FrameScene, frame: ImageBitmap?) {
    if (frame == null) return
    val blur = scene.stickerBackdrop.screenBlur
    if (blur <= 0.1) return
    val canvas = scene.canvasSize
    val content = scene.screen.finalRect
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val previewScale = size.width / canvas.width.toFloat()
                val sigma = (blur.toFloat() * previewScale * 0.5f)
                    .coerceIn(RenderParams.PreviewBlurSigmaMin.toFloat(), RenderParams.PreviewBlurSigmaMax.toFloat())
                renderEffect = BlurEffect(sigma, sigma, TileMode.Clamp)
            },
    ) {
        val sx = size.width / canvas.width.toFloat()
        val sy = size.height / canvas.height.toFloat()
        withTransform({ scale(sx, sy) }) {
            drawScreenFrame(
                frame,
                scene.screen,
                Rect(content.x.toFloat(), content.y.toFloat(), content.right.toFloat(), content.bottom.toFloat()),
            )
        }
    }
}

/**
 * Focus blur: everything outside the clear region (radial circle or linear band) is blurred,
 * with a soft feather at the boundary. Activates FocusEffect.blur/softness, which previously
 * only dimmed. Shares the clear-region geometry with export so the two match.
 */
@Composable
private fun FocusBlurLayer(scene: FrameScene, frame: ImageBitmap?) {
    if (frame == null) return
    val focus = scene.screen.focusEffect ?: return
    val strength = focus.effect.blur * focus.progress
    if (strength <= 0.01) return
    val canvas = scene.canvasSize
    val content = scene.screen.finalRect
    val region = OverlayGeometry.focusClearRegion(content, focus.effect, focus.center.x, focus.center.y)
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val previewScale = size.width / canvas.width.toFloat()
                val block = RenderParams.MosaicBlurDivisor.toFloat() * content.width.toFloat() * previewScale / frame.width.coerceAtLeast(1)
                val sigma = (block * RenderParams.PreviewBlurSigmaFactor.toFloat() * (0.5f + strength.toFloat()))
                    .coerceIn(RenderParams.PreviewBlurSigmaMin.toFloat(), RenderParams.PreviewBlurSigmaMax.toFloat())
                renderEffect = BlurEffect(sigma, sigma, TileMode.Clamp)
                alpha = strength.toFloat().coerceIn(0f, 1f)
            },
    ) {
        val sx = size.width / canvas.width.toFloat()
        val sy = size.height / canvas.height.toFloat()
        val cx = (region.center.x * sx).toFloat()
        val cy = (region.center.y * sy).toFloat()
        val half = (region.halfWidth * sx).toFloat()
        val cLeft = (content.x * sx).toFloat()
        val cTop = (content.y * sy).toFloat()
        val cRight = (content.right * sx).toFloat()
        val cBottom = (content.bottom * sy).toFloat()
        // Even-odd path: content rect minus the clear region -> blur covers only the outside.
        val blurPath = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(cLeft, cTop, cRight, cBottom))
            if (region.isLinear) {
                // Clear band is drawn rotated; approximate with an axis-aligned band here and
                // let the dimming layer carry the exact rotation (blur is isotropic).
                addRect(Rect(cLeft, cy - half, cRight, cy + half))
            } else {
                addOval(Rect(cx - half, cy - half, cx + half, cy + half))
            }
        }
        clipPath(blurPath) {
            withTransform({ scale(sx, sy) }) {
                drawScreenFrame(
                    frame,
                    scene.screen,
                    Rect(content.x.toFloat(), content.y.toFloat(), content.right.toFloat(), content.bottom.toFloat()),
                )
            }
        }
        // The clear-region edge is softened by the blur's own sigma; the dimming layer carries
        // the exact rotated/feathered boundary.
    }
}

@Composable
private fun MosaicLayer(scene: FrameScene) {
    if (scene.screen.mosaics.isEmpty() && scene.screen.focusEffect == null) return
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Overlay coordinates are content-relative (same contract as export). Map the screen
        // card into preview pixels once, then derive every region/center from it.
        val content = scene.screen.finalRect
        val sx = w / scene.canvasSize.width.toFloat()
        val sy = h / scene.canvasSize.height.toFloat()
        val cLeft = (content.x * sx).toFloat()
        val cTop = (content.y * sy).toFloat()
        val cRight = (content.right * sx).toFloat()
        val cBottom = (content.bottom * sy).toFloat()
        val cWidth = cRight - cLeft
        val cHeight = cBottom - cTop
        for (mosaic in scene.screen.mosaics) {
            val mapped = OverlayGeometry.overlayRect(content, mosaic.sourceRect)
            val left = (mapped.x * sx).toFloat()
            val top = (mapped.y * sy).toFloat()
            val width = (mapped.width * sx).toFloat()
            val height = (mapped.height * sy).toFloat()
            val radius = (mosaic.cornerRadius * min(width, height)).toFloat()
            // BLUR mosaics are drawn by MosaicBlurLayer; only spotlight dimming belongs here.
            if (mosaic.style == MosaicEffectStyle.SPOTLIGHT) {
                val path = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(cLeft, cTop, cRight, cBottom))
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left, top, left + width, top + height, CornerRadius(radius, radius),
                        )
                    )
                }
                drawPath(path, Color.Black.copy(alpha = (mosaic.spotlightDimming * mosaic.transitionProgress).toFloat()))
            }
        }
        scene.screen.focusEffect?.let { focus ->
            val alpha = (focus.effect.dimming * focus.progress).toFloat()
            if (alpha > 0.01f) {
                val center = OverlayGeometry.focusCenter(content, focus.center.x, focus.center.y)
                val cx = (center.x * sx).toFloat()
                val cy = (center.y * sy).toFloat()
                val half = OverlayGeometry.focusHalfWidth(
                    content,
                    focus.effect.clearHalfWidth(min(content.width, content.height)),
                )
                if (focus.effect.shape == dev.dogsc.core.model.FocusEffectShape.LINEAR) {
                    val angle = (focus.effect.angleDegrees ?: 0.0) * PI / 180
                    val band = (half * sx).toFloat()
                    rotate((angle * 180 / PI).toFloat(), Offset(cx, cy)) {
                        drawRect(Color.Black.copy(alpha = alpha), Offset(cLeft, cTop), Size(cWidth, cy - band - cTop))
                        drawRect(Color.Black.copy(alpha = alpha), Offset(cLeft, cy + band), Size(cWidth, cBottom - cy - band))
                    }
                } else {
                    val radius = (half * sx).toFloat()
                    val path = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(cLeft, cTop, cRight, cBottom))
                        addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius))
                    }
                    drawPath(path, Color.Black.copy(alpha = alpha))
                }
            }
        }
    }
}

private fun DrawScope.drawBackground(scene: FrameScene, bitmap: ImageBitmap?, canvasSize: Size) {
    val source = scene.background.source
    when (source) {
        is BackgroundSource.Pattern -> drawPattern(source.preset, scene.background, canvasSize)
        is BackgroundSource.DynamicFlow -> drawDynamic(source.preset, scene.background, canvasSize, scene.time)
        else -> {
            if (bitmap != null) {
                drawImageCover(bitmap, 0f, 0f, size.width, size.height)
            } else {
                drawRect(Brush.linearGradient(listOf(Color(0xFF202633), Color(0xFF0C0F16))))
            }
            if (scene.background.blurRadius > 0.1) {
                // Same dimming factor as the export renderer (blurRadius / 96 × 90).
                drawRect(Color(0xFF0B0E14).copy(alpha = min(0.35f, (scene.background.blurRadius / 96 * 90 / 255).toFloat())))
            }
        }
    }
}

private fun DrawScope.drawPattern(
    preset: BackgroundPatternPreset,
    background: dev.dogsc.core.scene.FrameBackgroundScene,
    canvasSize: Size,
) {
    val w = size.width
    val h = size.height
    val base = when (preset) {
        BackgroundPatternPreset.OBSIDIAN_GRID -> Color(0xFF0D1017)
        BackgroundPatternPreset.ENGINEERING_WHITE_GRID -> Color(0xFFF5F4F1)
        BackgroundPatternPreset.MIDNIGHT_DOTS -> Color(0xFF0A0C12)
        BackgroundPatternPreset.ARCHITECTURAL_DOTS -> Color(0xFFEFEDE8)
        BackgroundPatternPreset.ISOMETRIC_MESH -> Color(0xFF10141C)
    }
    drawRect(base)
    val ink = if (preset == BackgroundPatternPreset.ENGINEERING_WHITE_GRID ||
        preset == BackgroundPatternPreset.ARCHITECTURAL_DOTS
    ) Color(0xFF1A1F2E).copy(alpha = 0.10f) else Color.White.copy(alpha = 0.08f)
    val opacity = background.patternOpacity.toFloat()
    val step = (36f * background.patternScale).toFloat().coerceAtLeast(8f)
    when (preset) {
        BackgroundPatternPreset.OBSIDIAN_GRID, BackgroundPatternPreset.ENGINEERING_WHITE_GRID -> {
            var x = 0f
            while (x < w) {
                drawLine(ink.copy(alpha = ink.alpha * opacity), Offset(x, 0f), Offset(x, h), 1f)
                x += step
            }
            var y = 0f
            while (y < h) {
                drawLine(ink.copy(alpha = ink.alpha * opacity), Offset(0f, y), Offset(w, y), 1f)
                y += step
            }
        }
        BackgroundPatternPreset.MIDNIGHT_DOTS, BackgroundPatternPreset.ARCHITECTURAL_DOTS -> {
            var y = step / 2
            while (y < h) {
                var x = step / 2
                while (x < w) {
                    drawCircle(ink.copy(alpha = ink.alpha * opacity), 1.2f * background.patternScale.toFloat(), Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        BackgroundPatternPreset.ISOMETRIC_MESH -> {
            val slant = step * 0.5f
            var x = -w
            while (x < w * 2) {
                drawLine(ink.copy(alpha = ink.alpha * opacity), Offset(x, 0f), Offset(x + h * 0.58f, h), 1f)
                x += step
            }
            var x2 = 0f
            while (x2 < w * 2) {
                drawLine(ink.copy(alpha = ink.alpha * opacity), Offset(x2, 0f), Offset(x2 - h * 0.58f, h), 1f)
                x2 += step
            }
            var y = 0f
            while (y < h) {
                drawLine(ink.copy(alpha = ink.alpha * opacity), Offset(0f, y), Offset(w, y), 1f)
                y += step * 0.86f
            }
        }
    }
    if (background.patternOpacity < 1.0) {
        drawRect(base.copy(alpha = (1 - background.patternOpacity).toFloat()))
    }
}

private fun DrawScope.drawDynamic(
    preset: DynamicBackgroundPreset,
    background: dev.dogsc.core.scene.FrameBackgroundScene,
    canvasSize: Size,
    time: Double,
) {
    val colors = dev.dogsc.core.scene.DynamicBackgroundGeometry.gradientColors(preset).map { Color(0xFF000000.toInt() or it) }
    val (dx, dy) = dev.dogsc.core.scene.DynamicBackgroundGeometry.drift(time)
    val driftX = (dx * size.width).toFloat()
    val driftY = (dy * size.height).toFloat()
    drawRect(Brush.linearGradient(colors, start = Offset(driftX, driftY), end = Offset(size.width + driftX, size.height + driftY)))
    // Starfield dots and glow orbs come from the same pure geometry export uses, so the two
    // backends draw identical motion at any playhead time.
    val radiusScale = size.minDimension.toDouble()
    if (preset == DynamicBackgroundPreset.STARFIELD_DOTS) {
        for (star in dev.dogsc.core.scene.DynamicBackgroundGeometry.stars(time, radiusScale)) {
            drawCircle(
                Color.White.copy(alpha = star.alpha.toFloat()),
                star.radius.toFloat(),
                Offset((star.x * size.width).toFloat(), (star.y * size.height).toFloat()),
            )
        }
    } else {
        for (orb in dev.dogsc.core.scene.DynamicBackgroundGeometry.orbs(time, radiusScale)) {
            drawCircle(
                Color.White.copy(alpha = orb.alpha.toFloat()),
                orb.radius.toFloat(),
                Offset((orb.x * size.width).toFloat(), (orb.y * size.height).toFloat()),
            )
        }
    }
}

private fun DrawScope.drawGrid(rect: Rect, color: Color, step: Float) {
    var x = rect.left
    while (x < rect.right) {
        drawLine(color, Offset(x, rect.top), Offset(x, rect.bottom), 1f)
        x += step
    }
    var y = rect.top
    while (y < rect.bottom) {
        drawLine(color, Offset(rect.left, y), Offset(rect.right, y), 1f)
        y += step
    }
}

private fun DrawScope.drawSafeGuides(canvasSize: Size) {
    val insetX = canvasSize.width * 0.08f
    val insetY = canvasSize.height * 0.08f
    drawRect(
        Color.White.copy(alpha = 0.22f),
        Offset(insetX, insetY),
        Size(canvasSize.width - insetX * 2, canvasSize.height - insetY * 2),
        style = Stroke(1f),
    )
}

fun HexColor.toCompose(): Color = Color(0xFF000000.toInt() or rgb24)
