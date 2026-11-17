package dev.dogsc.core.scene

import dev.dogsc.core.model.CameraShape
import dev.dogsc.core.model.CameraStyle
import dev.dogsc.core.model.CompositionPoint
import dev.dogsc.core.model.CompositionRect
import dev.dogsc.core.model.CompositionSize
import dev.dogsc.core.model.CursorAssetID
import dev.dogsc.core.model.CursorClickEffectStyle
import dev.dogsc.core.model.CursorStyle
import dev.dogsc.core.model.HexColor
import dev.dogsc.core.model.MotionStyle
import dev.dogsc.core.model.NormalizedCrop
import dev.dogsc.core.model.NormalizedPoint
import dev.dogsc.core.model.RecorderProject
import dev.dogsc.core.model.ScreenFrameStyle
import dev.dogsc.core.model.ScreenMotionClip
import dev.dogsc.core.model.ScreenMotionState
import dev.dogsc.core.model.ScreenMotionTrack
import dev.dogsc.core.model.ScreenAnchorViewport
import dev.dogsc.core.model.ZoomAnimationTrack
import dev.dogsc.core.model.ZoomSample
import dev.dogsc.core.model.ZoomViewportTransform
import dev.dogsc.core.model.CameraMotionTrack
import dev.dogsc.core.model.CameraMotionState
import dev.dogsc.core.model.CameraLayoutMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

data class ScreenDecorationInsets(
    val top: Double = 0.0,
    val right: Double = 0.0,
    val bottom: Double = 0.0,
    val left: Double = 0.0,
) {
    companion object {
        val Zero = ScreenDecorationInsets()
    }
}

object ScreenFrameGeometry {
    fun decorationInsetsAtScaleOne(
        style: ScreenFrameStyle,
        frameScale: Double,
        toolbarScale: Double = 1.0,
        fittedWidth: Double,
        fittedHeight: Double,
        styleScale: Double,
    ): ScreenDecorationInsets {
        if (style == ScreenFrameStyle.NONE) return ScreenDecorationInsets.Zero
        val safeStyleScale = max(styleScale, 0.0001)
        val authoredFrameScale = frameScale.coerceIn(0.6, 1.6)
        val authoredToolbarScale = toolbarScale.coerceIn(0.65, 1.6)
        val visualScale = safeStyleScale * authoredFrameScale
        return when (style) {
            ScreenFrameStyle.NONE -> ScreenDecorationInsets.Zero
            ScreenFrameStyle.WINDOW_LIGHT, ScreenFrameStyle.WINDOW_DARK,
            ScreenFrameStyle.BROWSER_LIGHT, ScreenFrameStyle.BROWSER_DARK -> {
                val idealHeight = 46 * visualScale * authoredToolbarScale
                ScreenDecorationInsets(
                    top = min(
                        max(idealHeight, 4 * safeStyleScale),
                        max(max(fittedHeight, 0.0) * 0.25, 4 * safeStyleScale),
                    )
                )
            }
            ScreenFrameStyle.DEVICE_PHONE -> {
                val shortEdge = max(min(fittedWidth, fittedHeight), 1.0)
                val bezel = min(max(shortEdge * 0.026 * authoredFrameScale, 2 * safeStyleScale), shortEdge * 0.07)
                ScreenDecorationInsets(bezel, bezel, bezel, bezel)
            }
            ScreenFrameStyle.DEVICE_TABLET -> {
                val shortEdge = max(min(fittedWidth, fittedHeight), 1.0)
                val bezel = min(max(shortEdge * 0.035 * authoredFrameScale, 2 * safeStyleScale), shortEdge * 0.08)
                ScreenDecorationInsets(bezel, bezel, bezel, bezel)
            }
        }
    }
}

data class ScreenSceneEvaluation(
    val fittedRect: CompositionRect,
    val baseRect: CompositionRect,
    val finalRect: CompositionRect,
    val manualScale: Double,
    val manualOffset: CompositionPoint,
    val rotationX: Double,
    val rotationY: Double,
    val rotationZ: Double,
    val perspective: Double,
    val projectionAnchor: NormalizedPoint,
    val baseCornerRadius: Double,
    val baseBorderWidth: Double,
    val finalCornerRadius: Double,
    val finalBorderWidth: Double,
    val zoom: ZoomSample,
    val viewport: ZoomViewportTransform,
) {
    val projectedQuad: ProjectedScreenQuad
        get() {
            val anchor = CompositionPoint(
                finalRect.x + finalRect.width * projectionAnchor.x,
                finalRect.y + finalRect.height * projectionAnchor.y,
            )
            return ScreenProjection.project(
                finalRect, rotationX, rotationY, rotationZ, perspective, anchor,
            )
        }
}

data class CameraSceneEvaluation(
    val rect: CompositionRect,
    val cornerRadius: Double,
    val borderWidth: Double,
    val zoomScale: Double,
    val opacity: Double,
    val fullscreenProgress: Double,
)

