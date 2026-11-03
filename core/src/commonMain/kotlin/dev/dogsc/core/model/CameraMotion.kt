package dev.dogsc.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Serializable
sealed class CameraLayoutMode {
    @Serializable
    @SerialName("shape")
    data class Shape(val shape: CameraShape) : CameraLayoutMode()

    @Serializable
    @SerialName("fullscreen")
    data object Fullscreen : CameraLayoutMode()
}

@Serializable
data class CameraMotionState(
    val layout: CameraLayoutMode = CameraLayoutMode.Shape(CameraShape.SQUARE),
    val position: NormalizedPoint = NormalizedPoint(0.86, 0.82),
    val size: Double = 0.35,
    val roundness: Double = 0.5,
    val opacity: Double = 1.0,
)

@Serializable
data class CameraMotionClip(
    val id: String = randomId(),
    val timing: TransitionTiming,
    val target: CameraMotionState,
    val groupID: String? = null,
)

data class CameraMotionSample(
    val position: NormalizedPoint,
    val size: Double,
    val aspectRatio: Double,
    val cornerFraction: Double,
    val fullscreenProgress: Double,
    val opacity: Double,
)

class CameraMotionTrack(private val authoredClips: List<CameraMotionClip>) {
    val clips: List<CameraMotionClip> = if (needsRepair(authoredClips)) {
        authoredClips.sortedWith(compareBy({ it.timing.startTime }, { it.id }))
    } else {
        authoredClips
    }

    private val usesIndexedEvaluation: Boolean = run {
        val timingsValid = clips.all { it.timing.startTime.isFinite() && it.timing.duration.isFinite() && it.timing.duration > 0 }
        val sequenceValid = clips.zipWithNext().all { (previous, next) ->
            val gap = next.timing.startTime - previous.timing.endTime
            val touches = gap <= ZoomInterpolator.AdjacencyTolerance
            val clears = next.timing.startTime >= previous.timing.effectEndTime - 0.0001
            next.timing.startTime >= previous.timing.endTime - 0.0001 && (touches || clears)
        }
        timingsValid && sequenceValid
    }

    fun sample(
        at: Double,
        base: CameraMotionState,
        cameraAspectRatio: Double,
        canvasAspectRatio: Double,
        motion: MotionStyle = MotionStyle(),
    ): CameraMotionSample {
        val resolvedBase = resolve(base, cameraAspectRatio, canvasAspectRatio)
        if (!usesIndexedEvaluation || !at.isFinite()) {
            return sampleByScanning(at, resolvedBase, cameraAspectRatio, canvasAspectRatio, motion)
        }
        val index = lastStartedClipIndex(at) ?: return resolvedBase
        val clip = clips[index]
        val target = resolve(clip.target, cameraAspectRatio, canvasAspectRatio)
        if (at < clip.timing.endTime) {
            val inherited: CameraMotionSample
            if (index > 0) {
                val previous = clips[index - 1]
                val touches = abs(previous.timing.endTime - clip.timing.startTime) <= ZoomInterpolator.AdjacencyTolerance
                inherited = if (!touches && previous.timing.returnDuration > 0.0001) {
                    resolvedBase
                } else {
                    resolve(previous.target, cameraAspectRatio, canvasAspectRatio)
                }
            } else {
                inherited = resolvedBase
            }
            val leadIn = min(clip.timing.leadInDuration, clip.timing.duration)
            if (leadIn <= 0.0001) return target
            val local = min((at - clip.timing.startTime) / leadIn, 1.0)
            val linear = clip.timing.leadInProgressOffset + (1 - clip.timing.leadInProgressOffset) * local
            val progress = ZoomInterpolator.easedProgress(
                linear, clip.timing.easing, clip.timing.customCurve, motion,
            )
            return transitionSample(inherited, target, progress)
        }
        if (touchesNextClip(index)) return target
        returnFalloff(index, resolvedBase, at, cameraAspectRatio, canvasAspectRatio, motion)?.let { return it }
        return if (clip.timing.returnDuration > 0.0001) resolvedBase else target
    }

    private fun sampleByScanning(
        at: Double,
        resolvedBase: CameraMotionSample,
        cameraAspectRatio: Double,
        canvasAspectRatio: Double,
        motion: MotionStyle,
    ): CameraMotionSample {
        var inherited = resolvedBase
        var previousIndex: Int? = null
        for ((index, clip) in clips.withIndex()) {
            if (!clip.timing.startTime.isFinite() || !clip.timing.duration.isFinite() || clip.timing.duration <= 0) continue
            if (at < clip.timing.startTime) {
                previousIndex?.let {
                    returnFalloff(it, resolvedBase, at, cameraAspectRatio, canvasAspectRatio, motion)
                }?.let { return it }
                return inherited
            }
            val target = resolve(clip.target, cameraAspectRatio, canvasAspectRatio)
            if (at < clip.timing.endTime) {
                val leadIn = min(clip.timing.leadInDuration, clip.timing.duration)
                if (leadIn <= 0.0001) return target
                val local = min((at - clip.timing.startTime) / leadIn, 1.0)
                val linear = clip.timing.leadInProgressOffset + (1 - clip.timing.leadInProgressOffset) * local
                val progress = ZoomInterpolator.easedProgress(
                    linear, clip.timing.easing, clip.timing.customCurve, motion,
                )
                return transitionSample(inherited, target, progress)
            }
            inherited = if (!touchesNextClip(index) && clip.timing.returnDuration > 0.0001) resolvedBase else target
            previousIndex = index
        }
        previousIndex?.let {
            returnFalloff(it, resolvedBase, at, cameraAspectRatio, canvasAspectRatio, motion)
        }?.let { return it }
        val index = previousIndex ?: return inherited
        return if (clips[index].timing.returnDuration > 0.0001) resolvedBase else inherited
    }

