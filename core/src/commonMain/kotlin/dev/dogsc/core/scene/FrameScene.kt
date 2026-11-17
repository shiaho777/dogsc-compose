package dev.dogsc.core.scene

import dev.dogsc.core.model.BackgroundSource
import dev.dogsc.core.model.CameraMotionTrack
import dev.dogsc.core.model.CompositionPoint
import dev.dogsc.core.model.CompositionRect
import dev.dogsc.core.model.CompositionSize
import dev.dogsc.core.model.CursorAssetID
import dev.dogsc.core.model.CursorClickEffectStyle
import dev.dogsc.core.model.CursorStyle
import dev.dogsc.core.model.ElementMotionCurve
import dev.dogsc.core.model.ElementMotionEvaluator
import dev.dogsc.core.model.FocusEffect
import dev.dogsc.core.model.FocusEffectShape
import dev.dogsc.core.model.FocusEffectTarget
import dev.dogsc.core.model.FrameStickerBackdropScene
import dev.dogsc.core.model.HexColor
import dev.dogsc.core.model.MosaicEffectStyle
import dev.dogsc.core.model.MosaicTransitionStyle
import dev.dogsc.core.model.NormalizedCrop
import dev.dogsc.core.model.NormalizedOverlayRect
import dev.dogsc.core.model.NormalizedPoint
import dev.dogsc.core.model.OpeningSequence
import dev.dogsc.core.model.OpeningSequenceElement
import dev.dogsc.core.model.OpeningSequencePreset
import dev.dogsc.core.model.OverlayTiming
import dev.dogsc.core.model.PointerSample
import dev.dogsc.core.model.ProjectPointerTrack
import dev.dogsc.core.model.RecorderProject
import dev.dogsc.core.model.ScreenFrameStyle
import dev.dogsc.core.model.ScreenMotionTrack
import dev.dogsc.core.model.StickerAnimationPreset
import dev.dogsc.core.model.StickerTransitionTrack
import dev.dogsc.core.model.ZoomAnimationTrack
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class FrameColorSpace { LINEAR_SRGB, SRGB }

data class FrameColorContract(
    val workingSpace: FrameColorSpace = FrameColorSpace.LINEAR_SRGB,
    val outputSpace: FrameColorSpace = FrameColorSpace.SRGB,
    val isHDR: Boolean = false,
) {
    companion object {
        val SdrDesktop = FrameColorContract()
    }
}

data class FrameShadow(
    val color: HexColor = HexColor.Black,
    val opacity: Double,
    val radius: Double,
    val offset: CompositionPoint,
)

data class FrameBackgroundScene(
    val source: BackgroundSource,
    val blurRadius: Double,
    val patternScale: Double = 1.0,
    val patternOpacity: Double = 1.0,
)

enum class FrameScreenChromeKind {
    WINDOW, BROWSER, DEVICE_PHONE_PORTRAIT, DEVICE_PHONE_LANDSCAPE,
    DEVICE_TABLET_PORTRAIT, DEVICE_TABLET_LANDSCAPE;

    val isDevice: Boolean get() = this != WINDOW && this != BROWSER
    val isPhone: Boolean get() = this == DEVICE_PHONE_PORTRAIT || this == DEVICE_PHONE_LANDSCAPE
    val isPortrait: Boolean get() = this == DEVICE_PHONE_PORTRAIT || this == DEVICE_TABLET_PORTRAIT
}

data class FrameScreenChromeScene(
    val kind: FrameScreenChromeKind,
    val outerRect: CompositionRect,
    val toolbarRect: CompositionRect,
    val contentRect: CompositionRect,
    val outerCornerRadius: Double,
    val contentCornerRadius: Double,
    val surfaceColor: HexColor,
    val toolbarColor: HexColor,
    val separatorColor: HexColor,
    val fieldColor: HexColor,
    val glyphColor: HexColor,
    val title: String = "",
    val browserAddress: String = "",
)

sealed class FrameScreenDecoration {
    data object None : FrameScreenDecoration()
    data class Chrome(val scene: FrameScreenChromeScene) : FrameScreenDecoration()

    val projectionRect: CompositionRect?
        get() = (this as? Chrome)?.scene?.outerRect
}

data class FrameMosaicScene(
    val id: String,
    val sourceRect: NormalizedOverlayRect,
    val cornerRadius: Double,
    val style: MosaicEffectStyle,
    val intensity: Double,
    val spotlightDimming: Double,
    val transitionProgress: Double,
)

data class FrameFocusScene(
    val effect: FocusEffect,
    val center: NormalizedPoint,
    val progress: Double,
)

