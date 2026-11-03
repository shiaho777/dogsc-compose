package dev.dogsc.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Serializable
data class CanvasCustomAspectRatio(val width: Double = 16.0, val height: Double = 9.0) {
    val value: Double
        get() = if (width.isFinite() && height.isFinite() && width > 0 && height > 0) {
            (width / height).coerceIn(0.1, 10.0)
        } else {
            16.0 / 9.0
        }
}

@Serializable
data class CanvasDimensions(val width: Int, val height: Int) {
    val aspectRatio: Double get() = max(width, 1).toDouble() / max(height, 1)
}

@Serializable
data class CanvasStyle(
    val aspectRatio: CanvasAspectRatio = CanvasAspectRatio.ADAPTIVE,
    val customAspectRatio: CanvasCustomAspectRatio = CanvasCustomAspectRatio(),
    val backgroundSource: BackgroundSource = BackgroundSource.SafeFallback,
    val padding: Double = 100.0,
    val contentScale: Double = 1.0,
    val contentPosition: NormalizedPoint = NormalizedPoint.Center,
    val crop: NormalizedCrop = NormalizedCrop.Full,
    val cornerRadius: Double = 24.0,
    val borderWidth: Double = 0.0,
    val borderColor: HexColor = HexColor.White,
    val shadowStrength: Double = 0.28,
    val backgroundBlur: Double = 0.0,
    val insetOpacity: Double = 0.125,
    val screenFrame: ScreenFrameStyle = ScreenFrameStyle.NONE,
    val screenFrameScale: Double = 1.0,
    val screenFrameToolbarScale: Double = 1.0,
    val screenFrameOuterCornerRadius: Double? = null,
    val screenFrameContentCornerRadius: Double? = null,
    val screenFrameTitle: String = "",
    val screenFrameBrowserAddress: String = "",
    val patternScale: Double = 1.0,
    val patternOpacity: Double = 1.0,
) {
    init {
        require(padding.isFinite() && padding in 0.0..1000.0)
        require(contentScale.isFinite() && contentScale in 0.05..8.0)
        require(cornerRadius.isFinite() && cornerRadius in 0.0..500.0)
        require(borderWidth.isFinite() && borderWidth in 0.0..60.0)
        require(shadowStrength.isFinite() && shadowStrength in 0.0..1.0)
        require(backgroundBlur.isFinite() && backgroundBlur in 0.0..96.0)
        require(insetOpacity.isFinite() && insetOpacity in 0.0..1.0)
    }

    val resolvedFixedAspectRatio: Double?
        get() = if (aspectRatio == CanvasAspectRatio.CUSTOM) customAspectRatio.value else aspectRatio.fixedRatio

    fun pixelDimensions(
        resolution: CanvasResolution,
        sourceAspectRatio: Double? = null,
        sourcePixelSize: CanvasDimensions? = null,
    ): CanvasDimensions {
        if (resolution == CanvasResolution.SOURCE || resolution.shortEdge == null || resolution.longEdge == null) {
            return sourcePixelDimensions(sourcePixelSize)
        }
        val shortEdge = resolution.shortEdge
        val longEdge = resolution.longEdge
        return when (aspectRatio) {
            CanvasAspectRatio.ADAPTIVE -> {
                val ratio = (sourceAspectRatio ?: (16.0 / 9.0)).coerceIn(0.2, 5.0)
                if (ratio >= 1) {
                    CanvasDimensions(even(longEdge.toDouble()), even(longEdge / ratio))
                } else {
                    CanvasDimensions(even(longEdge * ratio.toDouble()), even(longEdge.toDouble()))
                }
            }
            CanvasAspectRatio.LANDSCAPE -> CanvasDimensions(shortEdge * 16 / 9, shortEdge)
            CanvasAspectRatio.STANDARD -> CanvasDimensions(shortEdge * 4 / 3, shortEdge)
            CanvasAspectRatio.PORTRAIT -> CanvasDimensions(shortEdge, shortEdge * 16 / 9)
            CanvasAspectRatio.SQUARE -> CanvasDimensions(shortEdge, shortEdge)
            CanvasAspectRatio.STANDARD_PORTRAIT -> CanvasDimensions(shortEdge, shortEdge * 4 / 3)
            CanvasAspectRatio.CINEMA, CanvasAspectRatio.CINEMA_PORTRAIT, CanvasAspectRatio.CUSTOM -> {
                val ratio = resolvedFixedAspectRatio ?: 1.0
                if (ratio >= 1) {
                    CanvasDimensions(even(longEdge.toDouble()), even(longEdge / ratio))
                } else {
                    CanvasDimensions(even(longEdge * ratio), even(longEdge.toDouble()))
                }
            }
        }
    }

    private fun sourcePixelDimensions(sourcePixelSize: CanvasDimensions?): CanvasDimensions {
        val source = sourcePixelSize ?: CanvasDimensions(1920, 1080)
        val safeCrop = crop.clamped()
        val croppedWidth = max(source.width * safeCrop.width, 2.0)
        val croppedHeight = max(source.height * safeCrop.height, 2.0)
        if (aspectRatio == CanvasAspectRatio.ADAPTIVE) {
            return CanvasDimensions(even(croppedWidth), even(croppedHeight))
        }
        val targetRatio = resolvedFixedAspectRatio ?: (croppedWidth / croppedHeight)
        return if (croppedWidth / croppedHeight >= targetRatio) {
            CanvasDimensions(even(croppedHeight * targetRatio), even(croppedHeight))
        } else {
            CanvasDimensions(even(croppedWidth), even(croppedWidth / targetRatio))
        }
    }

    private fun even(value: Double): Int = max((value / 2).roundToInt() * 2, 2)

    private fun even(value: Int): Int = max(value - value % 2, 2)
}

