package dev.dogsc.core.model

import kotlin.math.max
import kotlin.math.min

data class MediaTimelinePlacement(
    val timelineStart: Double,
    val sourceStart: Double,
    val playableDuration: Double,
    val sourceTimeScale: Double,
) {
    val sourceEnd: Double get() = sourceStart + playableDuration * sourceTimeScale
    val timelineEnd: Double get() = timelineStart + playableDuration

    fun sourceTimeAt(timelineTime: Double): Double? {
        if (!timelineTime.isFinite() || timelineTime < timelineStart || timelineTime >= timelineEnd) return null
        return sourceStart + (timelineTime - timelineStart) * sourceTimeScale
    }

    fun timelineTimeForSource(sourceTime: Double): Double? {
        if (!sourceTime.isFinite() || sourceTime < sourceStart || sourceTime >= sourceEnd) return null
        return timelineStart + (sourceTime - sourceStart) / sourceTimeScale
    }

    companion object {
        fun from(
            reference: ProjectMediaReference,
            sourceAvailableStart: Double = 0.0,
            sourceAvailableDuration: Double,
            timelineDuration: Double,
        ): MediaTimelinePlacement {
            val requestedSourceStart = max(reference.sourceStartTime, 0.0)
            val availableStart = max(sourceAvailableStart, 0.0)
            val availableDuration = max(sourceAvailableDuration, 0.0)
            val availableEnd = availableStart + availableDuration
            val effectiveSourceStart = min(max(requestedSourceStart, availableStart), availableEnd)
            val requestedSourceEnd = reference.sourceEndTime ?: availableEnd
            val effectiveSourceEnd = min(max(requestedSourceEnd, effectiveSourceStart), availableEnd)
            val unavailableLead = max(effectiveSourceStart - requestedSourceStart, 0.0)
            val effectiveTimelineStart = max(reference.startOffset, 0.0) + unavailableLead
            val safeTimelineDuration = max(timelineDuration, 0.0)
            val requestedScale = reference.sourceTimeScale ?: 1.0
            val safeScale = if (requestedScale.isFinite() && requestedScale > 0) requestedScale else 1.0
            return MediaTimelinePlacement(
                timelineStart = min(effectiveTimelineStart, safeTimelineDuration),
                sourceStart = effectiveSourceStart,
                playableDuration = min(
                    max(effectiveSourceEnd - effectiveSourceStart, 0.0) / safeScale,
                    max(safeTimelineDuration - min(effectiveTimelineStart, safeTimelineDuration), 0.0),
                ),
                sourceTimeScale = safeScale,
            )
        }
    }
}

data class TimelineMediaSlice(
    val id: String,
    val segmentID: String,
    val outputStart: Double,
    val sourceStart: Double,
    val duration: Double,
    val sourceTimeScale: Double = 1.0,
) {
    val outputEnd: Double get() = outputStart + duration
    val sourceEnd: Double get() = sourceStart + duration * sourceTimeScale

    fun sourceTimeAtOutput(time: Double): Double? {
        if (!time.isFinite() || time < outputStart || time >= outputEnd) return null
        return sourceStart + max(time - outputStart, 0.0) * sourceTimeScale
    }
}