data class FrameScreenScene(
    val sourceCrop: NormalizedCrop,
    val fittedRect: CompositionRect,
    val baseRect: CompositionRect,
    val finalRect: CompositionRect,
    val projectedQuad: ProjectedScreenQuad,
    val viewport: dev.dogsc.core.model.ZoomViewportTransform,
    val rotationX: Double,
    val rotationY: Double,
    val rotationZ: Double,
    val perspective: Double,
    val projectionAnchor: NormalizedPoint,
    val cornerRadius: Double,
    val borderWidth: Double,
    val borderColor: HexColor,
    val borderOpacity: Double,
    val shadow: FrameShadow?,
    val decoration: FrameScreenDecoration,
    val mosaics: List<FrameMosaicScene>,
    val focusEffect: FrameFocusScene?,
    val opacity: Double = 1.0,
) {
    val projectionRect: CompositionRect get() = decoration.projectionRect ?: finalRect
}

data class FrameCameraScene(
    val rect: CompositionRect,
    val contentFill: CameraContentFillLayout?,
    val cornerRadius: Double,
    val borderWidth: Double,
    val borderColor: HexColor = HexColor.White,
    val shadow: FrameShadow?,
    val opacity: Double,
    val isMirrored: Boolean,
)

data class FrameCursorScene(
    val assetID: CursorAssetID,
    val metrics: CursorAssetMetrics,
    val normalizedScreenPosition: NormalizedPoint,
    val layout: CursorRenderLayout,
    val isClicking: Boolean,
    val clickPhase: dev.dogsc.core.model.PointerClickPhase?,
    val clickStyle: CursorClickEffectStyle,
    val clickColor: HexColor?,
    val clickOpacity: Double,
    val clickScale: Double,
    val rotationRadians: Double,
    val shadow: FrameShadow?,
) {
    val effectiveClickColor: HexColor get() = clickColor ?: metrics.clickColor
}

data class FrameStickerScene(
    val id: String,
    val relativePath: String,
    val position: NormalizedPoint,
    val width: Double,
    val rotationRadians: Double,
    val opacity: Double,
    val scale: Double,
    val offset: NormalizedPoint,
    val cornerRadius: Double,
    val borderWidth: Double,
    val borderColor: HexColor,
    val shadowOpacity: Double,
    val shadowRadius: Double,
    val shadowOffset: CompositionPoint,
    val backdropBlur: Double,
    val backdropBlurIncludesCamera: Boolean,
    val hidesScreen: Boolean,
    val hidesCamera: Boolean,
    val transitionProgress: Double,
    val layerIndex: Int,
)

enum class FrameLayerRole { BACKGROUND, SCREEN, CURSOR, SPOTLIGHT, CAMERA, STICKERS }

data class FrameScene(
    val time: Double,
    val canvasSize: CompositionSize,
    val color: FrameColorContract,
    val background: FrameBackgroundScene,
    val screen: FrameScreenScene,
    val camera: FrameCameraScene?,
    val cursor: FrameCursorScene?,
    val stickers: List<FrameStickerScene>,
    val stickerBackdrop: FrameStickerBackdropScene,
    val layerOrder: List<FrameLayerRole>,
)

data class FrameRenderPlan(
    val presentationTime: Double,
    val outputDuration: Double,
    val frameRate: Int,
    val scene: FrameScene,
)

object FrameSceneEvaluator {
    fun renderPlan(
        project: RecorderProject,
        presentationTime: Double,
        outputDuration: Double,
        frameRate: Int,
        canvasSize: CompositionSize,
        sourceAspectRatio: Double,
        cameraSourceSize: CompositionSize? = null,
        pointerTrack: ProjectPointerTrack? = null,
        cursorMetrics: CursorAssetMetrics? = null,
        cursorMetricsByAssetID: Map<CursorAssetID, CursorAssetMetrics> = emptyMap(),
        zoomTrack: ZoomAnimationTrack? = null,
        screenMotionTrack: ScreenMotionTrack? = null,
        cameraMotionTrack: CameraMotionTrack? = null,
        stickerTrack: StickerTransitionTrack? = null,
        cameraAvailable: Boolean = true,
        color: FrameColorContract = FrameColorContract.SdrDesktop,
    ): FrameRenderPlan {
        val safeDuration = if (outputDuration.isFinite()) max(outputDuration, 0.0) else 0.0
        val safeTime = if (presentationTime.isFinite()) presentationTime.coerceIn(0.0, safeDuration) else 0.0
        val scene = scene(
            project = project,
            time = safeTime,
            outputDuration = safeDuration,
            canvasSize = canvasSize,
            sourceAspectRatio = sourceAspectRatio,
            cameraSourceSize = if (cameraAvailable) cameraSourceSize else null,
            pointerTrack = pointerTrack,
            cursorMetrics = cursorMetrics,
            cursorMetricsByAssetID = cursorMetricsByAssetID,
            zoomTrack = zoomTrack,
            screenMotionTrack = screenMotionTrack,
            cameraMotionTrack = cameraMotionTrack,
            stickerTrack = stickerTrack,
            color = color,
        )
        return FrameRenderPlan(safeTime, safeDuration, max(frameRate, 1), scene)
    }

