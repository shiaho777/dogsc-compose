package dev.dogsc.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

@Serializable
data class PointerEventRecord(
    val time: Double,
    val location: NormalizedPoint,
    val kind: PointerEventKind,
    val modifiers: List<PointerModifier> = emptyList(),
    val cursorAssetID: CursorAssetID? = null,
)

data class PointerClickPhase(
    val progress: Double,
    val elapsed: Double,
    val duration: Double,
    val kind: PointerEventKind,
)

data class PointerSample(
    val location: NormalizedPoint,
    val isClicking: Boolean,
    val clickPhase: PointerClickPhase? = null,
    val rotationRadians: Double = 0.0,
    val recordedCursorAssetID: CursorAssetID? = null,
)

data class PointerTrackEvaluation(
    val position: NormalizedPoint?,
    val cursor: PointerSample?,
)

private data class SpringSample(val time: Double, val location: NormalizedPoint)

class PointerTrack(rawEvents: List<PointerEventRecord>) {
    val events: List<PointerEventRecord> = rawEvents
        .filter { it.time.isFinite() && it.time >= 0 }
        .sortedWith(compareBy({ it.time }, { it.kind.ordinal }))

    private var springKey: Triple<Double, Double, Double>? = null
    private var springSamples: List<SpringSample>? = null

    fun prewarmCursorSpring(motion: MotionStyle) {
        if (motion.cursor == CursorMotionStyle.SMOOTH) {
            ensureSpring(motion)
        }
    }

    fun evaluation(
        at: Double,
        motion: MotionStyle = MotionStyle(),
        style: CursorStyle = CursorStyle(),
    ): PointerTrackEvaluation {
        val first = events.firstOrNull() ?: return PointerTrackEvaluation(null, null)
        if (at < first.time) return PointerTrackEvaluation(null, null)
        val upperIndex = firstIndexAfter(at)
        val lowerIndex = if (upperIndex > 0) upperIndex - 1 else 0
        val lower = events[lowerIndex]
        val upper = events.getOrNull(upperIndex)
        val rawLocation: NormalizedPoint = if (motion.cursor == CursorMotionStyle.SMOOTH) {
            springLocation(at, motion) ?: lower.location
        } else if (upper != null && upper.time > lower.time) {
            interpolatedLocation(lowerIndex, upperIndex, at, motion)
        } else {
            lower.location
        }
        val cursorVisible = style.assetID != CursorAssetID.Hidden &&
            (!style.hideWhenIdle || at - lower.time <= style.idleDelay)
        val clickPhase = if (cursorVisible && style.clickEffectStyle != CursorClickEffectStyle.NONE &&
            style.clickEffectStyle.duration > 0
        ) {
            mostRecentClick(at - style.clickEffectStyle.duration, at)?.let { click ->
                val elapsed = max(at - click.time, 0.0)
                val progress = (elapsed / style.clickEffectStyle.duration).coerceIn(0.0, 1.0)
                PointerClickPhase(progress, elapsed, style.clickEffectStyle.duration, click.kind)
            }
        } else {
            null
        }
        val rotation = cursorRotationRadians(at, rawLocation, motion, style.motionTiltStrength)
        return PointerTrackEvaluation(
            position = rawLocation,
            cursor = if (cursorVisible) {
                PointerSample(
                    location = rawLocation,
                    isClicking = clickPhase != null,
                    clickPhase = clickPhase,
                    rotationRadians = rotation,
                    recordedCursorAssetID = lower.cursorAssetID,
                )
            } else {
                null
            },
        )
    }

    fun sample(
        at: Double,
        motion: MotionStyle = MotionStyle(),
        style: CursorStyle = CursorStyle(),
    ): PointerSample? = evaluation(at, motion, style).cursor

    fun eventsBetween(start: Double, end: Double): List<PointerEventRecord> {
        if (!start.isFinite() || !end.isFinite() || end < start) return emptyList()
        val lower = firstIndexAtOrAfter(start)
        val upper = firstIndexAfter(end)
        return events.subList(lower, upper)
    }

    private fun ensureSpring(motion: MotionStyle): List<SpringSample> {
        val key = Triple(motion.cursorSpringMass, motion.cursorSpringStiffness, motion.cursorSpringDamping)
        if (springKey == key) return springSamples ?: emptyList()
        val samples = makeSpringSamples(motion)
        springKey = key
        springSamples = samples
        return samples
    }