@Serializable
data class AudioStyle(
    val isSystemMuted: Boolean = false,
    val isMicrophoneMuted: Boolean = false,
    val systemVolume: Double = 0.82,
    val microphoneVolume: Double = 0.9,
) {
    init {
        require(systemVolume in 0.0..1.0)
        require(microphoneVolume in 0.0..1.0)
    }
}

@Serializable
data class CameraStyle(
    val shape: CameraShape = CameraShape.SQUARE,
    val position: NormalizedPoint = NormalizedPoint(0.96, 0.47),
    val size: Double = 0.29,
    val borderWidth: Double = 3.0,
    val shadowStrength: Double = 0.25,
    val roundness: Double = 0.5,
    val isMirrored: Boolean = true,
    val isHidden: Boolean = false,
    val scaleDuringZoom: Double = 0.7,
    val contentPosition: NormalizedPoint = NormalizedPoint.Center,
    val contentScale: Double = 1.0,
) {
    init {
        require(size in 0.01..1.0)
        require(borderWidth in 0.0..18.0)
        require(shadowStrength in 0.0..1.0)
        require(roundness in 0.0..1.0)
        require(scaleDuringZoom in 0.1..2.0)
        require(contentScale in 1.0..4.0)
    }
}

@Serializable
data class CursorStyle(
    val assetID: CursorAssetID = CursorAssetID.Automatic,
    val size: Double = 1.25,
    val hideWhenIdle: Boolean = false,
    val idleDelay: Double = 1.2,
    val clickEffectStyle: CursorClickEffectStyle = CursorClickEffectStyle.RIPPLE,
    val clickColor: HexColor? = null,
    val clickOpacity: Double = 0.8,
    val clickScale: Double = 1.0,
    val motionTiltStrength: Double = 2.0,
) {
    init {
        require(size in 0.25..6.0)
        require(idleDelay in 0.2..8.0)
        require(clickOpacity in 0.05..1.0)
        require(clickScale in 0.4..3.0)
        require(motionTiltStrength in 2.0..4.0)
    }

    val showsClickEffect: Boolean get() = clickEffectStyle != CursorClickEffectStyle.NONE
}