data class CompositionSceneEvaluation(
    val screen: ScreenSceneEvaluation,
    val camera: CameraSceneEvaluation?,
)

object CompositionSceneEvaluator {
    fun canonicalStyleScale(canvasWidth: Double, canvasHeight: Double): Double =
        max(min(canvasWidth, canvasHeight), 1.0) / 1080.0

    fun evaluate(
        project: RecorderProject,
        time: Double,
        canvasWidth: Double,
        canvasHeight: Double,
        sourceAspectRatio: Double,
        cameraAspectRatio: Double? = null,
        styleScale: Double = 1.0,
        automaticZoomFocus: NormalizedPoint? = null,
        inheritedAutomaticZoomFocus: NormalizedPoint? = null,
        reanchorAutomaticEntry: Boolean = false,
        zoomTrack: ZoomAnimationTrack? = null,
        screenMotionTrack: ScreenMotionTrack? = null,
        cameraMotionTrack: CameraMotionTrack? = null,
    ): CompositionSceneEvaluation {
        val width = max(canvasWidth, 2.0)
        val height = max(canvasHeight, 2.0)
        val scale = max(styleScale, 0.0001)
        val crop = project.canvas.crop.clamped()
        val croppedAspect = max(sourceAspectRatio * crop.width / crop.height, 0.01)
        val maximumPadding = min(width, height) * 0.42
        val padding = (project.canvas.padding * scale).coerceIn(0.0, maximumPadding)
        val availableWidth = max(width - padding * 2, 2.0)
        val availableHeight = max(height - padding * 2, 2.0)
        val fitted = aspectFit(croppedAspect, availableWidth, availableHeight)
        val fittedRect = CompositionRect(
            (width - fitted.first) / 2,
            (height - fitted.second) / 2,
            fitted.first,
            fitted.second,
        )
        val sampleTime = if (time.isFinite()) max(time, 0.0) else 0.0
        val baseScreenMotion = ScreenMotionState(
            position = project.canvas.contentPosition,
            scale = project.canvas.contentScale,
        )
        val borderWidthAtScaleOne = project.canvas.borderWidth.coerceIn(0.0, 60.0) * scale
        val decorationInsets = ScreenFrameGeometry.decorationInsetsAtScaleOne(
            style = project.canvas.screenFrame,
            frameScale = project.canvas.screenFrameScale,
            toolbarScale = project.canvas.screenFrameToolbarScale,
            fittedWidth = fittedRect.width,
            fittedHeight = fittedRect.height,
            styleScale = scale,
        )
        val resolvedScreenTrack = screenMotionTrack ?: ScreenMotionTrack(project.timeline.screenMotionClips)
        val screenMotionSample = resolvedScreenTrack.sampleLayout(
            at = sampleTime,
            base = baseScreenMotion,
            motion = project.motion,
            anchorViewport = ScreenAnchorViewport(
                canvasWidth = width,
                canvasHeight = height,
                fittedWidth = fittedRect.width,
                fittedHeight = fittedRect.height,
                borderWidthAtScaleOne = borderWidthAtScaleOne,
                decorationTopAtScaleOne = decorationInsets.top,
                decorationRightAtScaleOne = decorationInsets.right,
                decorationBottomAtScaleOne = decorationInsets.bottom,
                decorationLeftAtScaleOne = decorationInsets.left,
            ),
        )
        val screenMotion = screenMotionSample.state
        val manualScale = screenMotion.scale.coerceIn(0.2, 4.0)
        val positionX = if (screenMotion.position.x.isFinite()) screenMotion.position.x else 0.5
        val positionY = if (screenMotion.position.y.isFinite()) screenMotion.position.y else 0.5
        val scaledWidth = fittedRect.width * manualScale
        val scaledHeight = fittedRect.height * manualScale
        val baseBorderWidth = borderWidthAtScaleOne * manualScale
        val decoratedWidth = scaledWidth + baseBorderWidth * 2
        val decoratedHeight = scaledHeight + baseBorderWidth * 2
        val offsetX = screenMotionSample.manualOffset?.x
            ?: (positionX - 0.5) * (width - decoratedWidth)
        val offsetY = screenMotionSample.manualOffset?.y
            ?: (positionY - 0.5) * (height - decoratedHeight)
        val baseRect = CompositionRect(
            fittedRect.midX - fittedRect.width * manualScale / 2 + offsetX,
            fittedRect.midY - fittedRect.height * manualScale / 2 + offsetY,
            fittedRect.width * manualScale,
            fittedRect.height * manualScale,
        )
        val zoom = zoomTrack?.sample(
            sampleTime,
            project.motion,
            automaticZoomFocus,
            inheritedAutomaticZoomFocus,
            reanchorAutomaticEntry,
        ) ?: ZoomAnimationTrack(project.timeline.zoomClips).sample(
            sampleTime,
            project.motion,
            automaticZoomFocus,
            inheritedAutomaticZoomFocus,
            reanchorAutomaticEntry,
        )
        val coverageTolerance = max(width, height) * 0.000000001
        val keepsCanvasCovered = project.canvas.screenFrame == ScreenFrameStyle.NONE &&
            resolvedScreenTrack.activeClip(sampleTime) == null &&
            baseRect.x <= coverageTolerance && baseRect.y <= coverageTolerance &&
            baseRect.right >= width - coverageTolerance && baseRect.bottom >= height - coverageTolerance
        val visibleRect = if (keepsCanvasCovered) {
            CompositionRect(
                -baseRect.x / baseRect.width,
                -baseRect.y / baseRect.height,
                width / baseRect.width,
                height / baseRect.height,
            )
        } else {
            null
        }
        val viewport = ZoomViewportTransform.make(zoom, crop, visibleRect)
        val decorationGrowth = manualScale * (viewport.scale - 1) / 2
        val finalRect = CompositionRect(
            baseRect.midX - baseRect.width * viewport.scale / 2 +
                viewport.translation.x * baseRect.width +
                (decorationInsets.left - decorationInsets.right) * decorationGrowth,
            baseRect.midY - baseRect.height * viewport.scale / 2 +
                viewport.translation.y * baseRect.height +
                (decorationInsets.top - decorationInsets.bottom) * decorationGrowth,
            baseRect.width * viewport.scale,
            baseRect.height * viewport.scale,
        )
        val screen = ScreenSceneEvaluation(
            fittedRect = fittedRect,
            baseRect = baseRect,
            finalRect = finalRect,
            manualScale = manualScale,
            manualOffset = CompositionPoint(offsetX, offsetY),
            rotationX = screenMotion.rotationX.coerceIn(-89.0, 89.0),
            rotationY = screenMotion.rotationY.coerceIn(-89.0, 89.0),
            rotationZ = screenMotion.rotationZ,
            perspective = screenMotion.perspective.coerceIn(0.0, 2.0),
            projectionAnchor = screenMotionSample.projectionAnchor,
            baseCornerRadius = max(project.canvas.cornerRadius, 0.0) * scale * manualScale,
            baseBorderWidth = baseBorderWidth,
            finalCornerRadius = if (keepsCanvasCovered) 0.0 else
                max(project.canvas.cornerRadius, 0.0) * scale * manualScale * viewport.scale,
            finalBorderWidth = baseBorderWidth * viewport.scale,
            zoom = zoom,
            viewport = viewport,
        )
        val camera = cameraAspectRatio?.let { aspect ->
            val baseCameraMotion = CameraMotionState(
                layout = CameraLayoutMode.Shape(project.camera.shape),
                position = project.camera.position,
                size = project.camera.size,
                roundness = project.camera.roundness,
                opacity = 1.0,
            )
            val cameraMotion = (cameraMotionTrack ?: CameraMotionTrack(project.timeline.cameraMotionClips)).sample(
                sampleTime,
                baseCameraMotion,
                aspect,
                width / height,
                project.motion,
            )
            evaluateCamera(
                project.camera,
                cameraMotion,
                zoom.scale,
                width,
                height,
                scale,
            )
        }
        return CompositionSceneEvaluation(screen, camera)
    }

