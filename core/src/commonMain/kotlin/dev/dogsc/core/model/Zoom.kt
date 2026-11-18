package dev.dogsc.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class ZoomBezierCurve(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
) {
    init {
        require(x1 in 0.0..1.0 && y1 in 0.0..1.0 && x2 in 0.0..1.0 && y2 in 0.0..1.0)
    }

    fun point(t: Double): NormalizedPoint {
        val safe = t.coerceIn(0.0, 1.0)
        val inverse = 1 - safe
        val x = 3 * inverse * inverse * safe * x1 +
            3 * inverse * safe * safe * x2 + safe * safe * safe
        val y = 3 * inverse * inverse * safe * y1 +
            3 * inverse * safe * safe * y2 + safe * safe * safe
        return NormalizedPoint(x, y)
    }

    /** Returns the curve's Y value for a normalized time (X). */
    fun value(normalizedTime: Double): Double {
        val targetX = normalizedTime.coerceIn(0.0, 1.0)
        if (targetX <= 0.0 || targetX >= 1.0) return targetX
        var parameter = targetX
        for (i in 0 until 7) {
            val point = point(parameter)
            val error = point.x - targetX
            if (abs(error) < 0.00001) return point.y
            val derivative = xDerivative(parameter)
            if (abs(derivative) < 0.00001) break
            val next = parameter - error / derivative
            if (next < 0 || next > 1) break
            parameter = next
        }
        var lower = 0.0
        var upper = 1.0
        parameter = targetX
        for (i in 0 until 18) {
            val point = point(parameter)
            if (abs(point.x - targetX) < 0.00001) return point.y
            if (point.x < targetX) lower = parameter else upper = parameter
            parameter = (lower + upper) / 2
        }
        return point(parameter).y
    }

    private fun xDerivative(t: Double): Double {
        val safe = t.coerceIn(0.0, 1.0)
        val inverse = 1 - safe
        return 3 * inverse * inverse * x1 + 6 * inverse * safe * (x2 - x1) + 3 * safe * safe * (1 - x2)
    }

    companion object {
        val Linear = ZoomBezierCurve(0.0, 0.0, 1.0, 1.0)
        val Quadratic = ZoomBezierCurve(0.45, 0.0, 0.55, 1.0)
        val Cubic = ZoomBezierCurve(0.65, 0.0, 0.35, 1.0)
        val Quintic = ZoomBezierCurve(0.82, 0.0, 0.18, 1.0)
    }
}

@Serializable
data class FocusEffect(
    val shape: FocusEffectShape? = null,
    val angleDegrees: Double? = null,
    val target: FocusEffectTarget = FocusEffectTarget.ANIMATION,
    val center: NormalizedPoint = NormalizedPoint.Center,
    val size: Double = 0.45,
    val blur: Double = 0.35,
    val softness: Double = 0.5,
    val dimming: Double = 0.18,
) {
    fun clearHalfWidth(shortEdge: Double): Double = shortEdge * size / 2
    fun featherWidth(shortEdge: Double): Double = max(shortEdge * softness * 0.4, 1.0)

    val isValid: Boolean
        get() = listOf(center.x, center.y, blur, softness, dimming).all { it.isFinite() && it in 0.0..1.0 } &&
            (angleDegrees == null || (angleDegrees.isFinite() && angleDegrees in 0.0..360.0)) &&
            size.isFinite() && size in 0.1..1.0

    companion object {
        fun linear(): FocusEffect = FocusEffect(
            shape = FocusEffectShape.LINEAR,
            angleDegrees = 0.0,
            target = FocusEffectTarget.FIXED,
            size = 0.32,
            dimming = 0.0,
        )

        fun normalizedAngle(degrees: Double): Double {
            val result = degrees % 360.0
            return if (result < 0) result + 360 else result
        }
    }
}