@Serializable
data class MotionStyle(
    val screen: ScreenMotionStyle = ScreenMotionStyle.FOCUSED,
    val cursor: CursorMotionStyle = CursorMotionStyle.SMOOTH,
    val screenSpringMass: Double = 2.4,
    val screenSpringStiffness: Double = 210.0,
    val screenSpringDamping: Double = 42.0,
    val cursorSpringMass: Double = 2.8,
    val cursorSpringStiffness: Double = 450.0,
    val cursorSpringDamping: Double = 66.0,
    val defaultZoomEasing: ZoomEasingPreset = ZoomEasingPreset.SPRING,
    val defaultZoomTransitionDuration: Double = 0.7,
    val defaultZoomScale: Double? = null,
    val defaultScreenMotion: ScreenMotionCreationStyle? = null,
) {
    init {
        require(defaultZoomTransitionDuration in 0.0..5.0)
        defaultZoomScale?.let { require(it in 1.0..6.0) }
    }
}

@Serializable
data class ScreenMotionCreationStyle(
    val target: ScreenMotionState,
    val focusEffect: FocusEffect? = null,
    val easing: ZoomEasingPreset = ZoomEasingPreset.CUBIC,
    val customCurve: ZoomBezierCurve = ZoomBezierCurve.Cubic,
    val leadInDuration: Double = 0.55,
    val returnDuration: Double = 0.7,
) {
    fun clip(timing: TransitionTiming): ScreenMotionClip {
        val resolved = timing.copy(
            easing = easing,
            customCurve = customCurve,
            preferredLeadInDuration = leadInDuration,
            preferredReturnDuration = returnDuration,
            leadInDuration = min(leadInDuration, timing.duration),
            returnDuration = min(returnDuration, timing.returnDuration),
            leadInProgressOffset = 0.0,
            returnProgressOffset = 0.0,
        )
        return ScreenMotionClip(timing = resolved, target = target, focusEffect = focusEffect)
    }
}

@Serializable
data class OpeningSequence(
    val isEnabled: Boolean = false,
    val preset: OpeningSequencePreset = OpeningSequencePreset.LIGHT_3D,
    val motionCurve: ElementMotionCurve = ElementMotionCurve.SWIFT,
    val duration: Double = 2.2,
    val stagger: Double = 0.16,
    val includedElements: List<OpeningSequenceElement> = OpeningSequenceElement.entries.toList(),
    val elementOrder: List<OpeningSequenceElement> = listOf(
        OpeningSequenceElement.SCREEN,
        OpeningSequenceElement.CAMERA,
        OpeningSequenceElement.STICKERS,
    ),
) {
    init {
        require(duration in 0.4..8.0)
    }

    fun maximumStagger(elementCount: Int): Double {
        val gapCount = max(elementCount - 1, 0)
        if (gapCount <= 0) return 1.2
        return min(1.2, max(duration - MinimumElementDuration, 0.0) / gapCount)
    }

    fun normalized(): OpeningSequence = copy(
        duration = duration.coerceIn(0.4, 8.0),
        stagger = stagger.coerceIn(0.0, maximumStagger(includedElements.size)),
        includedElements = includedElements.distinct(),
        elementOrder = (elementOrder + OpeningSequenceElement.entries).distinct(),
    )

    fun availableElements(hasCamera: Boolean, hasOpeningSticker: Boolean): List<OpeningSequenceElement> =
        elementOrder.filter { element ->
            when (element) {
                OpeningSequenceElement.SCREEN -> true
                OpeningSequenceElement.CAMERA -> hasCamera
                OpeningSequenceElement.STICKERS -> hasOpeningSticker
            }
        }

    fun participatingElements(hasCamera: Boolean, hasOpeningSticker: Boolean): List<OpeningSequenceElement> =
        availableElements(hasCamera, hasOpeningSticker).filter { includedElements.contains(it) }

    companion object {
        const val MinimumElementDuration: Double = 0.18
    }
}

@Serializable
data class ExportSettings(
    val frameRate: OutputFrameRate = OutputFrameRate.FPS_60,
    val resolution: CanvasResolution = CanvasResolution.SOURCE,
)

