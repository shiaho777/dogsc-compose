package dev.dogsc.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Serializable
data class ScreenMotionState(
    val position: NormalizedPoint = NormalizedPoint.Center,
    val scale: Double = 1.0,
    val rotationX: Double = 0.0,
    val rotationY: Double = 0.0,
    val rotationZ: Double = 0.0,
    val perspective: Double = 0.65,
)

@Serializable
data class ScreenMotionClip(
    val id: String = randomId(),
    val timing: TransitionTiming,
    val target: ScreenMotionState,
    val groupID: String? = null,
    val focusEffect: FocusEffect? = null,
)

data class ScreenAnchorViewport(
    val canvasWidth: Double,
    val canvasHeight: Double,
    val fittedWidth: Double,
    val fittedHeight: Double,
    val borderWidthAtScaleOne: Double = 0.0,
    val decorationTopAtScaleOne: Double = 0.0,
    val decorationRightAtScaleOne: Double = 0.0,
    val decorationBottomAtScaleOne: Double = 0.0,
    val decorationLeftAtScaleOne: Double = 0.0,
)

data class ScreenMotionSample(
    val state: ScreenMotionState,
    val manualOffset: CompositionPoint?,
    val projectionAnchor: NormalizedPoint = state.position,
)

private enum class ScreenMotionFraming { PLACEMENT, FOCUS }