@Serializable
data class ZoomAnimationClip(
    val id: String = randomId(),
    val startTime: Double,
    val endTime: Double,
    val scale: Double = 1.6,
    val focus: NormalizedPoint = NormalizedPoint.Center,
    val origin: ZoomKeyframeOrigin = ZoomKeyframeOrigin.MANUAL,
    val easing: ZoomEasingPreset = ZoomEasingPreset.CUBIC,
    val customCurve: ZoomBezierCurve = ZoomBezierCurve.Cubic,
    val enterDuration: Double = 0.7,
    val exitDuration: Double = 0.7,
    val enterProgressOffset: Double = 0.0,
    val exitProgressOffset: Double = 0.0,
    val focusEffect: FocusEffect? = null,
    val preferredEnterDuration: Double? = null,
    val preferredExitDuration: Double? = null,
) {
    init {
        require(startTime >= 0)
        require(endTime >= startTime)
        require(scale >= 1)
        require(enterDuration in 0.0..5.0)
        require(exitDuration in 0.0..5.0)
        require(enterProgressOffset in 0.0..1.0)
        require(exitProgressOffset in 0.0..1.0)
    }

    val duration: Double get() = max(endTime - startTime, 0.0)
    val effectEndTime: Double get() = endTime + exitDuration
    val requestedEnterDuration: Double get() = preferredEnterDuration ?: enterDuration

    fun requestedExitDuration(defaultTransition: Double = 0.7): Double =
        preferredExitDuration ?: if (exitDuration > 0.0001) exitDuration else max(defaultTransition, 0.0)

    fun preservingTransitionIntent(defaultTransition: Double = 0.7): ZoomAnimationClip = copy(
        preferredEnterDuration = preferredEnterDuration ?: enterDuration,
        preferredExitDuration = preferredExitDuration ?: requestedExitDuration(defaultTransition),
    )
}

@Serializable
data class ZoomKeyframe(
    val id: String = randomId(),
    val time: Double,
    val scale: Double,
    val focus: NormalizedPoint,
    val origin: ZoomKeyframeOrigin = ZoomKeyframeOrigin.AUTOMATIC,
)

data class ManualZoomViewportTransition(
    val fromScale: Double,
    val fromFocus: NormalizedPoint,
    val toScale: Double,
    val toFocus: NormalizedPoint,
    val progress: Double,
)

data class ZoomSample(
    val scale: Double,
    val focus: NormalizedPoint,
    val framingProgress: Double = if (scale > 1.0001) 1.0 else 0.0,
    val targetScale: Double = max(scale, 1.0),
    val manualViewportTransition: ManualZoomViewportTransition? = null,
)

data class ZoomViewportTransform(val scale: Double, val translation: NormalizedPoint) {
    fun applyTo(point: NormalizedPoint): NormalizedPoint = NormalizedPoint(
        x = 0.5 + scale * (point.x - 0.5) + translation.x,
        y = 0.5 + scale * (point.y - 0.5) + translation.y,
    )

    companion object {
        const val FocusEdgeInset = 0.0
        const val CompositionGuideInset = 0.12
        const val FramingTargetInset = 0.30

        fun make(
            sample: ZoomSample,
            crop: NormalizedCrop = NormalizedCrop.Full,
            visibleRect: CompositionRect? = null,
        ): ZoomViewportTransform {
            sample.manualViewportTransition?.let { transition ->
                val from = make(
                    ZoomSample(
                        scale = transition.fromScale,
                        focus = transition.fromFocus,
                        framingProgress = 1.0,
                    ),
                    crop,
                    visibleRect,
                )
                val to = make(
                    ZoomSample(
                        scale = transition.toScale,
                        focus = transition.toFocus,
                        framingProgress = 1.0,
                    ),
                    crop,
                    visibleRect,
                )
                val progress = transition.progress.coerceIn(0.0, 1.0)
                return ZoomViewportTransform(
                    scale = from.scale + (to.scale - from.scale) * progress,
                    translation = NormalizedPoint(
                        from.translation.x + (to.translation.x - from.translation.x) * progress,
                        from.translation.y + (to.translation.y - from.translation.y) * progress,
                    ),
                )
            }
            val scale = sample.scale.coerceIn(1.0, 6.0)
            val safeCrop = crop.clamped()
            val localFocus = NormalizedPoint(
                ((sample.focus.x - safeCrop.x) / safeCrop.width).coerceIn(0.0, 1.0),
                ((sample.focus.y - safeCrop.y) / safeCrop.height).coerceIn(0.0, 1.0),
            ).constrained(FocusEdgeInset)
            val destination = localFocus.constrained(FramingTargetInset)
            val progress = sample.framingProgress.coerceIn(0.0, 1.0)
            val desiredPoint = NormalizedPoint(
                localFocus.x + (destination.x - localFocus.x) * progress,
                localFocus.y + (destination.y - localFocus.y) * progress,
            )
            val scaledPoint = NormalizedPoint(
                0.5 + scale * (localFocus.x - 0.5),
                0.5 + scale * (localFocus.y - 0.5),
            )
            var translation = NormalizedPoint(
                desiredPoint.x - scaledPoint.x,
                desiredPoint.y - scaledPoint.y,
            )
            if (visibleRect != null) {
                translation = NormalizedPoint(
                    translation.x.coerceIn(
                        visibleRect.x + visibleRect.width - 0.5 - scale / 2,
                        visibleRect.x - 0.5 + scale / 2,
                    ),
                    translation.y.coerceIn(
                        visibleRect.y + visibleRect.height - 0.5 - scale / 2,
                        visibleRect.y - 0.5 + scale / 2,
                    ),
                )
            }
            return ZoomViewportTransform(scale, translation)
        }
    }
}