    fun evaluateCamera(
        style: CameraStyle,
        cameraAspectRatio: Double,
        screenZoomScale: Double,
        canvasWidth: Double,
        canvasHeight: Double,
        styleScale: Double = 1.0,
    ): CameraSceneEvaluation {
        val base = CameraMotionState(
            layout = CameraLayoutMode.Shape(style.shape),
            position = style.position,
            size = style.size,
            roundness = style.roundness,
            opacity = 1.0,
        )
        val sample = CameraMotionTrack(emptyList()).sample(
            0.0, base, cameraAspectRatio, max(canvasWidth, 2.0) / max(canvasHeight, 2.0),
        )
        return evaluateCamera(style, sample, screenZoomScale, canvasWidth, canvasHeight, styleScale)
    }

    fun evaluateCamera(
        style: CameraStyle,
        sample: dev.dogsc.core.model.CameraMotionSample,
        screenZoomScale: Double,
        canvasWidth: Double,
        canvasHeight: Double,
        styleScale: Double = 1.0,
    ): CameraSceneEvaluation {
        val width = max(canvasWidth, 2.0)
        val height = max(canvasHeight, 2.0)
        val base = min(width, height)
        val zoomProgress = ((screenZoomScale - 1) / 0.565).coerceIn(0.0, 1.0)
        val zoomScale = 1 + (style.scaleDuringZoom - 1) * zoomProgress
        val preferredCameraHeight = min(
            max(base * sample.size * zoomScale, 72 * max(styleScale, 0.0001)),
            base * 1.2,
        )
        val preferredCameraWidth = preferredCameraHeight * max(sample.aspectRatio, 0.01)
        val fitScale = minOf(
            1.0,
            width * 1.2 / max(preferredCameraWidth, 0.0001),
            height * 1.2 / max(preferredCameraHeight, 0.0001),
        )
        val cameraWidth = preferredCameraWidth * fitScale
        val cameraHeight = preferredCameraHeight * fitScale
        val positionX = sample.position.x.coerceIn(0.0, 1.0)
        val positionY = sample.position.y.coerceIn(0.0, 1.0)
        val shapedRect = CompositionRect(
            positionX * (width - cameraWidth),
            positionY * (height - cameraHeight),
            cameraWidth,
            cameraHeight,
        )
        val fullscreenProgress = sample.fullscreenProgress.coerceIn(0.0, 1.0)
        val rect = CompositionRect(
            mix(shapedRect.x, 0.0, fullscreenProgress),
            mix(shapedRect.y, 0.0, fullscreenProgress),
            mix(shapedRect.width, width, fullscreenProgress),
            mix(shapedRect.height, height, fullscreenProgress),
        )
        val radius = min(rect.width, rect.height) * sample.cornerFraction.coerceIn(0.0, 0.5)
        return CameraSceneEvaluation(
            rect = rect,
            cornerRadius = radius,
            borderWidth = style.borderWidth.coerceIn(0.0, 18.0) * max(styleScale, 0.0001) * (1 - fullscreenProgress),
            zoomScale = zoomScale,
            opacity = sample.opacity.coerceIn(0.0, 1.0),
            fullscreenProgress = fullscreenProgress,
        )
    }