    private fun springLocation(at: Double, motion: MotionStyle): NormalizedPoint? {
        val samples = ensureSpring(motion)
        val first = samples.firstOrNull() ?: return null
        if (at <= first.time) return first.location
        val last = samples.last()
        if (at >= last.time) return last.location
        var lower = 0
        var upper = samples.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (samples[middle].time <= at) lower = middle + 1 else upper = middle
        }
        val upperIndex = min(lower, samples.size - 1)
        val lowerIndex = max(upperIndex - 1, 0)
        val a = samples[lowerIndex]
        val b = samples[upperIndex]
        val duration = max(b.time - a.time, 1e-9)
        val progress = ((at - a.time) / duration).coerceIn(0.0, 1.0)
        return NormalizedPoint(
            a.location.x + (b.location.x - a.location.x) * progress,
            a.location.y + (b.location.y - a.location.y) * progress,
        )
    }

    private fun makeSpringSamples(motion: MotionStyle): List<SpringSample> {
        val first = events.firstOrNull() ?: return emptyList()
        val last = events.last()
        val interval = 1.0 / 240.0
        val mass = max(motion.cursorSpringMass, 0.01)
        val stiffness = max(motion.cursorSpringStiffness, 0.01)
        val damping = max(motion.cursorSpringDamping, 0.01)
        val settleDuration = max(0.65, 6 * sqrt(mass / stiffness))
        val endTime = max(last.time, first.time) + settleDuration
        val jitterThreshold = 0.00035
        val jitterThresholdSquared = jitterThreshold * jitterThreshold

        var position = first.location
        var velocityX = 0.0
        var velocityY = 0.0
        var stableTarget = first.location
        var eventIndex = 1
        var sampleTime = first.time
        val result = mutableListOf(SpringSample(sampleTime, position))

        while (sampleTime < endTime) {
            val nextTime = min(sampleTime + interval, endTime)
            while (eventIndex < events.size && events[eventIndex].time <= nextTime) eventIndex++
            val lowerIndex = max(eventIndex - 1, 0)
            val target: NormalizedPoint = if (eventIndex < events.size) {
                val lowerEvent = events[lowerIndex]
                val upperEvent = events[eventIndex]
                val duration = max(upperEvent.time - lowerEvent.time, 1e-9)
                if (isContinuousMovement(lowerEvent, upperEvent)) {
                    val progress = ((nextTime - lowerEvent.time) / duration).coerceIn(0.0, 1.0)
                    NormalizedPoint(
                        lowerEvent.location.x + (upperEvent.location.x - lowerEvent.location.x) * progress,
                        lowerEvent.location.y + (upperEvent.location.y - lowerEvent.location.y) * progress,
                    )
                } else {
                    lowerEvent.location
                }
            } else {
                last.location
            }
            val targetDeltaX = target.x - stableTarget.x
            val targetDeltaY = target.y - stableTarget.y
            if (targetDeltaX * targetDeltaX + targetDeltaY * targetDeltaY >= jitterThresholdSquared) {
                stableTarget = target
            }
            val deltaTime = max(nextTime - sampleTime, 0.0)
            val accelerationX = (stiffness * (stableTarget.x - position.x) - damping * velocityX) / mass
            val accelerationY = (stiffness * (stableTarget.y - position.y) - damping * velocityY) / mass
            velocityX += accelerationX * deltaTime
            velocityY += accelerationY * deltaTime
            position = NormalizedPoint(
                (position.x + velocityX * deltaTime).coerceIn(0.0, 1.0),
                (position.y + velocityY * deltaTime).coerceIn(0.0, 1.0),
            )
            sampleTime = nextTime
            result += SpringSample(sampleTime, position)
        }
        return anchorClicks(result)
    }

    private fun anchorClicks(base: List<SpringSample>): List<SpringSample> {
        val clicks = events.filter { it.kind == PointerEventKind.LEFT_CLICK || it.kind == PointerEventKind.RIGHT_CLICK }
        if (base.isEmpty() || clicks.isEmpty()) return base
        val samples = insertClickSamples(base, clicks)
        if (samples === base) return base
        val mutable = samples.toMutableList()
        for ((clickIndex, click) in clicks.withIndex()) {
            val anchorIndex = exactSampleIndex(click.time, mutable) ?: continue
            val previousClickTime = clicks.getOrNull(clickIndex - 1)?.time
            val nextClickTime = clicks.getOrNull(clickIndex + 1)?.time
            val supportStart = max(
                mutable.first().time,
                max(
                    click.time - ClickAnchorLeadDuration,
                    previousClickTime?.let { (it + click.time) * 0.5 } ?: Double.NEGATIVE_INFINITY,
                ),
            )
            val supportEnd = min(
                mutable.last().time,
                min(
                    click.time + ClickAnchorTrailDuration,
                    nextClickTime?.let { (click.time + it) * 0.5 } ?: Double.POSITIVE_INFINITY,
                ),
            )
            val current = mutable[anchorIndex].location
            val correctionX = click.location.x - current.x
            val correctionY = click.location.y - current.y
            val startIndex = firstSampleIndexAtOrAfter(supportStart, mutable)
            val endIndex = firstSampleIndexAfter(supportEnd, mutable)
            for (index in startIndex until endIndex) {
                val sampleTime = mutable[index].time
                val weight = if (sampleTime <= click.time) {
                    val duration = click.time - supportStart
                    val progress = if (duration > 0) (sampleTime - supportStart) / duration else 1.0
                    smootherStep(progress)
                } else {
                    val duration = supportEnd - click.time
                    val progress = if (duration > 0) (sampleTime - click.time) / duration else 1.0
                    1 - smootherStep(progress)
                }
                mutable[index] = SpringSample(
                    sampleTime,
                    NormalizedPoint(
                        (mutable[index].location.x + correctionX * weight).coerceIn(0.0, 1.0),
                        (mutable[index].location.y + correctionY * weight).coerceIn(0.0, 1.0),
                    ),
                )
            }
            mutable[anchorIndex] = SpringSample(click.time, click.location)
        }
        return mutable
    }

    private fun insertClickSamples(base: List<SpringSample>, clicks: List<PointerEventRecord>): List<SpringSample> {
        if (base.size <= 1) return base
        val result = mutableListOf<SpringSample>()
        var baseIndex = 0
        for (click in clicks) {
            if (click.time < base.first().time || click.time > base.last().time) continue
            while (baseIndex < base.size && base[baseIndex].time < click.time - 0.0000001) {
                result += base[baseIndex]
                baseIndex++
            }
            if (baseIndex < base.size && abs(base[baseIndex].time - click.time) <= 0.0000001) {
                if (result.lastOrNull()?.time != base[baseIndex].time) result += base[baseIndex]
                baseIndex++
                continue
            }
            val before = result.lastOrNull() ?: continue
            if (baseIndex >= base.size) continue
            if (abs(before.time - click.time) <= 0.0000001) continue
            val after = base[baseIndex]
            val duration = max(after.time - before.time, 1e-9)
            val progress = ((click.time - before.time) / duration).coerceIn(0.0, 1.0)
            result += SpringSample(
                click.time,
                NormalizedPoint(
                    before.location.x + (after.location.x - before.location.x) * progress,
                    before.location.y + (after.location.y - before.location.y) * progress,
                ),
            )
        }
        result += base.subList(baseIndex, base.size)
        return result
    }

    private fun exactSampleIndex(time: Double, samples: List<SpringSample>): Int? {
        var lower = 0
        var upper = samples.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (samples[middle].time < time) lower = middle + 1 else upper = middle
        }
        return if (lower < samples.size && abs(samples[lower].time - time) <= 0.0000001) lower else null
    }

    private fun firstSampleIndexAtOrAfter(time: Double, samples: List<SpringSample>): Int {
        var lower = 0
        var upper = samples.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (samples[middle].time < time) lower = middle + 1 else upper = middle
        }
        return lower
    }

    private fun firstSampleIndexAfter(time: Double, samples: List<SpringSample>): Int {
        var lower = 0
        var upper = samples.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (samples[middle].time <= time) lower = middle + 1 else upper = middle
        }
        return lower
    }

    private fun cursorRotationRadians(
        at: Double,
        currentLocation: NormalizedPoint,
        motion: MotionStyle,
        strength: Double,
    ): Double {
        val boundedStrength = strength.coerceIn(2.0, 4.0)
        val sampleWindow: Double
        val maximumDegrees: Double
        val previousVelocityWeight: Double
        val responseSpan: Double
        when (motion.cursor) {
            CursorMotionStyle.SMOOTH -> {
                sampleWindow = 0.08; maximumDegrees = 9.0; previousVelocityWeight = 0.38; responseSpan = 0.18
            }
            CursorMotionStyle.MEDIUM -> {
                sampleWindow = 0.065; maximumDegrees = 8.0; previousVelocityWeight = 0.24; responseSpan = 0.22
            }
            CursorMotionStyle.RAPID -> {
                sampleWindow = 0.05; maximumDegrees = 7.0; previousVelocityWeight = 0.12; responseSpan = 0.28
            }
            CursorMotionStyle.NONE -> return 0.0
        }
        val previous = resolvedLocation(at - sampleWindow, motion) ?: return 0.0
        val older = resolvedLocation(at - sampleWindow * 2, motion) ?: return 0.0
        val recentVelocityX = (currentLocation.x - previous.x) / sampleWindow
        val recentVelocityY = (currentLocation.y - previous.y) / sampleWindow
        val previousVelocityX = (previous.x - older.x) / sampleWindow
        val previousVelocityY = (previous.y - older.y) / sampleWindow
        val velocityX = recentVelocityX * (1 - previousVelocityWeight) + previousVelocityX * previousVelocityWeight
        val velocityY = recentVelocityY * (1 - previousVelocityWeight) + previousVelocityY * previousVelocityWeight
        val speed = hypot(velocityX, velocityY)
        val responseGain = max(boundedStrength, 0.25)
        val stationaryThreshold = 0.006 / responseGain
        if (!speed.isFinite() || speed <= stationaryThreshold) return 0.0
        val inverseSpeed = 1 / max(speed, 0.000001)
        val trailingTorque = (((velocityX - velocityY) * inverseSpeed) / sqrt(2.0)).coerceIn(-1.0, 1.0)
        val response = ((speed - stationaryThreshold) / (responseSpan / responseGain)).coerceIn(0.0, 1.0)
        val smoothResponse = response * response * (3 - 2 * response)
        val lowerIndex = max(firstIndexAfter(at) - 1, 0)
        val elapsed = max(at - events[lowerIndex].time, 0.0)
        val idleDecay = (1 - elapsed / 0.18).coerceIn(0.0, 1.0)
        val amplitudeScale = 0.35 + 0.65 * boundedStrength
        val degrees = -maximumDegrees * amplitudeScale * smoothResponse * trailingTorque * idleDecay
        return degrees * kotlin.math.PI / 180.0
    }

    private fun resolvedLocation(at: Double, motion: MotionStyle): NormalizedPoint? {
        val first = events.firstOrNull() ?: return null
        if (at < first.time) return null
        val upperIndex = firstIndexAfter(at)
        val lowerIndex = if (upperIndex > 0) upperIndex - 1 else 0
        val lower = events[lowerIndex]
        if (motion.cursor == CursorMotionStyle.SMOOTH) return springLocation(at, motion) ?: lower.location
        return if (upperIndex < events.size && events[upperIndex].time > lower.time) {
            interpolatedLocation(lowerIndex, upperIndex, at, motion)
        } else {
            lower.location
        }
    }

    private fun interpolatedLocation(
        lowerIndex: Int,
        upperIndex: Int,
        time: Double,
        motion: MotionStyle,
    ): NormalizedPoint {
        val lower = events[lowerIndex]
        val upper = events[upperIndex]
        val duration = upper.time - lower.time
        if (duration <= 0) return lower.location
        if (!isContinuousMovement(lower, upper)) return lower.location
        val progress = ((time - lower.time) / duration).coerceIn(0.0, 1.0)
        if (motion.cursor == CursorMotionStyle.NONE) {
            return NormalizedPoint(
                lower.location.x + (upper.location.x - lower.location.x) * progress,
                lower.location.y + (upper.location.y - lower.location.y) * progress,
            )
        }
        val tangentScale = when (motion.cursor) {
            CursorMotionStyle.SMOOTH -> {
                val mass = max(motion.cursorSpringMass, 0.01)
                val stiffness = max(motion.cursorSpringStiffness, 0.01)
                val damping = max(motion.cursorSpringDamping, 0.01)
                val naturalFrequency = sqrt(stiffness / mass)
                val decayRate = damping / (2 * mass)
                (0.25 + 1.1 * (naturalFrequency / (naturalFrequency + decayRate))).coerceIn(0.25, 1.2)
            }
            CursorMotionStyle.MEDIUM -> 0.78
            CursorMotionStyle.RAPID, CursorMotionStyle.NONE -> 1.0
        }
        val lowerTangent = tangent(lowerIndex)
        val upperTangent = tangent(upperIndex)
        val t2 = progress * progress
        val t3 = t2 * progress
        val h00 = 2 * t3 - 3 * t2 + 1
        val h10 = t3 - 2 * t2 + progress
        val h01 = -2 * t3 + 3 * t2
        val h11 = t3 - t2
        return NormalizedPoint(
            (h00 * lower.location.x + h10 * duration * lowerTangent.x * tangentScale +
                h01 * upper.location.x + h11 * duration * upperTangent.x * tangentScale).coerceIn(0.0, 1.0),
            (h00 * lower.location.y + h10 * duration * lowerTangent.y * tangentScale +
                h01 * upper.location.y + h11 * duration * upperTangent.y * tangentScale).coerceIn(0.0, 1.0),
        )
    }

    private fun tangent(index: Int): NormalizedPoint {
        val current = events[index]
        var previous = index - 1
        while (previous >= 0 && events[previous].time >= current.time) previous--
        var next = index + 1
        while (next < events.size && events[next].time <= current.time) next++
        if (previous >= 0 && next < events.size) {
            return monotoneTangent(events[previous], current, events[next])
        }
        if (next < events.size) {
            val duration = events[next].time - current.time
            if (duration <= 0) return NormalizedPoint(0.0, 0.0)
            return NormalizedPoint(
                (events[next].location.x - current.location.x) / duration,
                (events[next].location.y - current.location.y) / duration,
            )
        }
        if (previous >= 0) {
            val duration = current.time - events[previous].time
            if (duration <= 0) return NormalizedPoint(0.0, 0.0)
            return NormalizedPoint(
                (current.location.x - events[previous].location.x) / duration,
                (current.location.y - events[previous].location.y) / duration,
            )
        }
        return NormalizedPoint(0.0, 0.0)
    }

    private fun monotoneTangent(
        previous: PointerEventRecord,
        current: PointerEventRecord,
        next: PointerEventRecord,
    ): NormalizedPoint {
        val previousDuration = current.time - previous.time
        val nextDuration = next.time - current.time
        if (previousDuration <= 0 || nextDuration <= 0) return NormalizedPoint(0.0, 0.0)
        fun axis(previousValue: Double, value: Double, nextValue: Double): Double {
            val previousSlope = (value - previousValue) / previousDuration
            val nextSlope = (nextValue - value) / nextDuration
            if (!previousSlope.isFinite() || !nextSlope.isFinite() || previousSlope * nextSlope <= 0) return 0.0
            val firstWeight = 2 * nextDuration + previousDuration
            val secondWeight = nextDuration + 2 * previousDuration
            val denominator = firstWeight / previousSlope + secondWeight / nextSlope
            if (!denominator.isFinite() || abs(denominator) < 1e-9) return 0.0
            return (firstWeight + secondWeight) / denominator
        }
        return NormalizedPoint(
            axis(previous.location.x, current.location.x, next.location.x),
            axis(previous.location.y, current.location.y, next.location.y),
        )
    }

    private fun mostRecentClick(from: Double, through: Double): PointerEventRecord? {
        var index = firstIndexAtOrAfter(from)
        var latest: PointerEventRecord? = null
        while (index < events.size && events[index].time <= through) {
            val event = events[index]
            if (event.kind == PointerEventKind.LEFT_CLICK || event.kind == PointerEventKind.RIGHT_CLICK) latest = event
            index++
        }
        return latest
    }

    private fun firstIndexAfter(time: Double): Int {
        var lower = 0
        var upper = events.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (events[middle].time <= time) lower = middle + 1 else upper = middle
        }
        return lower
    }

    private fun firstIndexAtOrAfter(time: Double): Int {
        var lower = 0
        var upper = events.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (events[middle].time < time) lower = middle + 1 else upper = middle
        }
        return lower
    }

    private fun isContinuousMovement(lower: PointerEventRecord, upper: PointerEventRecord): Boolean {
        val duration = upper.time - lower.time
        if (duration <= 0) return false
        if (duration <= MaximumContinuousInputGap) return true
        if (duration > MaximumSlowMoveGap) return false
        val deltaX = upper.location.x - lower.location.x
        val deltaY = upper.location.y - lower.location.y
        return deltaX * deltaX + deltaY * deltaY <= MaximumSlowMoveDistance * MaximumSlowMoveDistance
    }

    private fun smootherStep(value: Double): Double {
        val t = value.coerceIn(0.0, 1.0)
        return t * t * t * (t * (t * 6 - 15) + 10)
    }

    companion object {
        private const val MaximumContinuousInputGap = 0.12
        private const val MaximumSlowMoveGap = 0.50
        private const val MaximumSlowMoveDistance = 0.03
        private const val ClickAnchorLeadDuration = 0.18
        private const val ClickAnchorTrailDuration = 0.18
    }
}