/** Shared by the inspector, preview and export. */
object ZoomTransitionResolution {
    fun resolve(animations: List<ZoomAnimationClip>, outputDuration: Double): List<ZoomAnimationClip> {
        if (!outputDuration.isFinite() || outputDuration <= 0) return emptyList()
        val ordered = animations
            .filter { it.startTime < outputDuration && it.duration > 0.001 }
            .sortedWith(compareBy({ it.startTime }, { it.id }))
        return ordered.mapIndexed { index, authored ->
            var clip = authored
            val next = ordered.getOrNull(index + 1)
            val requestedEnter = max(clip.requestedEnterDuration, 0.0)
            val requestedExit = max(clip.requestedExitDuration(), 0.0)
            if (next != null) {
                val gap = max(next.startTime - clip.endTime, 0.0)
                clip = clip.copy(
                    exitDuration = if (gap <= ZoomInterpolator.AdjacencyTolerance) 0.0 else min(requestedExit, gap)
                )
            } else {
                val available = max(outputDuration - clip.startTime, 0.0)
                val exit = min(requestedExit, max(available - min(requestedEnter, available * 0.5), 0.0))
                val endTime = min(clip.endTime, outputDuration - exit)
                clip = clip.copy(
                    endTime = endTime,
                    exitProgressOffset = if (endTime < authored.endTime - 0.000001) 0.0 else clip.exitProgressOffset,
                    exitDuration = min(requestedExit, max(outputDuration - endTime, 0.0)),
                )
            }
            clip.copy(enterDuration = min(requestedEnter, clip.duration))
        }
    }
}