    fun scene(
        project: RecorderProject,
        time: Double,
        outputDuration: Double? = null,
        canvasSize: CompositionSize,
        sourceAspectRatio: Double,
        cameraSourceSize: CompositionSize? = null,
        pointerTrack: ProjectPointerTrack? = null,
        pointerEvaluation: dev.dogsc.core.model.PointerTrackEvaluation? = null,
        cursorMetrics: CursorAssetMetrics? = null,
        cursorMetricsByAssetID: Map<CursorAssetID, CursorAssetMetrics> = emptyMap(),
        zoomTrack: ZoomAnimationTrack? = null,
        screenMotionTrack: ScreenMotionTrack? = null,
        cameraMotionTrack: CameraMotionTrack? = null,
        stickerTrack: StickerTransitionTrack? = null,
        color: FrameColorContract = FrameColorContract.SdrDesktop,
    ): FrameScene {
        val width = max(canvasSize.width, 2.0)
        val height = max(canvasSize.height, 2.0)
        val sampleTime = if (time.isFinite()) max(time, 0.0) else 0.0
        val resolvedZoomTrack = zoomTrack?.boundedTo(outputDuration)
            ?: ZoomAnimationTrack(project.timeline.zoomClips, outputDuration)
        val resolvedScreenTrack = screenMotionTrack ?: ScreenMotionTrack(project.timeline.screenMotionClips)
        val activeZoomClip = resolvedZoomTrack.activeClip(sampleTime)
        val activeAutomaticClip = activeZoomClip?.takeIf { it.origin == dev.dogsc.core.model.ZoomKeyframeOrigin.AUTOMATIC }
        val reanchorsAtCut = activeAutomaticClip?.let { pointerTrack?.automaticEntryStartsAfterCut(it) } ?: false
        val pointer = (if (reanchorsAtCut) null else pointerEvaluation)
            ?: pointerTrack?.evaluation(sampleTime, project.motion, project.cursorStyle, activeAutomaticClip)
            ?: dev.dogsc.core.model.PointerTrackEvaluation(null, null)
        val automaticCameraPosition = activeAutomaticClip?.let { clip ->
            pointerTrack?.automaticCameraFocus(sampleTime, clip, project.motion) ?: clip.focus
        }
        val inheritedAutomaticCameraPosition = activeZoomClip
            ?.let { resolvedZoomTrack.adjacentPreviousClip(it) }
            ?.takeIf { it.origin == dev.dogsc.core.model.ZoomKeyframeOrigin.AUTOMATIC }
            ?.let { previous ->
                pointerTrack?.automaticCameraFocus(previous.endTime, previous, project.motion) ?: previous.focus
            }
        val cameraAspect = cameraSourceSize?.let { max(it.width, 1.0) / max(it.height, 1.0) }
        val styleScale = CompositionSceneEvaluator.canonicalStyleScale(width, height)
        val geometry = CompositionSceneEvaluator.evaluate(
            project = project,
            time = sampleTime,
            canvasWidth = width,
            canvasHeight = height,
            sourceAspectRatio = sourceAspectRatio,
            cameraAspectRatio = cameraAspect,
            styleScale = styleScale,
            automaticZoomFocus = automaticCameraPosition,
            inheritedAutomaticZoomFocus = inheritedAutomaticCameraPosition,
            reanchorAutomaticEntry = reanchorsAtCut,
            zoomTrack = resolvedZoomTrack,
            screenMotionTrack = resolvedScreenTrack,
            cameraMotionTrack = cameraMotionTrack,
        )
        val hasOpeningSticker = project.timeline.stickerClips.any {
            it.timing.startTime <= 0.0001 && it.timing.endTime > 0.0001
        }
        val hasOpeningCamera = (project.media?.camera != null || cameraSourceSize != null) && !project.camera.isHidden
        val availableOpeningElements = project.openingSequence.participatingElements(hasOpeningCamera, hasOpeningSticker)
        val screenOpening = openingSample(
            OpeningSequenceElement.SCREEN, project.openingSequence, availableOpeningElements,
            sampleTime, width, height,
        )
        var screenGeometry = geometry.screen
        screenGeometry = screenGeometry.copy(
            finalRect = openingRect(screenGeometry.finalRect, screenOpening),
            finalCornerRadius = screenGeometry.finalCornerRadius * screenOpening.scale,
            finalBorderWidth = screenGeometry.finalBorderWidth * screenOpening.scale,
            rotationX = screenGeometry.rotationX + screenOpening.rotationX,
            rotationY = screenGeometry.rotationY + screenOpening.rotationY,
            perspective = (screenGeometry.perspective + screenOpening.perspective).coerceIn(0.0, 2.0),
        )
        val screenShadow = if (project.canvas.shadowStrength > 0) {
            FrameShadow(
                opacity = project.canvas.shadowStrength,
                radius = 22 * styleScale * screenGeometry.manualScale *
                    screenGeometry.viewport.scale * screenOpening.scale,
                offset = CompositionPoint(
                    0.0,
                    12 * styleScale * screenGeometry.manualScale *
                        screenGeometry.viewport.scale * screenOpening.scale,
                ),
            )
        } else {
            null
        }
        val decoration = screenDecoration(
            project = project,
            geometry = screenGeometry,
            styleScale = styleScale,
            groupScale = screenOpening.scale,
        )
        val projectionRect = decoration.projectionRect ?: screenGeometry.finalRect
        val projectionAnchor = CompositionPoint(
            screenGeometry.finalRect.x + screenGeometry.finalRect.width * screenGeometry.projectionAnchor.x,
            screenGeometry.finalRect.y + screenGeometry.finalRect.height * screenGeometry.projectionAnchor.y,
        )
        val projectedQuad = ScreenProjection.project(
            projectionRect,
            screenGeometry.rotationX,
            screenGeometry.rotationY,
            screenGeometry.rotationZ,
            screenGeometry.perspective,
            projectionAnchor,
        )
        val activeMosaics = project.timeline.mosaicClips.mapNotNull { clip ->
            if (!clip.timing.contains(sampleTime)) return@mapNotNull null
            FrameMosaicScene(
                id = clip.id,
                sourceRect = clip.sourceRect,
                cornerRadius = clip.cornerRadius,
                style = clip.style,
                intensity = clip.intensity,
                spotlightDimming = clip.spotlightDimming,
                transitionProgress = overlayEffectProgress(
                    clip.timing, clip.transitionStyle, clip.transitionInDuration, clip.transitionOutDuration, sampleTime,
                ),
            )
        }
        val screen = FrameScreenScene(
            sourceCrop = project.canvas.crop,
            fittedRect = screenGeometry.fittedRect,
            baseRect = screenGeometry.baseRect,
            finalRect = screenGeometry.finalRect,
            projectedQuad = projectedQuad,
            viewport = screenGeometry.viewport,
            rotationX = screenGeometry.rotationX,
            rotationY = screenGeometry.rotationY,
            rotationZ = screenGeometry.rotationZ,
            perspective = screenGeometry.perspective,
            projectionAnchor = screenGeometry.projectionAnchor,
            cornerRadius = screenGeometry.finalCornerRadius,
            borderWidth = screenGeometry.finalBorderWidth,
            borderColor = project.canvas.borderColor,
            borderOpacity = project.canvas.insetOpacity,
            shadow = screenShadow,
            decoration = decoration,
            mosaics = activeMosaics,
            focusEffect = FocusEffectEvaluator.scene(
                activeZoomClip,
                resolvedScreenTrack.activeClip(sampleTime),
                geometry.screen.zoom.focus,
                project.canvas.crop,
                pointer.position,
                sampleTime,
                project.motion,
            ),
            opacity = screenOpening.opacity,
        )
        val camera = geometry.camera?.let { evaluation ->
            val opening = openingSample(
                OpeningSequenceElement.CAMERA, project.openingSequence, availableOpeningElements,
                sampleTime, width, height,
            )
            val openingRect = openingRect(evaluation.rect, opening)
            FrameCameraScene(
                rect = openingRect,
                contentFill = cameraSourceSize?.let { source ->
                    val visualFocus = project.camera.contentPosition
                    CameraContentFill.layout(
                        sourceWidth = source.width,
                        sourceHeight = source.height,
                        target = openingRect,
                        focus = NormalizedPoint(
                            if (project.camera.isMirrored) 1 - visualFocus.x else visualFocus.x,
                            visualFocus.y,
                        ),
                        contentScale = project.camera.contentScale,
                    )
                },
                cornerRadius = evaluation.cornerRadius * opening.scale,
                borderWidth = evaluation.borderWidth * opening.scale,
                shadow = if (project.camera.shadowStrength > 0) {
                    FrameShadow(
                        opacity = project.camera.shadowStrength,
                        radius = 14 * styleScale,
                        offset = CompositionPoint(0.0, 8 * styleScale),
                    )
                } else {
                    null
                },
                opacity = if (project.camera.isHidden) 0.0 else evaluation.opacity * opening.opacity,
                isMirrored = project.camera.isMirrored,
            )
        }
        val cursor = cursorScene(
            pointer.cursor, project, cursorMetrics, cursorMetricsByAssetID, screen, canvasSize,
        )
        val stickerSample = stickerScenes(
            stickerTrack ?: StickerTransitionTrack(project.timeline.stickerClips),
            sampleTime,
            project.openingSequence.isEnabled && project.openingSequence.includedElements.contains(OpeningSequenceElement.STICKERS),
        )
        val stickerOpening = openingSample(
            OpeningSequenceElement.STICKERS, project.openingSequence, availableOpeningElements,
            sampleTime, width, height,
        )
        val stickers = stickerSample.first.map { scene ->
            scene.copy(
                offset = NormalizedPoint(
                    scene.offset.x + stickerOpening.offset.x / width,
                    scene.offset.y + stickerOpening.offset.y / height,
                ),
                scale = scene.scale * stickerOpening.scale,
                opacity = scene.opacity * stickerOpening.opacity,
            )
        }
        val order = mutableListOf(FrameLayerRole.BACKGROUND, FrameLayerRole.SCREEN)
        if (cursor != null) order += FrameLayerRole.CURSOR
        if (screen.focusEffect != null || activeMosaics.any { it.style == MosaicEffectStyle.SPOTLIGHT }) {
            order += FrameLayerRole.SPOTLIGHT
        }
        if (camera != null && camera.opacity > 0) order += FrameLayerRole.CAMERA
        if (stickers.isNotEmpty()) order += FrameLayerRole.STICKERS
        return FrameScene(
            time = sampleTime,
            canvasSize = CompositionSize(width, height),
            color = color,
            background = FrameBackgroundScene(
                project.canvas.backgroundSource,
                project.canvas.backgroundBlur * styleScale,
                project.canvas.patternScale,
                project.canvas.patternOpacity,
            ),
            screen = screen,
            camera = camera,
            cursor = cursor,
            stickers = stickers,
            stickerBackdrop = stickerSample.second,
            layerOrder = order,
        )
    }