    private fun mix(from: Double, to: Double, progress: Double): Double = from + (to - from) * progress

    private fun aspectFit(aspectRatio: Double, width: Double, height: Double): Pair<Double, Double> {
        val safeAspect = max(aspectRatio, 0.01)
        return if (width / max(height, 1.0) > safeAspect) {
            height * safeAspect to height
        } else {
            width to width / safeAspect
        }
    }
}

data class ProjectedScreenQuad(
    val topLeft: CompositionPoint,
    val topRight: CompositionPoint,
    val bottomRight: CompositionPoint,
    val bottomLeft: CompositionPoint,
) {
    val corners: List<CompositionPoint> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)
    val bounds: CompositionRect
        get() {
            val minX = corners.minOf { it.x }
            val maxX = corners.maxOf { it.x }
            val minY = corners.minOf { it.y }
            val maxY = corners.maxOf { it.y }
            return CompositionRect(minX, minY, max(maxX - minX, 0.0), max(maxY - minY, 0.0))
        }
    val center: CompositionPoint
        get() = CompositionPoint(corners.sumOf { it.x } / 4, corners.sumOf { it.y } / 4)
    val resizeHandlePoint: CompositionPoint get() = bottomRight

    /**
     * Row-major 3×3 homography that maps points authored inside [sourceRect] onto this quad.
     * Preview and export both warp the screen card with this matrix, so the two stay identical.
     */
    fun homography(sourceRect: CompositionRect): DoubleArray? {
        if (sourceRect.width <= 0 || sourceRect.height <= 0) return null
        val dx1 = topRight.x - bottomRight.x
        val dx2 = bottomLeft.x - bottomRight.x
        val dx3 = topLeft.x - topRight.x + bottomRight.x - bottomLeft.x
        val dy1 = topRight.y - bottomRight.y
        val dy2 = bottomLeft.y - bottomRight.y
        val dy3 = topLeft.y - topRight.y + bottomRight.y - bottomLeft.y
        val denominator = dx1 * dy2 - dx2 * dy1
        val px: Double
        val py: Double
        if (kotlin.math.abs(dx3) <= 1e-9 && kotlin.math.abs(dy3) <= 1e-9) {
            px = 0.0; py = 0.0
        } else {
            if (kotlin.math.abs(denominator) <= 1e-9) return null
            px = (dx3 * dy2 - dx2 * dy3) / denominator
            py = (dx1 * dy3 - dx3 * dy1) / denominator
        }
        val a = topRight.x - topLeft.x + px * topRight.x
        val b = bottomLeft.x - topLeft.x + py * bottomLeft.x
        val d = topRight.y - topLeft.y + px * topRight.y
        val e = bottomLeft.y - topLeft.y + py * bottomLeft.y
        val sx = 1.0 / sourceRect.width
        val sy = 1.0 / sourceRect.height
        val ox = -sourceRect.x * sx
        val oy = -sourceRect.y * sy
        return doubleArrayOf(
            a * sx, b * sy, a * ox + b * oy + topLeft.x,
            d * sx, e * sy, d * ox + e * oy + topLeft.y,
            px * sx, py * sy, px * ox + py * oy + 1.0,
        )
    }

    /**
     * Inverse of [project]: maps a canvas-space point back into [sourceRect] authoring space.
     * Used by canvas hit-testing so a tap on a tilted/3D screen card resolves to the same
     * content coordinate the renderer drew. Returns null when the homography is degenerate.
     */
    fun unproject(point: CompositionPoint, sourceRect: CompositionRect): CompositionPoint? {
        val h = homography(sourceRect) ?: return null
        val inv = invert3x3(h) ?: return null
        val divisor = inv[6] * point.x + inv[7] * point.y + inv[8]
        if (kotlin.math.abs(divisor) <= 1e-9) return null
        return CompositionPoint(
            (inv[0] * point.x + inv[1] * point.y + inv[2]) / divisor,
            (inv[3] * point.x + inv[4] * point.y + inv[5]) / divisor,
        )
    }

    /** Projects a point authored inside [sourceRect] through the quad homography. */
    fun project(point: CompositionPoint, sourceRect: CompositionRect): CompositionPoint? {
        if (sourceRect.width <= 0 || sourceRect.height <= 0) return null
        val dx1 = topRight.x - bottomRight.x
        val dx2 = bottomLeft.x - bottomRight.x
        val dx3 = topLeft.x - topRight.x + bottomRight.x - bottomLeft.x
        val dy1 = topRight.y - bottomRight.y
        val dy2 = bottomLeft.y - bottomRight.y
        val dy3 = topLeft.y - topRight.y + bottomRight.y - bottomLeft.y
        val denominator = dx1 * dy2 - dx2 * dy1
        val px: Double
        val py: Double
        if (kotlin.math.abs(dx3) <= 1e-9 && kotlin.math.abs(dy3) <= 1e-9) {
            px = 0.0; py = 0.0
        } else {
            if (kotlin.math.abs(denominator) <= 1e-9) return null
            px = (dx3 * dy2 - dx2 * dy3) / denominator
            py = (dx1 * dy3 - dx3 * dy1) / denominator
        }
        val a = topRight.x - topLeft.x + px * topRight.x
        val b = bottomLeft.x - topLeft.x + py * bottomLeft.x
        val d = topRight.y - topLeft.y + px * topRight.y
        val e = bottomLeft.y - topLeft.y + py * bottomLeft.y
        val x = (point.x - sourceRect.x) / sourceRect.width
        val y = (point.y - sourceRect.y) / sourceRect.height
        val divisor = px * x + py * y + 1
        if (kotlin.math.abs(divisor) <= 1e-9) return null
        return CompositionPoint(
            (a * x + b * y + topLeft.x) / divisor,
            (d * x + e * y + topLeft.y) / divisor,
        )
    }
}