/** A small internal marker kept for source compatibility with early ports. */
data class ProjectPointerTrackSegment(
    val id: String,
    val outputRange: MediaTimeRange,
    val track: PointerTrack,
    val startsAfterSourceDiscontinuity: Boolean = false,
) {
    val firstClickTime: Double? = track.events.firstOrNull {
        it.time >= outputRange.start && (it.kind == PointerEventKind.LEFT_CLICK || it.kind == PointerEventKind.RIGHT_CLICK)
    }?.time
}

/**
 * Pointer evaluator in the final output-time domain.
 */
class ProjectPointerTrack(
    timelineMap: TimelineMap,
    sourceEvents: List<PointerEventRecord>,
) {
    val segments: List<ProjectPointerTrackSegment>
    private val cursorActivityTimes: List<Double>

    init {
        val discontinuities = mutableSetOf<String>()
        timelineMap.segments.forEachIndexed { index, segment ->
            val previousEnd = if (index > 0) timelineMap.segments[index - 1].sourceEnd else 0.0
            if (abs(segment.sourceStart - previousEnd) > EndpointEpsilon) discontinuities += segment.id
        }
        segments = timelineMap.mapPointerEventSegments(sourceEvents).map {
            ProjectPointerTrackSegment(
                id = it.id,
                outputRange = it.outputRange,
                track = PointerTrack(it.events),
                startsAfterSourceDiscontinuity = discontinuities.contains(it.id),
            )
        }
        val activity = mutableListOf<Double>()
        var previous: NormalizedPoint? = null
        var previousEnd: Double? = null
        for (segment in segments) {
            if (previousEnd != null && segment.outputRange.start > previousEnd + EndpointEpsilon) previous = null
            for (event in segment.track.events) {
                if (event.time < segment.outputRange.start) continue
                val moved = previous?.let {
                    abs(it.x - event.location.x) > 0.000001 || abs(it.y - event.location.y) > 0.000001
                } ?: true
                if (moved || event.kind != PointerEventKind.MOVE) activity += event.time
                previous = event.location
            }
            previousEnd = segment.outputRange.end
        }
        cursorActivityTimes = activity
    }

    fun prewarmCursorSpring(motion: MotionStyle) {
        if (motion.cursor != CursorMotionStyle.SMOOTH) return
        segments.forEach { it.track.prewarmCursorSpring(motion) }
    }

    fun evaluation(
        at: Double,
        motion: MotionStyle = MotionStyle(),
        style: CursorStyle = CursorStyle(),
        automaticEntry: ZoomAnimationClip? = null,
    ): PointerTrackEvaluation {
        val segmentIndex = segmentIndex(at) ?: return PointerTrackEvaluation(null, null)
        val segment = segments[segmentIndex]
        var segmentStyle = style.copy(hideWhenIdle = false)
        var current = segment.track.evaluation(at, motion, segmentStyle)
        if (style.hideWhenIdle) {
            var lo = 0
            var hi = cursorActivityTimes.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (cursorActivityTimes[mid] <= at) lo = mid + 1 else hi = mid
            }
            if (lo > 0 && at - cursorActivityTimes[lo - 1] > style.idleDelay) {
                current = current.copy(cursor = null)
            }
        }
        if (automaticEntry != null && automaticEntryStartsAfterCut(automaticEntry) &&
            abs(automaticEntry.startTime - segment.outputRange.start) <= EndpointEpsilon
        ) {
            return current
        }
        if (segmentIndex > 0) {
            val bridged = cutBridgePosition(at, segmentIndex, motion, style)
            if (bridged != null) {
                return PointerTrackEvaluation(bridged, current.cursor?.copy(location = bridged))
            }
        }
        return current
    }

    fun sample(
        at: Double,
        motion: MotionStyle = MotionStyle(),
        style: CursorStyle = CursorStyle(),
    ): PointerSample? = evaluation(at, motion, style).cursor

    fun automaticEntryStartsAfterCut(clip: ZoomAnimationClip): Boolean {
        if (clip.origin != ZoomKeyframeOrigin.AUTOMATIC) return false
        val index = segmentIndex(clip.startTime) ?: return false
        val segment = segments[index]
        return segment.startsAfterSourceDiscontinuity &&
            abs(segment.outputRange.start - clip.startTime) <= EndpointEpsilon
    }

    // Per-frame hot path: the 120 Hz spring integration over the whole clip is
    // deterministic in (clip, entry focus, spring params), so cache the last two
    // results (scene evaluation asks for the active clip and its inherited
    // predecessor every frame). Without this, a 30 s clip at 60 fps re-integrates
    // ~430 k spring steps per second.
    private data class CameraFocusCacheKey(
        val clip: ZoomAnimationClip,
        val entryFocus: NormalizedPoint,
        val mass: Double,
        val stiffness: Double,
        val damping: Double,
    )
    private var cameraFocusCacheA: Pair<CameraFocusCacheKey, List<Pair<Double, NormalizedPoint>>>? = null
    private var cameraFocusCacheB: Pair<CameraFocusCacheKey, List<Pair<Double, NormalizedPoint>>>? = null

    fun automaticCameraFocus(
        at: Double,
        clip: ZoomAnimationClip,
        motion: MotionStyle = MotionStyle(),
    ): NormalizedPoint {
        val baseFocus = clip.focus.constrained(ZoomViewportTransform.FocusEdgeInset)
        if (clip.origin != ZoomKeyframeOrigin.AUTOMATIC || !at.isFinite() || clip.endTime <= clip.startTime) return baseFocus
        val entryFocus = automaticEntryFocus(clip, baseFocus)
        val key = CameraFocusCacheKey(
            clip, entryFocus,
            max(motion.screenSpringMass, 0.01),
            max(motion.screenSpringStiffness, 0.01),
            max(motion.screenSpringDamping, 0.01),
        )
        val samples = when {
            cameraFocusCacheA?.first == key -> cameraFocusCacheA!!.second
            cameraFocusCacheB?.first == key -> {
                val hit = cameraFocusCacheB!!
                cameraFocusCacheB = cameraFocusCacheA
                cameraFocusCacheA = hit
                hit.second
            }
            else -> {
                val computed = makeAutomaticCameraFocusSamples(clip, entryFocus, motion)
                cameraFocusCacheB = cameraFocusCacheA
                cameraFocusCacheA = key to computed
                computed
            }
        }
        return interpolatedFocus(at.coerceIn(clip.startTime, clip.endTime), samples, baseFocus)
    }

    private fun automaticEntryFocus(clip: ZoomAnimationClip, fallback: NormalizedPoint): NormalizedPoint {
        if ((clip.enterProgressOffset > EndpointEpsilon && !automaticEntryStartsAfterCut(clip))) return fallback
        val index = segmentIndex(clip.startTime) ?: return fallback
        val segment = segments[index]
        val entryEnd = min(clip.startTime + min(max(clip.enterDuration, 0.0), clip.duration), segment.outputRange.end)
        val entryClick = segment.track.eventsBetween(clip.startTime, entryEnd).firstOrNull {
            it.time < segment.outputRange.end &&
                (it.kind == PointerEventKind.LEFT_CLICK || it.kind == PointerEventKind.RIGHT_CLICK)
        }
        val focus = entryClick?.location ?: interpolatedRawPosition(clip.startTime, segment.track.events)
        return focus?.constrained(ZoomViewportTransform.FocusEdgeInset) ?: fallback
    }

    private fun makeAutomaticCameraFocusSamples(
        clip: ZoomAnimationClip,
        baseFocus: NormalizedPoint,
        motion: MotionStyle,
    ): List<Pair<Double, NormalizedPoint>> {
        val interval = 1.0 / 120.0
        val visibleHalfSpan = 1 / (2 * max(clip.scale, 1.0))
        val restAreaInsetRatio = 0.22
        val restAreaHalfSpan = max(visibleHalfSpan - (2 * visibleHalfSpan) * restAreaInsetRatio, 0.01)
        val followStart = min(clip.startTime + min(max(clip.enterDuration, 0.0), clip.duration), clip.endTime)
        val mass = max(motion.screenSpringMass, 0.01)
        val stiffness = max(motion.screenSpringStiffness, 0.01)
        val damping = max(motion.screenSpringDamping, 0.01)
        var position = baseFocus
        var target = baseFocus
        var velocityX = 0.0
        var velocityY = 0.0
        val clipEvents = segments.flatMap { it.track.eventsBetween(clip.startTime, clip.endTime) }
        var sampleTime = clip.startTime
        val result = mutableListOf(sampleTime to position)
        while (sampleTime < clip.endTime) {
            val nextTime = min(sampleTime + interval, clip.endTime)
            if (nextTime >= followStart) {
                val cursor = interpolatedRawPosition(nextTime, clipEvents)
                if (cursor != null) {
                    if (cursor.x < target.x - restAreaHalfSpan || cursor.x > target.x + restAreaHalfSpan) target = target.copy(x = cursor.x)
                    if (cursor.y < target.y - restAreaHalfSpan || cursor.y > target.y + restAreaHalfSpan) target = target.copy(y = cursor.y)
                    target = target.constrained(ZoomViewportTransform.FocusEdgeInset)
                }
            }
            val deltaTime = max(nextTime - sampleTime, 0.0)
            val accelerationX = (stiffness * (target.x - position.x) - damping * velocityX) / mass
            val accelerationY = (stiffness * (target.y - position.y) - damping * velocityY) / mass
            velocityX += accelerationX * deltaTime
            velocityY += accelerationY * deltaTime
            position = NormalizedPoint(position.x + velocityX * deltaTime, position.y + velocityY * deltaTime)
                .constrained(ZoomViewportTransform.FocusEdgeInset)
            sampleTime = nextTime
            result += sampleTime to position
        }
        return result
    }

    private fun interpolatedFocus(at: Double, samples: List<Pair<Double, NormalizedPoint>>, fallback: NormalizedPoint): NormalizedPoint {
        val first = samples.firstOrNull() ?: return fallback
        if (at <= first.first) return first.second
        val last = samples.last()
        if (at >= last.first) return last.second
        var lower = 0
        var upper = samples.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (samples[middle].first <= at) lower = middle + 1 else upper = middle
        }
        val upperIndex = min(lower, samples.size - 1)
        val lowerIndex = max(upperIndex - 1, 0)
        val a = samples[lowerIndex]
        val b = samples[upperIndex]
        val duration = max(b.first - a.first, 1e-9)
        val progress = ((at - a.first) / duration).coerceIn(0.0, 1.0)
        return NormalizedPoint(
            a.second.x + (b.second.x - a.second.x) * progress,
            a.second.y + (b.second.y - a.second.y) * progress,
        )
    }

    private fun cutBridgePosition(
        at: Double,
        segmentIndex: Int,
        motion: MotionStyle,
        style: CursorStyle,
    ): NormalizedPoint? {
        val segment = segments[segmentIndex]
        val start = segment.outputRange.start
        if (at < start) return null
        val availableUntilClick = segment.firstClickTime?.let { max(it - start, 0.0) }
        val duration = min(
            min(CutBridgeDuration, availableUntilClick ?: CutBridgeDuration),
            segment.outputRange.duration,
        )
        if (duration <= EndpointEpsilon || at >= start + duration) return null
        val previous = segments[segmentIndex - 1]
        val previousTime = max(previous.outputRange.start, previous.outputRange.end - EndpointEpsilon)
        val from = previous.track.evaluation(previousTime, motion, style).position ?: return null
        val to = segment.track.evaluation(start + duration, motion, style).position ?: return null
        val linear = ((at - start) / duration).coerceIn(0.0, 1.0)
        val eased = linear * linear * linear * (linear * (linear * 6 - 15) + 10)
        return NormalizedPoint(from.x + (to.x - from.x) * eased, from.y + (to.y - from.y) * eased)
    }

    private fun segmentIndex(at: Double): Int? {
        if (!at.isFinite() || at < 0) return null
        var lower = 0
        var upper = segments.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (segments[middle].outputRange.end <= at) lower = middle + 1 else upper = middle
        }
        val candidate = segments.getOrNull(lower) ?: return null
        return if (at >= candidate.outputRange.start) lower else null
    }

    companion object {
        private const val CutBridgeDuration = 0.28
        private const val EndpointEpsilon = 1.0 / 10000.0

        fun interpolatedRawPosition(time: Double, events: List<PointerEventRecord>): NormalizedPoint? {
            val first = events.firstOrNull() ?: return null
            if (time <= first.time) return first.location
            val last = events.last()
            if (time >= last.time) return last.location
            var lower = 0
            var upper = events.size
            while (lower < upper) {
                val middle = (lower + upper) / 2
                if (events[middle].time <= time) lower = middle + 1 else upper = middle
            }
            val upperIndex = min(lower, events.size - 1)
            val lowerIndex = max(upperIndex - 1, 0)
            val a = events[lowerIndex]
            val b = events[upperIndex]
            val duration = max(b.time - a.time, 1e-9)
            val progress = ((time - a.time) / duration).coerceIn(0.0, 1.0)
            return NormalizedPoint(
                a.location.x + (b.location.x - a.location.x) * progress,
                a.location.y + (b.location.y - a.location.y) * progress,
            )
        }
    }
}