    private fun stickerScenes(
        track: StickerTransitionTrack,
        time: Double,
        suppressesInitialEntry: Boolean = false,
    ): Pair<List<FrameStickerScene>, FrameStickerBackdropScene> {
        val (samples, backdrop) = track.sample(time, suppressesInitialEntry)
        val stickers = samples.map { sample ->
            val clip = sample.clip
            val visibility = sample.visibility
            val transform = stickerAnimationTransform(sample.preset, sample.progress, clip.position, clip.width)
            FrameStickerScene(
                id = clip.id,
                relativePath = clip.relativePath,
                position = clip.position,
                width = clip.width.coerceIn(0.02, 2.0),
                rotationRadians = clip.rotationDegrees * kotlin.math.PI / 180,
                opacity = clip.opacity.coerceIn(0.0, 1.0) * visibility,
                scale = transform.first,
                offset = transform.second,
                cornerRadius = max(clip.cornerRadius, 0.0),
                borderWidth = max(clip.borderWidth, 0.0),
                borderColor = clip.borderColor,
                shadowOpacity = clip.shadowOpacity.coerceIn(0.0, 1.0),
                shadowRadius = max(clip.shadowRadius, 0.0),
                shadowOffset = CompositionPoint(clip.shadowOffsetX, clip.shadowOffsetY),
                backdropBlur = max(clip.backdropBlur, 0.0) * visibility,
                backdropBlurIncludesCamera = clip.backdropBlurIncludesCamera,
                hidesScreen = clip.hidesScreen,
                hidesCamera = clip.hidesCamera,
                transitionProgress = visibility,
                layerIndex = clip.layerIndex,
            )
        }
        return stickers to backdrop
    }