object ScreenProjection {
    fun project(
        rect: CompositionRect,
        rotationX: Double,
        rotationY: Double,
        rotationZ: Double,
        perspective: Double,
        anchor: CompositionPoint? = null,
    ): ProjectedScreenQuad {
        val safeWidth = max(rect.width, 0.0)
        val safeHeight = max(rect.height, 0.0)
        val pivot = anchor?.takeIf { it.x.isFinite() && it.y.isFinite() }
            ?: CompositionPoint(rect.midX, rect.midY)
        val maximumDimension = max(max(rect.width, rect.height), 1.0)
        val depth = perspective.coerceIn(0.0, 2.0)
        val cameraDistance = if (depth > 0) maximumDimension * 2 / max(depth, 0.0001) else Double.POSITIVE_INFINITY
        val rx = rotationX * kotlin.math.PI / 180
        val ry = rotationY * kotlin.math.PI / 180
        val rz = rotationZ * kotlin.math.PI / 180
        val sinX = sin(rx); val cosX = cos(rx)
        val sinY = sin(ry); val cosY = cos(ry)
        val sinZ = sin(rz); val cosZ = cos(rz)

        fun point(x: Double, y: Double): CompositionPoint {
            val xAfterX = x
            val yAfterX = y * cosX
            val zAfterX = y * sinX
            val xAfterY = xAfterX * cosY + zAfterX * sinY
            val yAfterY = yAfterX
            val zAfterY = -xAfterX * sinY + zAfterX * cosY
            val xAfterZ = xAfterY * cosZ - yAfterY * sinZ
            val yAfterZ = xAfterY * sinZ + yAfterY * cosZ
            val projectionScale = if (cameraDistance.isFinite()) {
                val denominator = max(cameraDistance - zAfterY, cameraDistance * 0.12)
                cameraDistance / denominator
            } else {
                1.0
            }
            return CompositionPoint(
                pivot.x + xAfterZ * projectionScale,
                pivot.y + yAfterZ * projectionScale,
            )
        }
        return ProjectedScreenQuad(
            point(rect.x - pivot.x, rect.y - pivot.y),
            point(rect.x + safeWidth - pivot.x, rect.y - pivot.y),
            point(rect.x + safeWidth - pivot.x, rect.y + safeHeight - pivot.y),
            point(rect.x - pivot.x, rect.y + safeHeight - pivot.y),
        )
    }
}

data class CursorAssetMetrics(
    val hotspot: NormalizedPoint,
    val intrinsicWidth: Double,
    val intrinsicHeight: Double,
    val clickColor: HexColor,
    val scaleReferenceHeight: Double = intrinsicHeight,
)