class ScreenMotionTrack(private val authoredClips: List<ScreenMotionClip>) {
    val clips: List<ScreenMotionClip> = if (needsRepair(authoredClips)) {
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

    fun activeClip(at: Double): ScreenMotionClip? {
        if (!at.isFinite()) return null
        val index = lastStartedClipIndex(at) ?: return null
        val clip = clips[index]
        return if (clip.timing.returnDuration > 0 && at >= clip.timing.effectEndTime) null else clip
    }

    fun sample(
        at: Double,
        base: ScreenMotionState,
        motion: MotionStyle = MotionStyle(),
        anchorViewport: ScreenAnchorViewport? = null,
    ): ScreenMotionState = sampleLayout(at, base, motion, anchorViewport).state

    fun sampleLayout(
        at: Double,
        base: ScreenMotionState,
        motion: MotionStyle = MotionStyle(),
        anchorViewport: ScreenAnchorViewport? = null,
    ): ScreenMotionSample {
        if (!usesIndexedEvaluation || !at.isFinite()) {
            return sampleByScanning(at, base, motion, anchorViewport)
        }
        val index = lastStartedClipIndex(at)
            ?: return layoutSample(base, anchorViewport, ScreenMotionFraming.PLACEMENT)
        val clip = clips[index]
        if (at < clip.timing.endTime) {
            val inherited: ScreenMotionState
            val inheritedFraming: ScreenMotionFraming
            if (index > 0) {
                val previous = clips[index - 1]
                val touches = abs(previous.timing.endTime - clip.timing.startTime) <= ZoomInterpolator.AdjacencyTolerance
                if (!touches && previous.timing.returnDuration > 0.0001) {
                    inherited = base
                    inheritedFraming = ScreenMotionFraming.PLACEMENT
                } else {
                    inherited = previous.target
                    inheritedFraming = ScreenMotionFraming.FOCUS
                }
            } else {
                inherited = base
                inheritedFraming = ScreenMotionFraming.PLACEMENT
            }
            val leadIn = min(clip.timing.leadInDuration, clip.timing.duration)
            if (leadIn <= 0.0001) {
                return layoutSample(clip.target, anchorViewport, ScreenMotionFraming.FOCUS)
            }
            val local = min((at - clip.timing.startTime) / leadIn, 1.0)
            val linear = clip.timing.leadInProgressOffset + (1 - clip.timing.leadInProgressOffset) * local
            val progress = ZoomInterpolator.easedProgress(
                linear,
                clip.timing.easing,
                clip.timing.customCurve,
                motion,
            )
            return interpolateLayout(
                from = inherited,
                to = clip.target,
                progress = progress,
                anchorViewport = anchorViewport,
                fromFraming = inheritedFraming,
                toFraming = ScreenMotionFraming.FOCUS,
                inheritedProjectionAnchor = if (inheritedFraming == ScreenMotionFraming.FOCUS) inherited.position else null,
                projectionAnchor = clip.target.position,
            )
        }
        if (touchesNextClip(index)) {
            return layoutSample(clip.target, anchorViewport, ScreenMotionFraming.FOCUS)
        }
        returnFalloff(index, base, at, motion, anchorViewport)?.let { return it }
        return if (clip.timing.returnDuration > 0.0001) {
            layoutSample(base, anchorViewport, ScreenMotionFraming.PLACEMENT)
        } else {
            layoutSample(clip.target, anchorViewport, ScreenMotionFraming.FOCUS)
        }
    }

    private fun sampleByScanning(
        at: Double,
        base: ScreenMotionState,
        motion: MotionStyle,
        anchorViewport: ScreenAnchorViewport?,
    ): ScreenMotionSample {
        var inherited = base
        var inheritedFraming = ScreenMotionFraming.PLACEMENT
        var previousIndex: Int? = null
        for ((index, clip) in clips.withIndex()) {
            if (!clip.timing.startTime.isFinite() || !clip.timing.duration.isFinite() || clip.timing.duration <= 0) continue
            if (at < clip.timing.startTime) {
                previousIndex?.let { returnFalloff(it, base, at, motion, anchorViewport) }?.let { return it }
                return layoutSample(inherited, anchorViewport, inheritedFraming)
            }
            if (at < clip.timing.endTime) {
                val leadIn = min(clip.timing.leadInDuration, clip.timing.duration)
                if (leadIn <= 0.0001) return layoutSample(clip.target, anchorViewport, ScreenMotionFraming.FOCUS)
                val local = min((at - clip.timing.startTime) / leadIn, 1.0)
                val linear = clip.timing.leadInProgressOffset + (1 - clip.timing.leadInProgressOffset) * local
                val progress = ZoomInterpolator.easedProgress(
                    linear, clip.timing.easing, clip.timing.customCurve, motion,
                )
                return interpolateLayout(
                    inherited, clip.target, progress, anchorViewport,
                    inheritedFraming, ScreenMotionFraming.FOCUS,
                    if (inheritedFraming == ScreenMotionFraming.FOCUS) inherited.position else null,
                    clip.target.position,
                )
            }
            if (!touchesNextClip(index) && clip.timing.returnDuration > 0.0001) {
                inherited = base
                inheritedFraming = ScreenMotionFraming.PLACEMENT
            } else {
                inherited = clip.target
                inheritedFraming = ScreenMotionFraming.FOCUS
            }
            previousIndex = index
        }
        previousIndex?.let { returnFalloff(it, base, at, motion, anchorViewport) }?.let { return it }
        val index = previousIndex ?: return layoutSample(inherited, anchorViewport, inheritedFraming)
        return if (clips[index].timing.returnDuration > 0.0001) {
            layoutSample(base, anchorViewport, ScreenMotionFraming.PLACEMENT)
        } else {
            layoutSample(inherited, anchorViewport, inheritedFraming)
        }
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
        base: ScreenMotionState,
        at: Double,
        motion: MotionStyle,
        anchorViewport: ScreenAnchorViewport?,
    ): ScreenMotionSample? {
        val clip = clips[index]
        if (clip.timing.returnDuration <= 0.0001 || touchesNextClip(index)) return null
        if (at > clip.timing.endTime + clip.timing.returnDuration) return null
        val local = ((at - clip.timing.endTime) / clip.timing.returnDuration).coerceIn(0.0, 1.0)
        val linear = clip.timing.returnProgressOffset + (1 - clip.timing.returnProgressOffset) * local
        val progress = ZoomInterpolator.easedProgress(
            linear, clip.timing.easing, clip.timing.customCurve, motion,
        )
        return interpolateLayout(
            from = clip.target,
            to = base,
            progress = progress,
            anchorViewport = anchorViewport,
            fromFraming = ScreenMotionFraming.FOCUS,
            toFraming = ScreenMotionFraming.PLACEMENT,
            projectionAnchor = clip.target.position,
        )
    }

    private fun interpolateLayout(
        from: ScreenMotionState,
        to: ScreenMotionState,
        progress: Double,
        anchorViewport: ScreenAnchorViewport?,
        fromFraming: ScreenMotionFraming,
        toFraming: ScreenMotionFraming,
        inheritedProjectionAnchor: NormalizedPoint? = null,
        projectionAnchor: NormalizedPoint,
    ): ScreenMotionSample {
        val t = progress.coerceIn(0.0, 1.0)
        val position: NormalizedPoint
        if (anchorViewport != null) {
            val scaledW0 = from.scale * anchorViewport.fittedWidth
            val scaledW1 = to.scale * anchorViewport.fittedWidth
            val scaledW = mix(scaledW0, scaledW1, t)
            val ux = mix(
                from.position.x * (anchorViewport.canvasWidth - scaledW0),
                to.position.x * (anchorViewport.canvasWidth - scaledW1),
                t,
            )
            val scaledH0 = from.scale * anchorViewport.fittedHeight
            val scaledH1 = to.scale * anchorViewport.fittedHeight
            val scaledH = mix(scaledH0, scaledH1, t)
            val uy = mix(
                from.position.y * (anchorViewport.canvasHeight - scaledH0),
                to.position.y * (anchorViewport.canvasHeight - scaledH1),
                t,
            )
            val denomX = anchorViewport.canvasWidth - scaledW
            val denomY = anchorViewport.canvasHeight - scaledH
            position = NormalizedPoint(
                if (abs(denomX) > 0.001) ux / denomX else mix(from.position.x, to.position.x, t),
                if (abs(denomY) > 0.001) uy / denomY else mix(from.position.y, to.position.y, t),
            )
        } else {
            position = NormalizedPoint(
                mix(from.position.x, to.position.x, t),
                mix(from.position.y, to.position.y, t),
            )
        }
        val state = ScreenMotionState(
            position = position,
            scale = mix(from.scale, to.scale, t),
            rotationX = mixAngle(from.rotationX, to.rotationX, t),
            rotationY = mixAngle(from.rotationY, to.rotationY, t),
            rotationZ = mixAngle(from.rotationZ, to.rotationZ, t),
            perspective = mix(from.perspective, to.perspective, t),
        )
        val manualOffset = anchorViewport?.let { viewport ->
            val fromOffset = directOffset(from, viewport, fromFraming)
            val toOffset = directOffset(to, viewport, toFraming)
            CompositionPoint(mix(fromOffset.x, toOffset.x, t), mix(fromOffset.y, toOffset.y, t))
        }
        return ScreenMotionSample(
            state = state,
            manualOffset = manualOffset,
            projectionAnchor = inheritedProjectionAnchor?.let { inherited ->
                NormalizedPoint(
                    mix(inherited.x, projectionAnchor.x, t),
                    mix(inherited.y, projectionAnchor.y, t),
                )
            } ?: projectionAnchor,
        )
    }

    private fun layoutSample(
        state: ScreenMotionState,
        viewport: ScreenAnchorViewport?,
        framing: ScreenMotionFraming,
    ): ScreenMotionSample = ScreenMotionSample(
        state = state,
        manualOffset = viewport?.let { directOffset(state, it, framing) },
        projectionAnchor = state.position,
    )

    private fun directOffset(
        state: ScreenMotionState,
        viewport: ScreenAnchorViewport,
        framing: ScreenMotionFraming,
    ): CompositionPoint {
        val cardScale = state.scale
        val contentWidth = viewport.fittedWidth * cardScale
        val contentHeight = viewport.fittedHeight * cardScale
        val border = viewport.borderWidthAtScaleOne * cardScale
        val left = border + viewport.decorationLeftAtScaleOne * cardScale
        val right = border + viewport.decorationRightAtScaleOne * cardScale
        val top = border + viewport.decorationTopAtScaleOne * cardScale
        val bottom = border + viewport.decorationBottomAtScaleOne * cardScale
        val decoratedWidth = contentWidth + left + right
        val decoratedHeight = contentHeight + top + bottom
        val contentCenterX: Double
        val contentCenterY: Double
        when (framing) {
            ScreenMotionFraming.PLACEMENT -> {
                contentCenterX = state.position.x * (viewport.canvasWidth - decoratedWidth) + contentWidth / 2 + left
                contentCenterY = state.position.y * (viewport.canvasHeight - decoratedHeight) + contentHeight / 2 + top
            }
            ScreenMotionFraming.FOCUS -> {
                val focusX = state.position.x.coerceIn(0.0, 1.0)
                val focusY = state.position.y.coerceIn(0.0, 1.0)
                val recenter = ((cardScale - 1) / 2).coerceIn(0.0, 1.0) * 0.62
                val boundedX = left + focusX * max(viewport.canvasWidth - left - right, 0.0)
                val boundedY = top + focusY * max(viewport.canvasHeight - top - bottom, 0.0)
                val targetX = mix(boundedX, (viewport.canvasWidth + left - right) / 2, recenter)
                val targetY = mix(boundedY, (viewport.canvasHeight + top - bottom) / 2, recenter)
                contentCenterX = targetX + (0.5 - focusX) * contentWidth
                contentCenterY = targetY + (0.5 - focusY) * contentHeight
            }
        }
        return CompositionPoint(
            contentCenterX - viewport.canvasWidth / 2,
            contentCenterY - viewport.canvasHeight / 2,
        )
    }

    private fun needsRepair(clips: List<ScreenMotionClip>): Boolean {
        if (clips.size <= 1) return false
        return clips.zipWithNext().any { (previous, current) ->
            current.timing.startTime < previous.timing.startTime ||
                (current.timing.startTime == previous.timing.startTime && current.id < previous.id)
        }
    }

    private fun mix(from: Double, to: Double, progress: Double): Double = from + (to - from) * progress

    private fun mixAngle(from: Double, to: Double, progress: Double): Double {
        var delta = (to - from) % 360.0
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        return from + delta * progress
    }
}