/** Final-time media placement generated from the one authoritative TimelineMap. */
class TimelineMediaPlan(
    val outputDuration: Double,
    val slices: List<TimelineMediaSlice>,
) {
    val playableDuration: Double get() = slices.sumOf { it.duration }

    fun sliceAtOutput(time: Double): TimelineMediaSlice? {
        if (!time.isFinite() || time < 0) return null
        var lower = 0
        var upper = slices.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (slices[middle].outputEnd <= time) lower = middle + 1 else upper = middle
        }
        val candidate = slices.getOrNull(lower) ?: return null
        return candidate.takeIf { time >= it.outputStart }
    }

    fun sourceTimeAtOutput(time: Double): Double? = sliceAtOutput(time)?.sourceTimeAtOutput(time)

    fun containsOutput(time: Double): Boolean = sourceTimeAtOutput(time) != null

    companion object {
        fun primary(timelineMap: TimelineMap): TimelineMediaPlan = TimelineMediaPlan(
            outputDuration = timelineMap.outputDuration,
            slices = timelineMap.segments.map {
                TimelineMediaSlice(
                    id = it.id,
                    segmentID = it.id,
                    outputStart = it.outputStart,
                    sourceStart = it.sourceStart,
                    duration = it.outputDuration,
                    sourceTimeScale = it.playbackRate,
                )
            },
        )

        fun auxiliary(
            timelineMap: TimelineMap,
            placement: MediaTimelinePlacement,
            syncAnchors: List<MediaSyncAnchor> = emptyList(),
            sourceAvailableStart: Double = 0.0,
            sourceAvailableEnd: Double = Double.POSITIVE_INFINITY,
        ): TimelineMediaPlan {
            if (syncAnchors.isEmpty()) {
                val slices = timelineMap.segments.mapNotNull { segment ->
                    val retainedStart = max(segment.sourceStart, placement.timelineStart)
                    val retainedEnd = min(segment.sourceEnd, placement.timelineEnd)
                    if (retainedEnd - retainedStart <= 0.000001) return@mapNotNull null
                    val startsAtBoundary = kotlin.math.abs(retainedStart - segment.sourceStart) <= 1e-9
                    val endsAtBoundary = kotlin.math.abs(retainedEnd - segment.sourceEnd) <= 1e-9
                    val outputStart = if (startsAtBoundary) segment.outputStart
                    else segment.outputStart + (retainedStart - segment.sourceStart) / segment.playbackRate
                    val duration = if (startsAtBoundary && endsAtBoundary) segment.outputDuration
                    else {
                        val outputEnd = if (endsAtBoundary) segment.outputEnd
                        else segment.outputStart + (retainedEnd - segment.sourceStart) / segment.playbackRate
                        outputEnd - outputStart
                    }
                    if (duration <= 0) return@mapNotNull null
                    TimelineMediaSlice(
                        id = segment.id,
                        segmentID = segment.id,
                        outputStart = outputStart,
                        sourceStart = placement.sourceTimeAt(retainedStart) ?: placement.sourceStart,
                        duration = duration,
                        sourceTimeScale = placement.sourceTimeScale * segment.playbackRate,
                    )
                }
                return TimelineMediaPlan(timelineMap.outputDuration, slices)
            }
            val curve = MediaSyncAnchorCurve(syncAnchors)
            val slices = timelineMap.segments.flatMap { segment ->
                val retainedStart = max(segment.sourceStart, placement.timelineStart)
                val retainedEnd = min(segment.sourceEnd, placement.timelineEnd)
                if (retainedEnd - retainedStart <= 0.000001) return@flatMap emptyList<TimelineMediaSlice>()
                val boundaries = buildList<Double> {
                    add(retainedStart)
                    addAll(syncAnchors.map { it.sourceTime }.filter {
                        it > retainedStart + 0.000001 && it < retainedEnd - 0.000001
                    })
                    add(retainedEnd)
                }.sorted()
                (0 until boundaries.size - 1).mapNotNull { index ->
                    val primaryStart = boundaries[index]
                    val primaryEnd = boundaries[index + 1]
                    val startsAtBoundary = kotlin.math.abs(primaryStart - segment.sourceStart) <= 1e-9
                    val endsAtBoundary = kotlin.math.abs(primaryEnd - segment.sourceEnd) <= 1e-9
                    val outputStart = if (startsAtBoundary) segment.outputStart
                    else segment.outputStart + (primaryStart - segment.sourceStart) / segment.playbackRate
                    val outputEnd = if (endsAtBoundary) segment.outputEnd
                    else segment.outputStart + (primaryEnd - segment.sourceStart) / segment.playbackRate
                    val duration = if (startsAtBoundary && endsAtBoundary) segment.outputDuration else outputEnd - outputStart
                    if (duration <= 0.000001) return@mapNotNull null
                    val baseStart = placement.sourceStart +
                        (primaryStart - placement.timelineStart) * placement.sourceTimeScale
                    val baseEnd = placement.sourceStart +
                        (primaryEnd - placement.timelineStart) * placement.sourceTimeScale
                    val correctedStart = min(
                        max(baseStart + curve.offsetAtSourceTime(primaryStart), sourceAvailableStart),
                        sourceAvailableEnd,
                    )
                    val correctedEnd = min(
                        max(baseEnd + curve.offsetAtSourceTime(primaryEnd), sourceAvailableStart),
                        sourceAvailableEnd,
                    )
                    val correctedDuration = correctedEnd - correctedStart
                    if (correctedDuration <= 0.000001) return@mapNotNull null
                    TimelineMediaSlice(
                        // One segment can be split at sync anchors; slice ids must stay
                        // unique because audio stretchers are keyed by them.
                        id = "${segment.id}#$index",
                        segmentID = segment.id,
                        outputStart = outputStart,
                        sourceStart = correctedStart,
                        duration = duration,
                        sourceTimeScale = correctedDuration / duration,
                    )
                }
            }
            return TimelineMediaPlan(timelineMap.outputDuration, slices)
        }
    }
}

class MediaSyncAnchorCurve(anchors: List<MediaSyncAnchor>) {
    private val anchors: List<MediaSyncAnchor> = anchors.sortedWith(compareBy({ it.sourceTime }, { it.id }))

    fun offsetAtSourceTime(sourceTime: Double): Double {
        if (!sourceTime.isFinite() || this.anchors.isEmpty()) return 0.0
        val safeTime = max(sourceTime, 0.0)
        val first = this.anchors.first()
        if (first.sourceTime > 0.000001 && safeTime <= first.sourceTime) {
            return first.offset * (safeTime / first.sourceTime).coerceIn(0.0, 1.0)
        }
        if (safeTime <= first.sourceTime) return first.offset
        var lower = 1
        var upper = this.anchors.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (this.anchors[middle].sourceTime < safeTime) lower = middle + 1 else upper = middle
        }
        if (lower >= this.anchors.size) return this.anchors.last().offset
        val left = this.anchors[lower - 1]
        val right = this.anchors[lower]
        val span = right.sourceTime - left.sourceTime
        if (span <= 0.000001) return right.offset
        val progress = ((safeTime - left.sourceTime) / span).coerceIn(0.0, 1.0)
        return left.offset + (right.offset - left.offset) * progress
    }
}