data class CursorRenderLayout(
    val origin: CompositionPoint,
    val width: Double,
    val height: Double,
    val pointer: CompositionPoint,
    val clickDiameter: Double,
    val clickLineWidth: Double,
    val clickEffectHeight: Double,
)

object CursorCatalog {
    private val metrics = mapOf(
        CursorAssetID.SystemArrow to CursorAssetMetrics(NormalizedPoint(0.0, 0.0), 32.0, 40.0, HexColor(0x2B2D33)),
        CursorAssetID.SystemPointingHand to CursorAssetMetrics(NormalizedPoint(0.34, 0.0), 30.0, 40.0, HexColor(0x2B2D33)),
        CursorAssetID.SystemCrosshair to CursorAssetMetrics(NormalizedPoint(0.5, 0.5), 32.0, 32.0, HexColor(0x2B2D33)),
        CursorAssetID.SystemIBeam to CursorAssetMetrics(NormalizedPoint(0.5, 0.5), 22.0, 34.0, HexColor(0x2B2D33)),
        CursorAssetID.SystemOpenHand to CursorAssetMetrics(NormalizedPoint(0.5, 0.08), 32.0, 38.0, HexColor(0x2B2D33)),
        CursorAssetID.SystemClosedHand to CursorAssetMetrics(NormalizedPoint(0.5, 0.08), 32.0, 34.0, HexColor(0x2B2D33)),
        CursorAssetID.SystemNotAllowed to CursorAssetMetrics(NormalizedPoint(0.5, 0.5), 32.0, 32.0, HexColor(0x2B2D33)),
        CursorAssetID.TouchDot to CursorAssetMetrics(NormalizedPoint(0.5, 0.5), 40.0, 40.0, HexColor(0xFFFFFF)),
    )

    fun metricsFor(assetID: CursorAssetID): CursorAssetMetrics =
        metrics[assetID] ?: metrics.getValue(CursorAssetID.SystemArrow)

    fun displayName(assetID: CursorAssetID): String = when (assetID) {
        CursorAssetID.Automatic -> "跟随录制"
        CursorAssetID.SystemArrow -> "系统箭头"
        CursorAssetID.SystemPointingHand -> "指向手势"
        CursorAssetID.SystemCrosshair -> "十字准星"
        CursorAssetID.SystemIBeam -> "文本光标"
        CursorAssetID.SystemOpenHand -> "张开手"
        CursorAssetID.SystemClosedHand -> "抓取手"
        CursorAssetID.SystemNotAllowed -> "禁止"
        CursorAssetID.TouchDot -> "触控圆点"
        CursorAssetID.Hidden -> "隐藏"
        else -> "系统箭头"
    }

    val pickerChoices: List<CursorAssetID> = listOf(
        CursorAssetID.Automatic,
        CursorAssetID.SystemArrow,
        CursorAssetID.TouchDot,
        CursorAssetID.SystemPointingHand,
        CursorAssetID.SystemCrosshair,
        CursorAssetID.Hidden,
    )
}

object CursorRenderGeometry {
    const val CanonicalHeight = 44.0
    const val CanonicalShortEdge = 1080.0

    fun layout(
        pointer: CompositionPoint,
        canvasShortEdge: Double,
        styleSize: Double,
        metrics: CursorAssetMetrics,
    ): CursorRenderLayout? {
        if (!pointer.x.isFinite() || !pointer.y.isFinite() || canvasShortEdge <= 0 ||
            metrics.intrinsicWidth <= 0 || metrics.intrinsicHeight <= 0 || metrics.scaleReferenceHeight <= 0
        ) {
            return null
        }
        val multiplier = styleSize.coerceIn(0.25, 6.0)
        val referenceHeight = max(canvasShortEdge * CanonicalHeight / CanonicalShortEdge * multiplier, 1.0)
        val imageScale = referenceHeight / metrics.scaleReferenceHeight
        val height = metrics.intrinsicHeight * imageScale
        val width = metrics.intrinsicWidth * imageScale
        val hotspotX = metrics.hotspot.x.coerceIn(0.0, 1.0)
        val hotspotY = metrics.hotspot.y.coerceIn(0.0, 1.0)
        val lineWidth = max(referenceHeight * 0.08, canvasShortEdge * 2 / CanonicalShortEdge)
        return CursorRenderLayout(
            origin = CompositionPoint(pointer.x - width * hotspotX, pointer.y - height * hotspotY),
            width = width,
            height = height,
            pointer = pointer,
            clickDiameter = referenceHeight * 1.45,
            clickLineWidth = lineWidth,
            clickEffectHeight = referenceHeight,
        )
    }