class ZoomAnimationTrack(
    private val authoredAnimations: List<ZoomAnimationClip>,
    val outputDuration: Double? = null,
) {
    val animations: List<ZoomAnimationClip> = run {
        val resolved = outputDuration?.let { ZoomTransitionResolution.resolve(authoredAnimations, it) }
            ?: authoredAnimations
        if (resolved.any { it.duration <= 0.001 } || resolved.zipWithNext().any { (a, b) ->
                if (a.startTime == b.startTime) a.id > b.id else a.startTime > b.startTime
            }
        ) {
            resolved.filter { it.duration > 0.001 }.sortedWith(compareBy({ it.startTime }, { it.id }))
        } else {
            resolved
        }
    }

    fun boundedTo(duration: Double?): ZoomAnimationTrack =
        if (duration == null || duration == outputDuration) this
        else ZoomAnimationTrack(authoredAnimations, duration)

    fun sample(
        at: Double,
        motion: MotionStyle = MotionStyle(),
        automaticFocus: NormalizedPoint? = null,
        inheritedAutomaticFocus: NormalizedPoint? = null,
        reanchorAutomaticEntry: Boolean = false,
    ): ZoomSample = ZoomInterpolator.sample(
        orderedAnimations = animations,
        activeClipIndex = activeClipIndex(at),
        time = at,
        motion = motion,
        automaticFocus = automaticFocus,
        inheritedAutomaticFocus = inheritedAutomaticFocus,
        reanchorAutomaticEntry = reanchorAutomaticEntry,
    )

    fun activeClip(at: Double): ZoomAnimationClip? = activeClipIndex(at)?.let { animations[it] }

    fun activeAutomaticClip(at: Double): ZoomAnimationClip? =
        activeClip(at)?.takeIf { it.origin == ZoomKeyframeOrigin.AUTOMATIC }

    fun adjacentPreviousClip(to: ZoomAnimationClip): ZoomAnimationClip? {
        val index = animations.indexOfFirst { it.id == to.id }
        if (index <= 0) return null
        val previous = animations[index - 1]
        return if (abs(previous.endTime - to.startTime) <= ZoomInterpolator.AdjacencyTolerance) previous else null
    }

    /** prefixEffectEnd[i] = max effectEndTime over animations[0..i]; precomputed once. */
    private val prefixEffectEnd: DoubleArray = DoubleArray(animations.size).also { array ->
        var running = Double.NEGATIVE_INFINITY
        for (i in animations.indices) {
            running = max(running, animations[i].effectEndTime)
            array[i] = running
        }
    }

    private fun activeClipIndex(at: Double): Int? {
        if (!at.isFinite() || animations.isEmpty()) return null
        var lower = 0
        var upper = animations.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (animations[middle].startTime <= at) lower = middle + 1 else upper = middle
        }
        if (lower == 0) return null
        var index = lower - 1
        while (index >= 0) {
            val clip = animations[index]
            if (at >= clip.startTime && at <= clip.effectEndTime) return index
            if (index == 0) return null
            if (prefixEffectEnd[index - 1] < at) return null
            index--
        }
        return null
    }
}

object ZoomInterpolator {
    const val AdjacencyTolerance: Double = 1.0 / 120.0

    fun sample(
        animations: List<ZoomAnimationClip>,
        at: Double,
        motion: MotionStyle = MotionStyle(),
        automaticFocus: NormalizedPoint? = null,
        inheritedAutomaticFocus: NormalizedPoint? = null,
        reanchorAutomaticEntry: Boolean = false,
    ): ZoomSample = ZoomAnimationTrack(animations).sample(
        at,
        motion,
        automaticFocus,
        inheritedAutomaticFocus,
        reanchorAutomaticEntry,
    )

    internal fun sample(
        orderedAnimations: List<ZoomAnimationClip>,
        activeClipIndex: Int?,
        time: Double,
        motion: MotionStyle,
        automaticFocus: NormalizedPoint?,
        inheritedAutomaticFocus: NormalizedPoint?,
        reanchorAutomaticEntry: Boolean,
    ): ZoomSample {
        val ordered = orderedAnimations
        val clipIndex = activeClipIndex ?: return ZoomSample(1.0, NormalizedPoint.Center, 0.0, 1.0)
        if (clipIndex !in ordered.indices) return ZoomSample(1.0, NormalizedPoint.Center, 0.0, 1.0)
        val clip = ordered[clipIndex]
        val targetFocus = automaticTargetFocus(clip, automaticFocus)
        val previous = ordered.getOrNull(clipIndex - 1)
        val adjacentPrevious = previous?.takeIf {
            abs(it.endTime - clip.startTime) <= AdjacencyTolerance
        }
        val inheritedStart = adjacentPrevious?.let { previousClip ->
            ZoomSample(
                scale = previousClip.scale,
                focus = automaticTargetFocus(previousClip, inheritedAutomaticFocus),
                framingProgress = 1.0,
                targetScale = previousClip.scale,
            )
        }
        val requestedEnterDuration =
            if (inheritedStart != null && clip.enterDuration <= 0.001) motion.defaultZoomTransitionDuration
            else clip.enterDuration
        val effectiveEnterDuration = min(requestedEnterDuration, clip.duration)
        val zoomProgress: Double = when {
            effectiveEnterDuration > 0.001 && time < clip.startTime + effectiveEnterDuration -> {
                val local = (time - clip.startTime) / effectiveEnterDuration
                val offset = if (reanchorAutomaticEntry) 0.0 else clip.enterProgressOffset
                val linear = offset + (1 - offset) * local
                easedProgress(linear, clip.easing, clip.customCurve, motion)
            }
            time <= clip.endTime -> 1.0
            clip.exitDuration > 0.001 -> {
                val local = (time - clip.endTime) / clip.exitDuration
                val linear = clip.exitProgressOffset + (1 - clip.exitProgressOffset) * local
                1 - easedProgress(linear, clip.easing, clip.customCurve, motion)
            }
            else -> 0.0
        }
        if (inheritedStart != null && time <= clip.endTime) {
            val manualTransition = if (clip.origin == ZoomKeyframeOrigin.MANUAL) {
                ManualZoomViewportTransition(
                    fromScale = inheritedStart.scale,
                    fromFocus = inheritedStart.focus,
                    toScale = clip.scale,
                    toFocus = clip.focus,
                    progress = zoomProgress,
                )
            } else {
                null
            }
            val scale = interpolate(inheritedStart.scale, clip.scale, zoomProgress)
            return ZoomSample(
                scale = scale,
                focus = NormalizedPoint(
                    interpolate(inheritedStart.focus.x, targetFocus.x, zoomProgress),
                    interpolate(inheritedStart.focus.y, targetFocus.y, zoomProgress),
                ),
                framingProgress = 1.0,
                targetScale = scale,
                manualViewportTransition = manualTransition,
            )
        }
        return ZoomSample(
            scale = interpolate(1.0, clip.scale, zoomProgress),
            focus = targetFocus,
            framingProgress = zoomProgress,
            targetScale = clip.scale,
        )
    }