    private fun stickerAnimationTransform(
        preset: StickerAnimationPreset,
        progress: Double,
        position: NormalizedPoint,
        width: Double,
    ): Pair<Double, NormalizedPoint> {
        val p = progress.coerceIn(0.0, 1.0)
        val travel = 1 - p
        val halfExtent = max(width, 0.02) / 2
        val outsideLeading = min(-0.85, -halfExtent - 0.06)
        val outsideTrailing = max(1.85, 1 + halfExtent + 0.06)
        val left = outsideLeading - position.x
        val right = outsideTrailing - position.x
        val top = outsideLeading - position.y
        val bottom = outsideTrailing - position.y
        var offset = NormalizedPoint(0.0, 0.0)
        var scale = 1.0
        when (preset) {
            StickerAnimationPreset.NONE, StickerAnimationPreset.FADE -> Unit
            StickerAnimationPreset.POP -> scale = 0.72 + p * 0.28
            StickerAnimationPreset.SLIDE_LEFT -> offset = NormalizedPoint(left * travel, 0.0)
            StickerAnimationPreset.SLIDE_RIGHT -> offset = NormalizedPoint(right * travel, 0.0)
            StickerAnimationPreset.SLIDE_UP -> offset = NormalizedPoint(0.0, top * travel)
            StickerAnimationPreset.SLIDE_DOWN -> offset = NormalizedPoint(0.0, bottom * travel)
            StickerAnimationPreset.SLIDE_TOP_LEFT -> offset = NormalizedPoint(left * travel, top * travel)
            StickerAnimationPreset.SLIDE_TOP_RIGHT -> offset = NormalizedPoint(right * travel, top * travel)
            StickerAnimationPreset.SLIDE_BOTTOM_LEFT -> offset = NormalizedPoint(left * travel, bottom * travel)
            StickerAnimationPreset.SLIDE_BOTTOM_RIGHT -> offset = NormalizedPoint(right * travel, bottom * travel)
        }
        return scale to offset
    }