    fun clickGeometry(
        style: CursorClickEffectStyle,
        progress: Double,
        baseHeight: Double,
        opacityMultiplier: Double = 1.0,
        scaleMultiplier: Double = 1.0,
    ): CursorClickGeometryFrame? {
        if (style == CursorClickEffectStyle.NONE || progress !in 0.0..1.0 || baseHeight <= 0) return null
        val userScale = scaleMultiplier.coerceIn(0.4, 3.0)
        val userOpacity = opacityMultiplier.coerceIn(0.05, 1.0)
        val scaledHeight = baseHeight * userScale
        return when (style) {
            CursorClickEffectStyle.RIPPLE -> {
                val e = 1 - (1 - progress).pow(3)
                val primaryDiameter = scaledHeight * (0.45 + 1.45 * e)
                val primaryLineWidth = max(scaledHeight * (0.075 - 0.04 * progress), 1.2)
                val primaryOpacity = max(userOpacity * 0.95 * (1 - progress.pow(1.3)), 0.0)
                val primaryGlow = scaledHeight * 0.10 * (1 - progress)
                val secondaryDiameter: Double
                val secondaryOpacity: Double
                val secondaryFilled: Boolean
                val secondaryGlow: Double
                if (progress < 0.35) {
                    val dotP = progress / 0.35
                    secondaryDiameter = scaledHeight * 0.28 * (1 - dotP)
                    secondaryOpacity = max(userOpacity * 0.85 * (1 - dotP), 0.0)
                    secondaryFilled = true
                    secondaryGlow = scaledHeight * 0.08 * (1 - dotP)
                } else {
                    secondaryDiameter = 0.0; secondaryOpacity = 0.0; secondaryFilled = false; secondaryGlow = 0.0
                }
                CursorClickGeometryFrame(
                    style, progress, primaryDiameter, primaryLineWidth, primaryOpacity, false, primaryGlow,
                    secondaryDiameter, 0.0, secondaryOpacity, secondaryFilled, secondaryGlow,
                    maxFootprintDiameter = scaledHeight * 2.2,
                )
            }
            CursorClickEffectStyle.GLOW -> {
                val primaryDiameter: Double
                val primaryOpacity: Double
                if (progress < 0.18) {
                    val pBloom = progress / 0.18
                    primaryDiameter = scaledHeight * (0.75 + 0.50 * (1 - (1 - pBloom).pow(2)))
                    primaryOpacity = max(userOpacity * 0.78 * pBloom, 0.0)
                } else {
                    val pFade = (progress - 0.18) / 0.82
                    primaryDiameter = scaledHeight * (1.25 + 0.55 * pFade)
                    primaryOpacity = max(userOpacity * 0.78 * (1 - pFade.pow(1.4)), 0.0)
                }
                CursorClickGeometryFrame(
                    style, progress, primaryDiameter, 0.0, primaryOpacity, true,
                    scaledHeight * 0.30,
                    primaryDiameter * 0.50, 0.0, primaryOpacity * 0.75, true,
                    scaledHeight * 0.18, maxFootprintDiameter = scaledHeight * 2.2,
                )
            }
            CursorClickEffectStyle.PULSE -> {
                val e = 1 - (1 - progress).pow(2.5)
                val primaryDiameter = scaledHeight * (0.50 + 1.25 * e)
                val primaryLineWidth = max(scaledHeight * 0.065, 1.2)
                val primaryOpacity = max(userOpacity * 0.90 * (1 - progress), 0.0)
                val primaryGlow = scaledHeight * 0.10 * (1 - progress)
                val secondaryDiameter: Double
                val secondaryOpacity: Double
                val secondaryFilled: Boolean
                val secondaryGlow: Double
                if (progress <= 0.55) {
                    val innerP = progress / 0.55
                    val innerE = sin(innerP * kotlin.math.PI)
                    secondaryDiameter = scaledHeight * (0.25 + 0.45 * innerE)
                    secondaryOpacity = max(userOpacity * 0.55 * (1 - innerP), 0.0)
                    secondaryFilled = true
                    secondaryGlow = scaledHeight * 0.12 * (1 - innerP)
                } else {
                    secondaryDiameter = 0.0; secondaryOpacity = 0.0; secondaryFilled = false; secondaryGlow = 0.0
                }
                CursorClickGeometryFrame(
                    style, progress, primaryDiameter, primaryLineWidth, primaryOpacity, false, primaryGlow,
                    secondaryDiameter, 0.0, secondaryOpacity, secondaryFilled, secondaryGlow,
                    maxFootprintDiameter = scaledHeight * 2.0,
                )
            }
            CursorClickEffectStyle.BURST -> {
                val primaryDiameter = max(scaledHeight * (0.35 - 0.18 * progress), 1.0)
                val primaryOpacity = max(userOpacity * 0.95 * (1 - progress.pow(2.0)), 0.0)
                val primaryGlow = scaledHeight * 0.14 * (1 - progress)
                val secondaryDiameter = scaledHeight * (0.45 + 0.55 * progress)
                val secondaryLineWidth = max(scaledHeight * 0.035, 1.0)
                val secondaryOpacity = max(userOpacity * 0.55 * (1 - progress), 0.0)
                val secondaryGlow = scaledHeight * 0.08 * (1 - progress)
                val burstE = 1 - (1 - progress).pow(2.0)
                val accentOffset = scaledHeight * (0.28 + 0.38 * burstE)
                val accentSize = max(scaledHeight * 0.075 * (1 - progress), 1.2)
                val accentOpacity = max(userOpacity * 0.85 * (1 - progress), 0.0)
                CursorClickGeometryFrame(
                    style, progress, primaryDiameter, 0.0, primaryOpacity, true, primaryGlow,
                    secondaryDiameter, secondaryLineWidth, secondaryOpacity, false, secondaryGlow,
                    accentOffset, accentSize, accentOpacity,
                    maxFootprintDiameter = scaledHeight * 1.8,
                )
            }
            CursorClickEffectStyle.NONE -> null
        }
    }
}

