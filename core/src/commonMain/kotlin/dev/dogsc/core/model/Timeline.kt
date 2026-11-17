package dev.dogsc.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/**
 * One retained portion of the original recording. Segment order is output
 * order; output start is derived by accumulating durations so a trim or ripple
 * delete can never leave two contradictory timeline positions.
 */
@Serializable
data class RecordingSegment(
    val id: String = randomId(),
    val sourceStart: Double,
    val sourceDuration: Double,
    /** Source seconds consumed by one output second. 1x ... 100x fast-forward. */
    val playbackRate: Double = 1.0,
) {
    init {
        require(sourceStart >= 0)
        require(sourceDuration > 0)
        require(playbackRate.isFinite() && playbackRate > 0)
    }

    val sourceEnd: Double get() = sourceStart + sourceDuration
    val outputDuration: Double get() = sourceDuration / playbackRate

    companion object {
        const val MinimumPlaybackRate: Double = 1.0
        const val MaximumPlaybackRate: Double = 100.0
    }
}

@Serializable
sealed class SourceSequence {
    @Serializable
    @SerialName("fullRecording")
    data object FullRecording : SourceSequence()

    @Serializable
    @SerialName("edited")
    data class Edited(val segments: List<RecordingSegment>) : SourceSequence()

    val segmentsOrEmpty: List<RecordingSegment>
        get() = when (this) {
            FullRecording -> emptyList()
            is Edited -> segments
        }
}

/**
 * Timing shared by typed scene transitions. The clip interval is enter + hold:
 * the target eases in over `leadInDuration`, then holds until `endTime`.
 */
@Serializable
data class TransitionTiming(
    val startTime: Double,
    val duration: Double = 0.7,
    val leadInDuration: Double = 0.7,
    val leadInProgressOffset: Double = 0.0,
    val easing: ZoomEasingPreset = ZoomEasingPreset.CUBIC,
    val customCurve: ZoomBezierCurve = ZoomBezierCurve.Cubic,
    val returnDuration: Double = 0.0,
    val returnProgressOffset: Double = 0.0,
    val preferredLeadInDuration: Double? = null,
    val preferredReturnDuration: Double? = null,
) {
    init {
        require(startTime >= 0 && startTime.isFinite())
        require(duration > 0 && duration.isFinite())
        require(leadInDuration in 0.0..5.0)
        require(leadInProgressOffset in 0.0..1.0)
        require(returnDuration in 0.0..5.0)
        require(returnProgressOffset in 0.0..1.0)
    }

    val endTime: Double get() = startTime + duration
    val leadInEndTime: Double get() = startTime + min(leadInDuration, duration)
    val effectEndTime: Double get() = endTime + returnDuration
    val requestedLeadInDuration: Double get() = preferredLeadInDuration ?: leadInDuration

    fun requestedReturnDuration(defaultTransition: Double = 0.7): Double =
        preferredReturnDuration ?: if (returnDuration > 0.0001) returnDuration else max(defaultTransition, 0.0)

    fun preservingTransitionIntent(defaultTransition: Double = 0.7): TransitionTiming = copy(
        preferredLeadInDuration = preferredLeadInDuration ?: leadInDuration,
        preferredReturnDuration = preferredReturnDuration ?: requestedReturnDuration(defaultTransition),
    )
}

@Serializable
data class PrimarySegmentAudioOverrides(
    val systemVolume: Double? = null,
    val microphoneVolume: Double? = null,
    val isSystemMuted: Boolean? = null,
    val isMicrophoneMuted: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = systemVolume == null && microphoneVolume == null &&
            isSystemMuted == null && isMicrophoneMuted == null
}

/** The single persisted owner of every time-varying project edit. */
@Serializable
data class ProjectTimeline(
    val sourceSequence: SourceSequence = SourceSequence.FullRecording,
    val primarySegmentAudioOverrides: Map<String, PrimarySegmentAudioOverrides> = emptyMap(),
    val zoomClips: List<ZoomAnimationClip> = emptyList(),
    val screenMotionClips: List<ScreenMotionClip> = emptyList(),
    val cameraMotionClips: List<CameraMotionClip> = emptyList(),
    val mosaicClips: List<MosaicClip> = emptyList(),
    val stickerClips: List<StickerClip> = emptyList(),
)

/** Computes the output clock for a source sequence. */
object TimelineMath {
    fun outputDuration(
        sequence: SourceSequence,
        sourceDuration: Double,
    ): Double = when (sequence) {
        SourceSequence.FullRecording -> max(sourceDuration, 0.0)
        is SourceSequence.Edited -> sequence.segments.sumOf { it.outputDuration }
    }

    fun segments(sequence: SourceSequence, sourceDuration: Double): List<RecordingSegment> =
        when (sequence) {
            SourceSequence.FullRecording -> if (sourceDuration > 0) {
                listOf(RecordingSegment(sourceStart = 0.0, sourceDuration = sourceDuration))
            } else {
                emptyList()
            }
            is SourceSequence.Edited -> sequence.segments
        }
}