    private data class OpeningSample(
        val opacity: Double,
        val scale: Double,
        val offset: CompositionPoint,
        val rotationX: Double,
        val rotationY: Double,
        val perspective: Double,
        val remaining: Double,
    ) {
        companion object {
            val Identity = OpeningSample(1.0, 1.0, CompositionPoint(0.0, 0.0), 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun openingSample(
        element: OpeningSequenceElement,
        sequence: OpeningSequence,
        activeElements: List<OpeningSequenceElement>,
        time: Double,
        canvasWidth: Double,
        canvasHeight: Double,
    ): OpeningSample {
        if (!sequence.isEnabled || time >= sequence.duration) return OpeningSample.Identity
        val index = activeElements.indexOf(element)
        if (index < 0) return OpeningSample.Identity
        val effectiveStagger = min(sequence.stagger, sequence.maximumStagger(activeElements.size))
        val lastDelay = effectiveStagger * max(activeElements.size - 1, 0)
        val itemDuration = max(sequence.duration - lastDelay, OpeningSequence.MinimumElementDuration)
        val start = effectiveStagger * index
        val linear = ((time - start) / itemDuration).coerceIn(0.0, 1.0)
        val eased = ElementMotionEvaluator.progress(linear, sequence.motionCurve)
        val opacity = ElementMotionEvaluator.progress(min(linear * 1.65, 1.0), sequence.motionCurve)
        val remaining = 1 - eased
        val rotationRemaining = (1 - linear).pow(1.5)
        val overshoot = 1 + 1.35 * (linear - 1).pow(3) + 0.35 * (linear - 1).pow(2)
        val scaleStart: Double
        val initialOffset: CompositionPoint
        val rotationX: Double
        val rotationY: Double
        val perspective: Double
        when (sequence.preset to element) {
            OpeningSequencePreset.CONVERGE to OpeningSequenceElement.SCREEN -> {
                scaleStart = 0.72
                initialOffset = CompositionPoint(-canvasWidth * 1.05, -canvasHeight * 0.62)
                rotationX = 0.0; rotationY = 0.0; perspective = 0.0
            }
            OpeningSequencePreset.CONVERGE to OpeningSequenceElement.CAMERA -> {
                scaleStart = 0.58
                initialOffset = CompositionPoint(canvasWidth * 1.12, canvasHeight * 0.72)
                rotationX = 0.0; rotationY = 0.0; perspective = 0.0
            }
            OpeningSequencePreset.CONVERGE to OpeningSequenceElement.STICKERS -> {
                scaleStart = 0.62
                initialOffset = CompositionPoint(-canvasWidth * 1.30, -canvasHeight * 0.70)
                rotationX = 0.0; rotationY = 0.0; perspective = 0.0
            }
            OpeningSequencePreset.SIDE_SLIDE to OpeningSequenceElement.SCREEN -> {
                scaleStart = 0.94
                initialOffset = CompositionPoint(-canvasWidth * 1.12, 0.0)
                rotationX = 0.0; rotationY = 0.0; perspective = 0.0
            }
            OpeningSequencePreset.SIDE_SLIDE to OpeningSequenceElement.CAMERA -> {
                scaleStart = 0.88
                initialOffset = CompositionPoint(canvasWidth * 1.15, 0.0)
                rotationX = 0.0; rotationY = 0.0; perspective = 0.0
            }
            OpeningSequencePreset.SIDE_SLIDE to OpeningSequenceElement.STICKERS -> {
                scaleStart = 0.9
                initialOffset = CompositionPoint(-canvasWidth * 1.35, 0.0)
                rotationX = 0.0; rotationY = 0.0; perspective = 0.0
            }
            OpeningSequencePreset.LIGHT_3D to OpeningSequenceElement.SCREEN -> {
                scaleStart = 0.80
                initialOffset = CompositionPoint(-canvasWidth * 1.05, canvasHeight * 0.08)
                rotationX = 9 * rotationRemaining
                rotationY = -22 * rotationRemaining
                perspective = 0.72 * rotationRemaining
            }
            OpeningSequencePreset.LIGHT_3D to OpeningSequenceElement.CAMERA -> {
                scaleStart = 0.72
                initialOffset = CompositionPoint(canvasWidth * 1.10, canvasHeight * 0.16)
                rotationX = 0.0; rotationY = 0.0; perspective = 0.0
            }
            OpeningSequencePreset.LIGHT_3D to OpeningSequenceElement.STICKERS -> {
                scaleStart = 0.76
                initialOffset = CompositionPoint(-canvasWidth * 1.30, -canvasHeight * 0.12)
                rotationX = 0.0; rotationY = 0.0; perspective = 0.0
            }
            else -> {
                scaleStart = 1.0
                initialOffset = CompositionPoint(0.0, 0.0)
                rotationX = 0.0; rotationY = 0.0; perspective = 0.0
            }
        }
        val settledScale = overshoot.coerceIn(0.0, 1.04)
        return OpeningSample(
            opacity = opacity,
            scale = scaleStart + (settledScale - scaleStart) * eased,
            offset = CompositionPoint(initialOffset.x * remaining, initialOffset.y * remaining),
            rotationX = rotationX,
            rotationY = rotationY,
            perspective = perspective,
            remaining = remaining,
        )
    }

    private fun openingRect(rect: CompositionRect, sample: OpeningSample): CompositionRect {
        val width = rect.width * sample.scale
        val height = rect.height * sample.scale
        return CompositionRect(
            rect.midX - width / 2 + sample.offset.x,
            rect.midY - height / 2 + sample.offset.y,
            width,
            height,
        )
    }

    private fun overlayEffectProgress(
        timing: OverlayTiming,
        style: MosaicTransitionStyle,
        enterDuration: Double,
        exitDuration: Double,
        time: Double,
    ): Double {
        if (timing.duration <= 0) return 0.0
        if (style == MosaicTransitionStyle.NONE) return 1.0
        val elapsed = max(time - timing.startTime, 0.0)
        val remaining = max(timing.endTime - time, 0.0)
        val linearEnter = if (enterDuration > 0) (elapsed / enterDuration).coerceIn(0.0, 1.0) else 1.0
        val linearExit = if (exitDuration > 0) (remaining / exitDuration).coerceIn(0.0, 1.0) else 1.0
        val enter = when (style) {
            MosaicTransitionStyle.NONE -> return 1.0
            MosaicTransitionStyle.LINEAR -> linearEnter
            MosaicTransitionStyle.SMOOTH -> smootherStep(linearEnter)
        }
        val exit = when (style) {
            MosaicTransitionStyle.NONE -> return 1.0
            MosaicTransitionStyle.LINEAR -> linearExit
            MosaicTransitionStyle.SMOOTH -> smootherStep(linearExit)
        }
        return min(enter, exit)
    }

    private fun screenDecoration(
        project: RecorderProject,
        geometry: ScreenSceneEvaluation,
        styleScale: Double,
        groupScale: Double = 1.0,
    ): FrameScreenDecoration {
        val style = project.canvas.screenFrame
        if (style == ScreenFrameStyle.NONE) return FrameScreenDecoration.None
        val authoredFrameScale = project.canvas.screenFrameScale.coerceIn(0.6, 1.6)
        val motionScale = max(geometry.manualScale * geometry.viewport.scale * groupScale, 0.0001)
        val scale = max(styleScale * motionScale * authoredFrameScale, 0.0001)
        val authoredInsets = ScreenFrameGeometry.decorationInsetsAtScaleOne(
            style = style,
            frameScale = project.canvas.screenFrameScale,
            toolbarScale = project.canvas.screenFrameToolbarScale,
            fittedWidth = geometry.fittedRect.width,
            fittedHeight = geometry.fittedRect.height,
            styleScale = styleScale,
        )
        val topInset = authoredInsets.top * motionScale
        val rightInset = authoredInsets.right * motionScale
        val bottomInset = authoredInsets.bottom * motionScale
        val leftInset = authoredInsets.left * motionScale
        val outerRect = CompositionRect(
            geometry.finalRect.x - leftInset,
            geometry.finalRect.y - topInset,
            geometry.finalRect.width + leftInset + rightInset,
            geometry.finalRect.height + topInset + bottomInset,
        )
        val toolbarRect = CompositionRect(
            outerRect.x,
            outerRect.y,
            outerRect.width,
            topInset + min(2 * scale, topInset * 0.08),
        )
        val kind = when (style) {
            ScreenFrameStyle.BROWSER_LIGHT, ScreenFrameStyle.BROWSER_DARK -> FrameScreenChromeKind.BROWSER
            ScreenFrameStyle.DEVICE_PHONE -> if (geometry.finalRect.width >= geometry.finalRect.height) {
                FrameScreenChromeKind.DEVICE_PHONE_LANDSCAPE
            } else {
                FrameScreenChromeKind.DEVICE_PHONE_PORTRAIT
            }
            ScreenFrameStyle.DEVICE_TABLET -> if (geometry.finalRect.width >= geometry.finalRect.height) {
                FrameScreenChromeKind.DEVICE_TABLET_LANDSCAPE
            } else {
                FrameScreenChromeKind.DEVICE_TABLET_PORTRAIT
            }
            ScreenFrameStyle.WINDOW_LIGHT, ScreenFrameStyle.WINDOW_DARK, ScreenFrameStyle.NONE -> FrameScreenChromeKind.WINDOW
        }
        val authoredOuterRadius = (
            project.canvas.screenFrameOuterCornerRadius
                ?: if (kind.isDevice) {
                    min(outerRect.width, outerRect.height) * (if (kind.isPhone) 0.11 else 0.065) / max(scale, 0.0001)
                } else {
                    style.defaultOuterCornerRadius
                }
            ).coerceIn(0.0, 160.0)
        val outerCornerRadius = min(authoredOuterRadius * scale, min(outerRect.width, outerRect.height) / 2)
        val authoredContentRadius = (
            project.canvas.screenFrameContentCornerRadius
                ?: if (kind.isDevice) {
                    max(outerCornerRadius - min(topInset, leftInset), 0.0) / max(scale, 0.0001)
                } else {
                    style.defaultContentCornerRadius
                }
            ).coerceIn(0.0, 160.0)
        val contentCornerRadius = min(
            authoredContentRadius * scale,
            min(geometry.finalRect.width, geometry.finalRect.height) / 2,
        )
        val isDark = style.isDark
        return FrameScreenDecoration.Chrome(
            FrameScreenChromeScene(
                kind = kind,
                outerRect = outerRect,
                toolbarRect = toolbarRect,
                contentRect = geometry.finalRect,
                outerCornerRadius = outerCornerRadius,
                contentCornerRadius = contentCornerRadius,
                surfaceColor = if (isDark) HexColor(0x222327) else HexColor(0xECEDF0),
                toolbarColor = if (isDark) HexColor(0x313238) else HexColor(0xF4F4F6),
                separatorColor = if (isDark) HexColor(0x4B4D55) else HexColor(0xC9CACE),
                fieldColor = if (isDark) HexColor(0x1B1C20) else HexColor(0xFFFFFF),
                glyphColor = if (isDark) HexColor(0xA9ABB3) else HexColor(0x7A7C84),
                title = project.canvas.screenFrameTitle,
                browserAddress = project.canvas.screenFrameBrowserAddress,
            )
        )
    }

    private fun cursorScene(
        sample: PointerSample?,
        project: RecorderProject,
        metrics: CursorAssetMetrics?,
        metricsByAssetID: Map<CursorAssetID, CursorAssetMetrics>,
        screen: FrameScreenScene,
        canvasSize: CompositionSize,
    ): FrameCursorScene? {
        val pointer = sample ?: return null
        val assetID = if (project.cursorStyle.assetID == CursorAssetID.Automatic) {
            // Android cannot read the source app's cursor. The platform equivalent: show the
            // touch dot while a tap effect is active, and the arrow while moving.
            pointer.recordedCursorAssetID ?: if (pointer.isClicking) {
                CursorAssetID.TouchDot
            } else {
                CursorAssetID.SystemArrow
            }
        } else {
            project.cursorStyle.assetID
        }
        if (assetID == CursorAssetID.Hidden) return null
        val resolvedMetrics = metricsByAssetID[assetID] ?: metrics ?: CursorCatalog.metricsFor(assetID)
        val crop = screen.sourceCrop.clamped()
        val local = NormalizedPoint(
            (pointer.location.x - crop.x) / crop.width,
            (pointer.location.y - crop.y) / crop.height,
        )
        if (local.x !in 0.0..1.0 || local.y !in 0.0..1.0) return null
        val point = CompositionPoint(
            screen.finalRect.x + local.x * screen.finalRect.width,
            screen.finalRect.y + local.y * screen.finalRect.height,
        )
        val layout = CursorRenderGeometry.layout(
            pointer = point,
            canvasShortEdge = canvasSize.shortEdge,
            styleSize = project.cursorStyle.size,
            metrics = resolvedMetrics,
        ) ?: return null
        return FrameCursorScene(
            assetID = assetID,
            metrics = resolvedMetrics,
            normalizedScreenPosition = local,
            layout = layout,
            isClicking = pointer.isClicking,
            clickPhase = pointer.clickPhase,
            clickStyle = project.cursorStyle.clickEffectStyle,
            clickColor = project.cursorStyle.clickColor,
            clickOpacity = project.cursorStyle.clickOpacity,
            clickScale = project.cursorStyle.clickScale,
            rotationRadians = pointer.rotationRadians,
            shadow = FrameShadow(
                opacity = 0.42,
                radius = max(layout.clickEffectHeight * 1.5 / 44, 0.5),
                offset = CompositionPoint(0.0, max(layout.clickEffectHeight * 0.04, 1.0)),
            ),
        )
    }

    private fun smootherStep(value: Double): Double {
        val x = value.coerceIn(0.0, 1.0)
        return x * x * x * (x * (x * 6 - 15) + 10)
    }
}