    private fun lastStartedClipIndex(at: Double): Int? {
        var lower = 0
        var upper = clips.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (clips[middle].timing.startTime <= at) lower = middle + 1 else upper = middle
        }
        return if (lower == 0) null else lower - 1
    }

    private fun touchesNextClip(index: Int): Boolean {
        val next = clips.getOrNull(index + 1) ?: return false
        return abs(next.timing.startTime - clips[index].timing.endTime) <= ZoomInterpolator.AdjacencyTolerance
    }

    private fun returnFalloff(
        index: Int,
        base: CameraMotionSample,
        at: Double,
        cameraAspectRatio: Double,
        canvasAspectRatio: Double,
        motion: MotionStyle,
    ): CameraMotionSample? {
        val clip = clips[index]
        if (clip.timing.returnDuration <= 0.0001 || touchesNextClip(index)) return null
        if (at > clip.timing.endTime + clip.timing.returnDuration) return null
        val local = ((at - clip.timing.endTime) / clip.timing.returnDuration).coerceIn(0.0, 1.0)
        val linear = clip.timing.returnProgressOffset + (1 - clip.timing.returnProgressOffset) * local
        val progress = ZoomInterpolator.easedProgress(
            linear, clip.timing.easing, clip.timing.customCurve, motion,
        )
        val target = resolve(clip.target, cameraAspectRatio, canvasAspectRatio)
        return transitionSample(target, base, progress)
    }

    private fun transitionSample(
        inherited: CameraMotionSample,
        target: CameraMotionSample,
        progress: Double,
    ): CameraMotionSample {
        val safeProgress = progress.coerceIn(0.0, 1.0)
        if (target.opacity < 0.01) {
            return inherited.copy(opacity = mix(inherited.opacity, 0.0, safeProgress))
        }
        if (inherited.opacity < 0.01) {
            return target.copy(opacity = mix(0.0, target.opacity, safeProgress))
        }
        return CameraMotionSample(
            position = NormalizedPoint(
                mix(inherited.position.x, target.position.x, safeProgress),
                mix(inherited.position.y, target.position.y, safeProgress),
            ),
            size = mix(inherited.size, target.size, safeProgress),
            aspectRatio = mix(inherited.aspectRatio, target.aspectRatio, safeProgress),
            cornerFraction = mix(inherited.cornerFraction, target.cornerFraction, safeProgress),
            fullscreenProgress = mix(inherited.fullscreenProgress, target.fullscreenProgress, safeProgress),
            opacity = mix(inherited.opacity, target.opacity, safeProgress),
        )
    }

    private fun resolve(
        state: CameraMotionState,
        cameraAspectRatio: Double,
        canvasAspectRatio: Double,
    ): CameraMotionSample {
        val aspect: Double
        val cornerFraction: Double
        val fullscreen: Double
        when (val layout = state.layout) {
            is CameraLayoutMode.Shape -> {
                fullscreen = 0.0
                when (layout.shape) {
                    CameraShape.SQUARE -> { aspect = 1.0; cornerFraction = 0.5 * state.roundness }
                    CameraShape.CIRCLE -> { aspect = 1.0; cornerFraction = 0.5 }
                    CameraShape.HORIZONTAL -> { aspect = 1.5; cornerFraction = 0.5 * state.roundness }
                    CameraShape.VERTICAL -> { aspect = 0.72; cornerFraction = 0.5 * state.roundness }
                    CameraShape.ORIGINAL -> { aspect = max(cameraAspectRatio, 0.01); cornerFraction = 0.5 * state.roundness }
                }
            }
            CameraLayoutMode.Fullscreen -> {
                aspect = max(canvasAspectRatio, 0.01)
                cornerFraction = 0.0
                fullscreen = 1.0
            }
        }
        return CameraMotionSample(
            position = state.position,
            size = state.size,
            aspectRatio = aspect,
            cornerFraction = cornerFraction,
            fullscreenProgress = fullscreen,
            opacity = state.opacity,
        )
    }

    private fun needsRepair(clips: List<CameraMotionClip>): Boolean {
        if (clips.size <= 1) return false
        return clips.zipWithNext().any { (previous, current) ->
            current.timing.startTime < previous.timing.startTime ||
                (current.timing.startTime == previous.timing.startTime && current.id < previous.id)
        }
    }

    private fun mix(from: Double, to: Double, progress: Double): Double = from + (to - from) * progress
}
