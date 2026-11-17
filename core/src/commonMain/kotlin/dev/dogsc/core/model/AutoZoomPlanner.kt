package dev.dogsc.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object AutoZoomPlanner {
    data class PointerClick(val time: Double, val location: NormalizedPoint)

    fun makeAnimations(
        pointerEvents: List<PointerEventRecord>,
        zoomScale: Double = 1.6,
        easing: ZoomEasingPreset = ZoomEasingPreset.SPRING,
        transitionDuration: Double = 0.7,
    ): List<ZoomAnimationClip> {
        val clicks = pointerEvents.mapNotNull { event ->
            if (event.kind == PointerEventKind.LEFT_CLICK || event.kind == PointerEventKind.RIGHT_CLICK) {
                PointerClick(event.time, event.location)
            } else {
                null
            }
        }
        val duration = transitionDuration.coerceIn(0.0, 5.0)
        val planned = makeAnimations(
            clicks = clicks,
            zoomScale = zoomScale,
            enterDuration = duration,
            exitDuration = duration,
            easing = easing,
        )
        val extended = extendingAutomaticHoldsThroughPointerActivity(planned, clicks, pointerEvents)
        return mergingConnectedAutomaticEnvelopes(extended)
    }

    fun makeAnimations(
        clicks: List<PointerClick>,
        zoomScale: Double = 1.6,
        enterDuration: Double = 0.7,
        holdDuration: Double = 0.9,
        exitDuration: Double = 0.7,
        clickGroupGap: Double = 1.4,
        safeInset: Double = 0.12,
        easing: ZoomEasingPreset = ZoomEasingPreset.SPRING,
    ): List<ZoomAnimationClip> {
        val ordered = orderedValidClicks(clicks)
        val temporalGroups = mutableListOf<MutableList<PointerClick>>()
        for (click in ordered) {
            val previous = temporalGroups.lastOrNull()?.lastOrNull()
            if (previous != null && click.time - previous.time <= clickGroupGap) {
                temporalGroups.last().add(click)
            } else {
                temporalGroups.add(mutableListOf(click))
            }
        }
        val animations = mutableListOf<ZoomAnimationClip>()
        var earliestNextStart = 0.0
        for (temporalGroup in temporalGroups) {
            val first = temporalGroup.first()
            val last = temporalGroup.last()
            val visibleExtent = 1 / max(zoomScale, 1.0)
            val focusGroups = spatialFocusGroups(
                temporalGroup,
                maximumSpanX = visibleExtent * 0.50,
                maximumSpanY = visibleExtent * 0.70,
            )
            if (focusGroups.isEmpty()) continue
            val starts = mutableListOf(max(first.time - enterDuration * 0.18, max(earliestNextStart, 0.0)))
            for (focusGroup in focusGroups.drop(1)) {
                val minimumStart = starts.last() + 0.16
                starts += max(focusGroup.firstTime - enterDuration, minimumStart)
            }
            for (index in focusGroups.indices) {
                val start = starts[index]
                val isLast = index == focusGroups.lastIndex
                val end = if (isLast) {
                    max(last.time + holdDuration, start + enterDuration)
                } else {
                    starts[index + 1]
                }
                if (end - start <= 0.01) continue
                animations += ZoomAnimationClip(
                    startTime = start,
                    endTime = end,
                    scale = zoomScale,
                    focus = focusGroups[index].focus.constrained(safeInset),
                    origin = ZoomKeyframeOrigin.AUTOMATIC,
                    easing = easing,
                    enterDuration = enterDuration,
                    exitDuration = if (isLast) exitDuration else 0.0,
                )
            }
            earliestNextStart = animations.lastOrNull()?.effectEndTime ?: last.time
        }
        return animations
    }

    fun clipped(animations: List<ZoomAnimationClip>, duration: Double): List<ZoomAnimationClip> {
        if (!duration.isFinite() || duration <= 0) return emptyList()
        return animations.mapNotNull { animation ->
            if (animation.startTime >= duration) return@mapNotNull null
            val endTime = min(animation.endTime, duration)
            val exitDuration = min(animation.exitDuration, max(duration - endTime, 0.0))
            if (endTime - animation.startTime <= 0.01) null
            else animation.copy(endTime = endTime, exitDuration = exitDuration)
        }
    }

    private data class SpatialFocusGroup(
        val firstTime: Double,
        var minimumX: Double,
        var maximumX: Double,
        var minimumY: Double,
        var maximumY: Double,
    ) {
        constructor(click: PointerClick) : this(
            click.time, click.location.x, click.location.x, click.location.y, click.location.y,
        )

        val focus: NormalizedPoint
            get() = NormalizedPoint((minimumX + maximumX) / 2, (minimumY + maximumY) / 2)

        fun canInclude(click: PointerClick, maximumSpanX: Double, maximumSpanY: Double): Boolean =
            max(maximumX, click.location.x) - min(minimumX, click.location.x) <= maximumSpanX &&
                max(maximumY, click.location.y) - min(minimumY, click.location.y) <= maximumSpanY

        fun append(click: PointerClick) {
            minimumX = min(minimumX, click.location.x)
            maximumX = max(maximumX, click.location.x)
            minimumY = min(minimumY, click.location.y)
            maximumY = max(maximumY, click.location.y)
        }
    }

    private fun spatialFocusGroups(
        clicks: List<PointerClick>,
        maximumSpanX: Double,
        maximumSpanY: Double,
    ): List<SpatialFocusGroup> {
        val groups = mutableListOf<SpatialFocusGroup>()
        for (click in clicks) {
            val last = groups.lastOrNull()
            if (last != null && last.canInclude(click, maximumSpanX, maximumSpanY)) {
                last.append(click)
            } else {
                groups += SpatialFocusGroup(click)
            }
        }
        return groups
    }

    private fun extendingAutomaticHoldsThroughPointerActivity(
        planned: List<ZoomAnimationClip>,
        clicks: List<PointerClick>,
        pointerEvents: List<PointerEventRecord>,
        activityIdleGap: Double = 1.4,
        settleHold: Double = 0.55,
    ): List<ZoomAnimationClip> {
        if (planned.isEmpty() || clicks.isEmpty()) return planned
        val orderedEvents = pointerEvents.filter { it.time.isFinite() && it.time >= 0 }.sortedBy { it.time }
        val orderedClicks = orderedValidClicks(clicks)
        val result = planned.toMutableList()
        for (index in result.indices) {
            val clip = result[index]
            if (clip.exitDuration <= 0.001) continue
            val lastClick = orderedClicks.lastOrNull { it.time <= clip.endTime + 0.0001 } ?: continue
            if (lastClick.time < clip.startTime - 0.0001) continue
            val nextStart = result.getOrNull(index + 1)?.startTime ?: Double.POSITIVE_INFINITY
            var previousActivityTime = lastClick.time
            var activityEndTime = lastClick.time
            var eventIndex = orderedEvents.indexOfFirst { it.time > lastClick.time }
            if (eventIndex < 0) eventIndex = orderedEvents.size
            while (eventIndex < orderedEvents.size) {
                val event = orderedEvents[eventIndex]
                if (event.time >= nextStart - 0.0001) break
                if (event.time - previousActivityTime > activityIdleGap) break
                previousActivityTime = event.time
                activityEndTime = event.time
                eventIndex++
            }
            if (activityEndTime <= lastClick.time + 0.0001) continue
            val extendedEnd = min(max(clip.endTime, activityEndTime + settleHold), nextStart)
            if (extendedEnd <= clip.endTime + 0.0001) continue
            result[index] = clip.copy(
                endTime = extendedEnd,
                exitDuration = if (nextStart.isFinite()) min(clip.exitDuration, max(nextStart - extendedEnd, 0.0)) else clip.exitDuration,
            )
        }
        return result
    }

    private fun mergingConnectedAutomaticEnvelopes(animations: List<ZoomAnimationClip>): List<ZoomAnimationClip> {
        val ordered = animations.sortedWith(compareBy({ it.startTime }, { it.id }))
        val result = mutableListOf<ZoomAnimationClip>()
        val tolerance = 0.000001
        for (animation in ordered) {
            val previous = result.lastOrNull()
            if (previous != null &&
                previous.origin == ZoomKeyframeOrigin.AUTOMATIC &&
                animation.origin == ZoomKeyframeOrigin.AUTOMATIC &&
                previous.exitDuration <= ZoomInterpolator.AdjacencyTolerance &&
                abs(previous.endTime - animation.startTime) <= ZoomInterpolator.AdjacencyTolerance &&
                abs(previous.scale - animation.scale) <= tolerance &&
                previous.easing == animation.easing &&
                previous.customCurve == animation.customCurve
            ) {
                result[result.lastIndex] = previous.copy(
                    endTime = max(previous.endTime, animation.endTime),
                    exitDuration = animation.exitDuration,
                )
            } else {
                result += animation
            }
        }
        return result
    }

    private fun orderedValidClicks(clicks: List<PointerClick>): List<PointerClick> {
        val alreadyOrdered = clicks.all { it.time.isFinite() && it.time >= 0 } &&
            clicks.zipWithNext().all { (a, b) -> b.time >= a.time }
        return if (alreadyOrdered) {
            clicks
        } else {
            clicks.filter { it.time.isFinite() && it.time >= 0 }.sortedBy { it.time }
        }
    }
}