    fun automaticTargetFocus(clip: ZoomAnimationClip, automaticFocus: NormalizedPoint?): NormalizedPoint {
        val base = clip.focus.constrained(ZoomViewportTransform.FocusEdgeInset)
        if (clip.origin != ZoomKeyframeOrigin.AUTOMATIC || automaticFocus == null) return base
        return automaticFocus.constrained(ZoomViewportTransform.FocusEdgeInset)
    }

    fun easedProgress(
        value: Double,
        preset: ZoomEasingPreset,
        customCurve: ZoomBezierCurve = ZoomBezierCurve.Cubic,
        motion: MotionStyle = MotionStyle(),
    ): Double {
        val t = value.coerceIn(0.0, 1.0)
        return when (preset) {
            ZoomEasingPreset.LINEAR -> ZoomBezierCurve.Linear.value(t)
            ZoomEasingPreset.QUADRATIC -> ZoomBezierCurve.Quadratic.value(t)
            ZoomEasingPreset.CUBIC -> ZoomBezierCurve.Cubic.value(t)
            ZoomEasingPreset.QUINTIC -> ZoomBezierCurve.Quintic.value(t)
            ZoomEasingPreset.SPRING -> springProgress(
                t,
                motion.screenSpringMass,
                motion.screenSpringStiffness,
                motion.screenSpringDamping,
            )
            ZoomEasingPreset.CUSTOM -> customCurve.value(t)
        }
    }