@Serializable
data class CaptureConfiguration(
    val source: CaptureSource = CaptureSource.DISPLAY,
    val displayName: String? = null,
    val area: NormalizedRect? = null,
    val captureFrameRate: OutputFrameRate = OutputFrameRate.FPS_60,
    val captureCodec: CaptureCodec = CaptureCodec.H264,
    val captureResolutionLimit: CaptureResolutionLimit = CaptureResolutionLimit.NATIVE,
    val recordsSystemAudio: Boolean = true,
    val systemAudioScope: SystemAudioScope = SystemAudioScope.ALL,
    val recordsMicrophone: Boolean = false,
    val microphoneDeviceID: String? = null,
    val microphoneDeviceName: String? = null,
    val recordsCamera: Boolean = false,
    val cameraDeviceID: String? = null,
    val cameraDeviceName: String? = null,
    val cameraCaptureResolution: String? = null,
    /** Android-only: touch capture requires the optional accessibility service. */
    val recordsTouch: Boolean = false,
    val hidesDesktopFiles: Boolean = false,
    val hidesDock: Boolean = false,
)

@Serializable
data class ProjectMediaReference(
    val relativePath: String,
    val startOffset: Double = 0.0,
    val sourceStartTime: Double = 0.0,
    val sourceTimeScale: Double? = null,
    val sourceEndTime: Double? = null,
    val syncAnchors: List<MediaSyncAnchor> = emptyList(),
    val durationSeconds: Double? = null,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
) {
    init {
        require(startOffset >= 0 && sourceStartTime >= 0)
    }
}

@Serializable
data class ProjectMediaManifest(
    val screen: ProjectMediaReference,
    val camera: ProjectMediaReference? = null,
    val microphone: ProjectMediaReference? = null,
    val systemAudio: ProjectMediaReference? = null,
    val pointerEvents: ProjectMediaReference? = null,
    val background: ProjectMediaReference? = null,
)

@Serializable
data class RecordingMarker(
    val id: String = randomId(),
    val sourceTime: Double,
    val number: Int,
) {
    init {
        require(sourceTime >= 0)
        require(number > 0)
    }
}

@Serializable
data class RecorderProject(
    val schemaVersion: Int = CurrentSchemaVersion,
    val id: String = randomId(),
    val title: String = "未命名录制",
    val createdAtEpochMs: Long = 0L,
    val capture: CaptureConfiguration = CaptureConfiguration(),
    val canvas: CanvasStyle = CanvasStyle(),
    val camera: CameraStyle = CameraStyle(),
    val audio: AudioStyle = AudioStyle(),
    val motion: MotionStyle = MotionStyle(),
    val openingSequence: OpeningSequence = OpeningSequence(),
    val cursorStyle: CursorStyle = CursorStyle(),
    val exportSettings: ExportSettings = ExportSettings(),
    val timeline: ProjectTimeline = ProjectTimeline(),
    val media: ProjectMediaManifest? = null,
    val recordingMarkers: List<RecordingMarker> = emptyList(),
    val scenePresetId: String? = null,
    val notes: String = "",
) {
    val hasRecording: Boolean get() = media != null

    companion object {
        const val CurrentSchemaVersion: Int = 1
    }
}

/** Deep-enough copy used by the undo stack and immutable UI snapshots. */
fun RecorderProject.withTimeline(timeline: ProjectTimeline): RecorderProject = copy(timeline = timeline)

fun RecorderProject.withCanvas(canvas: CanvasStyle): RecorderProject = copy(canvas = canvas)

fun RecorderProject.withCamera(camera: CameraStyle): RecorderProject = copy(camera = camera)

fun RecorderProject.withCursor(cursorStyle: CursorStyle): RecorderProject = copy(cursorStyle = cursorStyle)

fun RecorderProject.withOpening(opening: OpeningSequence): RecorderProject = copy(openingSequence = opening.normalized())

fun RecorderProject.withMotion(motion: MotionStyle): RecorderProject = copy(motion = motion)

fun RecorderProject.withAudio(audio: AudioStyle): RecorderProject = copy(audio = audio)

fun RecorderProject.withExport(export: ExportSettings): RecorderProject = copy(exportSettings = export)