data class CursorClickGeometryFrame(
    val style: CursorClickEffectStyle,
    val progress: Double,
    val primaryDiameter: Double,
    val primaryLineWidth: Double,
    val primaryOpacity: Double,
    val primaryIsFilled: Boolean,
    val primaryGlowRadius: Double = 0.0,
    val secondaryDiameter: Double = 0.0,
    val secondaryLineWidth: Double = 0.0,
    val secondaryOpacity: Double = 0.0,
    val secondaryIsFilled: Boolean = false,
    val secondaryGlowRadius: Double = 0.0,
    val accentOffset: Double = 0.0,
    val accentSize: Double = 0.0,
    val accentOpacity: Double = 0.0,
    val maxFootprintDiameter: Double = 0.0,
)

data class CameraContentFillLayout(
    val source: CompositionRect,
    val target: CompositionRect,
    val destination: CompositionRect,
    val visibleSource: CompositionRect,
    val scale: Double,
) {
    val mirrorAxisX: Double get() = target.midX
}

object CameraContentFill {
    const val EdgeOverscanScale = 1.01

    fun layout(
        sourceWidth: Double,
        sourceHeight: Double,
        target: CompositionRect,
        focus: NormalizedPoint = NormalizedPoint.Center,
        contentScale: Double = 1.0,
    ): CameraContentFillLayout? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || target.width <= 0 || target.height <= 0) return null
        val sourceZoom = contentScale.coerceIn(1.0, 4.0)
        val scale = max(target.width / sourceWidth, target.height / sourceHeight) * EdgeOverscanScale * sourceZoom
        val destinationWidth = sourceWidth * scale
        val destinationHeight = sourceHeight * scale
        val visibleSourceWidth = target.width / scale
        val visibleSourceHeight = target.height / scale
        val visibleSource = CompositionRect(
            (sourceWidth - visibleSourceWidth) * focus.x.coerceIn(0.0, 1.0),
            (sourceHeight - visibleSourceHeight) * focus.y.coerceIn(0.0, 1.0),
            visibleSourceWidth,
            visibleSourceHeight,
        )
        return CameraContentFillLayout(
            source = CompositionRect(0.0, 0.0, sourceWidth, sourceHeight),
            target = target,
            destination = CompositionRect(
                target.x - visibleSource.x * scale,
                target.y - visibleSource.y * scale,
                destinationWidth,
                destinationHeight,
            ),
            visibleSource = visibleSource,
            scale = scale,
        )
    }
}

/** Inverts a row-major 3×3 matrix; returns null when singular. Used by quad unprojection. */
internal fun invert3x3(m: DoubleArray): DoubleArray? {
    val a = m[0]; val b = m[1]; val c = m[2]
    val d = m[3]; val e = m[4]; val f = m[5]
    val g = m[6]; val h = m[7]; val i = m[8]
    val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    if (kotlin.math.abs(det) <= 1e-12) return null
    val invDet = 1.0 / det
    return doubleArrayOf(
        (e * i - f * h) * invDet, (c * h - b * i) * invDet, (b * f - c * e) * invDet,
        (f * g - d * i) * invDet, (a * i - c * g) * invDet, (c * d - a * f) * invDet,
        (d * h - e * g) * invDet, (b * g - a * h) * invDet, (a * e - b * d) * invDet,
    )
}

/** Utility for hit testing the projected screen quad. */
fun ProjectedScreenQuad.contains(point: CompositionPoint, tolerance: Double = 0.000001): Boolean {
    if (!point.x.isFinite() || !point.y.isFinite()) return false
    var winding = 0
    val corners = corners
    for (i in corners.indices) {
        val start = corners[i]
        val end = corners[(i + 1) % corners.size]
        val cross = (end.x - start.x) * (point.y - start.y) - (end.y - start.y) * (point.x - start.x)
        if (start.y <= point.y) {
            if (end.y > point.y && cross > tolerance) winding++
        } else if (end.y <= point.y && cross < -tolerance) {
            winding--
        }
    }
    return winding != 0
}