    fun sampleKeyframes(keyframes: List<ZoomKeyframe>, at: Double, motion: MotionStyle = MotionStyle()): ZoomSample {
        val ordered = keyframes.sortedBy { it.time }
        val first = ordered.firstOrNull() ?: return ZoomSample(1.0, NormalizedPoint.Center, 0.0, 1.0)
        if (at <= first.time) {
            return ZoomSample(first.scale, first.focus, if (first.scale > 1.0001) 1.0 else 0.0, first.scale)
        }
        val last = ordered.last()
        if (at >= last.time) {
            return ZoomSample(last.scale, last.focus, if (last.scale > 1.0001) 1.0 else 0.0, last.scale)
        }
        val upperIndex = ordered.indexOfFirst { it.time >= at }
        if (upperIndex <= 0) {
            return ZoomSample(first.scale, first.focus, if (first.scale > 1.0001) 1.0 else 0.0, first.scale)
        }
        val lower = ordered[upperIndex - 1]
        val upper = ordered[upperIndex]
        val duration = max(upper.time - lower.time, 1e-9)
        val linear = ((at - lower.time) / duration).coerceIn(0.0, 1.0)
        val progress = when (motion.screen) {
            ScreenMotionStyle.FOCUSED -> springProgress(
                linear,
                motion.screenSpringMass,
                motion.screenSpringStiffness,
                motion.screenSpringDamping,
            )
            ScreenMotionStyle.SMOOTH -> smootherStep(linear)
        }
        val sampledScale = interpolate(lower.scale, upper.scale, progress)
        val segmentTargetScale = max(lower.scale, upper.scale)
        val framingProgress = if (segmentTargetScale > 1.0001) {
            ((sampledScale - 1) / (segmentTargetScale - 1)).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        return ZoomSample(
            scale = sampledScale,
            focus = NormalizedPoint(
                interpolate(lower.focus.x, upper.focus.x, progress),
                interpolate(lower.focus.y, upper.focus.y, progress),
            ),
            framingProgress = framingProgress,
            targetScale = segmentTargetScale,
        )
    }

    fun springProgress(value: Double, mass: Double, stiffness: Double, damping: Double): Double {
        val t = value.coerceIn(0.0, 1.0)
        if (t <= 0.0 || t >= 1.0) return t
        val safeMass = max(mass, 0.01)
        val safeStiffness = max(stiffness, 0.01)
        val safeDamping = max(damping, 0.01)
        val omega0 = sqrt(safeStiffness / safeMass)
        val zeta = min(safeDamping / (2 * sqrt(safeStiffness * safeMass)), 0.9999)
        val omegaD = omega0 * sqrt(max(1 - zeta * zeta, 1e-9))
        val duration = 0.72

        fun response(seconds: Double): Double {
            val envelope = exp(-zeta * omega0 * seconds)
            val oscillation = cos(omegaD * seconds) + (zeta * omega0 / omegaD) * sin(omegaD * seconds)
            return 1 - envelope * oscillation
        }

        return (response(t * duration) / response(duration)).coerceIn(0.0, 1.0)
    }

    fun smootherStep(value: Double): Double {
        val x = value.coerceIn(0.0, 1.0)
        return x * x * x * (x * (x * 6 - 15) + 10)
    }

    private fun interpolate(from: Double, to: Double, progress: Double): Double =
        from + (to - from) * progress
}

/** Migration from legacy keyframes to editable zoom clips. */
fun zoomClipsFromKeyframes(
    keyframes: List<ZoomKeyframe>,
    defaultEasing: ZoomEasingPreset = ZoomEasingPreset.CUBIC,
    baseScale: Double = 1.0,
): List<ZoomAnimationClip> {
    val ordered = keyframes.filter { it.time.isFinite() && it.time >= 0 }
        .sortedWith(compareBy({ it.time }, { it.id }))
    if (ordered.isEmpty()) return emptyList()
    val threshold = baseScale + 0.0001
    val result = mutableListOf<ZoomAnimationClip>()
    var index = 0
    while (index < ordered.size) {
        if (ordered[index].scale <= threshold) {
            index++
            continue
        }
        val firstZoomed = index
        var lastZoomed = index
        while (lastZoomed + 1 < ordered.size && ordered[lastZoomed + 1].scale > threshold) {
            lastZoomed++
        }
        val start = ordered[max(firstZoomed - 1, 0)].time
        val end = if (lastZoomed + 1 < ordered.size) {
            ordered[lastZoomed + 1].time
        } else {
            max(ordered[lastZoomed].time + 0.6, start + 0.6)
        }
        val frames = ordered.subList(firstZoomed, lastZoomed + 1)
        val representative = frames.maxWithOrNull(compareBy({ it.scale }, { it.time })) ?: frames.first()
        val origin = if (frames.any { it.origin == ZoomKeyframeOrigin.MANUAL }) {
            ZoomKeyframeOrigin.MANUAL
        } else {
            ZoomKeyframeOrigin.AUTOMATIC
        }
        if (end - start > 0.01) {
            result += ZoomAnimationClip(
                startTime = start,
                endTime = end,
                scale = representative.scale,
                focus = representative.focus,
                origin = origin,
                easing = defaultEasing,
            )
        }
        index = lastZoomed + 1
    }
    return result
}
