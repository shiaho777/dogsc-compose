package dev.dogsc.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class MediaTimeRange(val start: Double, val duration: Double) {
    init {
        require(start.isFinite() && duration.isFinite() && duration > 0)
    }
    val end: Double get() = start + duration
    fun contains(time: Double): Boolean = time >= start && time < end
}

data class ResolvedRecordingSegment(
    val id: String,
    val sourceStart: Double,
    val sourceDuration: Double,
    val playbackRate: Double,
    val outputStart: Double,
) {
    val sourceEnd: Double get() = sourceStart + sourceDuration
    val outputDuration: Double get() = sourceDuration / playbackRate
    val outputEnd: Double get() = outputStart + outputDuration
    val sourceRange: MediaTimeRange get() = MediaTimeRange(sourceStart, sourceDuration)
    val outputRange: MediaTimeRange get() = MediaTimeRange(outputStart, outputDuration)
}

data class TimelineMappedPointerSegment(
    val id: String,
    val outputRange: MediaTimeRange,
    val events: List<PointerEventRecord>,
)

/**
 * Pure mapping between the final ripple timeline and the original recording
 * clock. Array order is output order; source ranges may be rearranged but
 * remain non-overlapping so every original source instant has one owner.
 */
class TimelineMap(
    sourceSequence: SourceSequence,
    val fullSourceDuration: Double,
) {
    val segments: List<ResolvedRecordingSegment>

    init {
        require(fullSourceDuration.isFinite() && fullSourceDuration > 0) { "主录屏时长必须是正的有限数。" }
        val authored = when (sourceSequence) {
            SourceSequence.FullRecording -> listOf(
                RecordingSegment(
                    id = FullRecordingSegmentId,
                    sourceStart = 0.0,
                    sourceDuration = fullSourceDuration,
                )
            )
            is SourceSequence.Edited -> {
                require(sourceSequence.segments.isNotEmpty()) { "剪辑后至少需要保留一个主录屏片段。" }
                sourceSequence.segments
            }
        }
        val identifiers = mutableSetOf<String>()
        var outputStart = 0.0
        val resolved = mutableListOf<ResolvedRecordingSegment>()
        val epsilon = 1.0 / 120000.0
        for (segment in authored) {
            require(identifiers.add(segment.id)) { "时间线中存在重复的主片段。" }
            require(segment.sourceStart >= 0 && segment.sourceDuration > 0 && segment.playbackRate > 0)
            require(segment.sourceStart < fullSourceDuration && segment.sourceEnd <= fullSourceDuration + epsilon) {
                "主片段超出原始录屏可用时长。"
            }
            val resolvedDuration = min(segment.sourceDuration, fullSourceDuration - segment.sourceStart)
            require(resolvedDuration > 0)
            resolved += ResolvedRecordingSegment(
                id = segment.id,
                sourceStart = segment.sourceStart,
                sourceDuration = resolvedDuration,
                playbackRate = segment.playbackRate,
                outputStart = outputStart,
            )
            outputStart += resolvedDuration / segment.playbackRate
        }
        // Overlap check: sort once by source start, then verify each segment starts
        // after its predecessor ends. O(n log n) instead of the previous O(n²) scan;
        // ties on sourceStart are still rejected because the predecessor's end is
        // strictly greater than the shared start.
        val bySource = authored.sortedWith(compareBy({ it.sourceStart }, { it.id }))
        for (index in 1 until bySource.size) {
            val previous = bySource[index - 1]
            val current = bySource[index]
            if (current.sourceStart < previous.sourceEnd - epsilon) {
                throw IllegalArgumentException("主片段不能重复使用同一段源素材。")
            }
        }
        segments = resolved
    }

    /** Segments ordered by source start; used for binary-searched source lookups. */
    private val bySourceStart: List<ResolvedRecordingSegment> by lazy {
        segments.sortedBy { it.sourceStart }
    }

    val outputDuration: Double get() = segments.lastOrNull()?.outputEnd ?: 0.0

    fun segmentAtOutputTime(time: Double): ResolvedRecordingSegment? {
        if (!time.isFinite() || time < 0 || time >= outputDuration) return null
        var lower = 0
        var upper = segments.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (segments[middle].outputEnd <= time) lower = middle + 1 else upper = middle
        }
        val candidate = segments.getOrNull(lower) ?: return null
        return candidate.takeIf { time >= it.outputStart }
    }

    fun sourceTimeAtOutput(time: Double): Double? {
        val segment = segmentAtOutputTime(time) ?: return null
        return segment.sourceStart + (time - segment.outputStart) * segment.playbackRate
    }

    fun outputTimeForSource(time: Double): Double? {
        if (!time.isFinite()) return null
        val ordered = bySourceStart
        var lower = 0
        var upper = ordered.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (ordered[middle].sourceEnd <= time) lower = middle + 1 else upper = middle
        }
        val segment = ordered.getOrNull(lower)?.takeIf { time >= it.sourceStart } ?: return null
        return segment.outputStart + (time - segment.sourceStart) / segment.playbackRate
    }

    /**
     * Remaps an output-clock interval through a changed source sequence.
     *
     * Effect clips (zoom, motion, mosaic, stickers) live on the output clock, so a
     * ripple delete / trim / reorder / retime must carry them along or they drift
     * onto the wrong content — a redaction mosaic sliding off its sensitive region.
     * The interval is mapped back to source time on [this] map, split at cut
     * boundaries, and each surviving piece is re-projected through [target].
     * Pieces whose source range was removed are dropped; a piece that spans a cut
     * is compressed (the cut content no longer exists). Returns null when nothing
     * survives or the result would be degenerate.
     */
    fun remapOutputInterval(start: Double, end: Double, target: TimelineMap): Pair<Double, Double>? {
        if (!start.isFinite() || !end.isFinite() || end - start < 1e-9) return null
        val clampedStart = start.coerceIn(0.0, outputDuration)
        val clampedEnd = end.coerceIn(clampedStart, outputDuration)
        if (clampedEnd - clampedStart < 1e-9) return null
        var newStart = Double.POSITIVE_INFINITY
        var newEnd = Double.NEGATIVE_INFINITY
        // Walk this map's segments intersecting [clampedStart, clampedEnd).
        for (segment in segments) {
            if (segment.outputEnd <= clampedStart) continue
            if (segment.outputStart >= clampedEnd) break
            val pieceOutputStart = max(clampedStart, segment.outputStart)
            val pieceOutputEnd = min(clampedEnd, segment.outputEnd)
            val sourceA = segment.sourceStart + (pieceOutputStart - segment.outputStart) * segment.playbackRate
            val sourceB = segment.sourceStart + (pieceOutputEnd - segment.outputStart) * segment.playbackRate
            // The source piece may itself cross cuts in the target map; split there.
            var cursor = sourceA
            while (cursor < sourceB - 1e-9) {
                val owner = target.segmentContainingSource(cursor)
                if (owner == null) {
                    // Removed region: jump to the next retained source range.
                    val next = target.nextSegmentAfterSource(cursor) ?: break
                    cursor = next.sourceStart
                    continue
                }
                val pieceSourceEnd = min(sourceB, owner.sourceEnd)
                val mappedA = owner.outputStart + (cursor - owner.sourceStart) / owner.playbackRate
                val mappedB = owner.outputStart + (pieceSourceEnd - owner.sourceStart) / owner.playbackRate
                newStart = min(newStart, mappedA)
                newEnd = max(newEnd, mappedB)
                cursor = pieceSourceEnd
            }
        }
        if (newStart > newEnd || !newStart.isFinite() || !newEnd.isFinite()) return null
        if (newEnd - newStart < 1e-6) return null
        return newStart to newEnd
    }

    /** Segment owning a source instant (half-open [sourceStart, sourceEnd)), binary-searched. */
    private fun segmentContainingSource(time: Double): ResolvedRecordingSegment? {
        val ordered = bySourceStart
        var lower = 0
        var upper = ordered.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (ordered[middle].sourceEnd <= time) lower = middle + 1 else upper = middle
        }
        return ordered.getOrNull(lower)?.takeIf { time >= it.sourceStart }
    }

    /** First segment starting strictly after a removed source instant. */
    private fun nextSegmentAfterSource(time: Double): ResolvedRecordingSegment? {
        val ordered = bySourceStart
        var lower = 0
        var upper = ordered.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (ordered[middle].sourceStart <= time) lower = middle + 1 else upper = middle
        }
        return ordered.getOrNull(lower)
    }

    fun materializedSourceSequence(): SourceSequence = SourceSequence.Edited(
        segments.map {
            RecordingSegment(
                id = it.id,
                sourceStart = it.sourceStart,
                sourceDuration = it.sourceDuration,
                playbackRate = it.playbackRate,
            )
        }
    )

    /**
     * Maps events into output time while retaining cut boundaries. Each group
     * receives the most recent source position as a move seed when possible.
     */
    fun mapPointerEventSegments(sourceEvents: List<PointerEventRecord>): List<TimelineMappedPointerSegment> {
        val ordered = sourceEvents.sortedWith(compareBy({ it.time }, { it.kind.ordinal }))
        return segments.map { segment ->
            val outputRange = segment.outputRange
            val lowerIndex = firstEventIndexAtOrAfter(segment.sourceStart, ordered)
            val upperIndex = firstEventIndexAtOrAfter(segment.sourceEnd, ordered)
            val eventsInRange = ordered.subList(lowerIndex, upperIndex)
            val mapped = mutableListOf<PointerEventRecord>()
            val startsWithExactEvent = eventsInRange.firstOrNull()?.let {
                abs(it.time - segment.sourceStart) < 0.000001
            } ?: false
            if (!startsWithExactEvent && lowerIndex > 0) {
                val seed = ordered[lowerIndex - 1]
                mapped += PointerEventRecord(
                    time = segment.outputStart,
                    location = seed.location,
                    kind = PointerEventKind.MOVE,
                    modifiers = seed.modifiers,
                    cursorAssetID = seed.cursorAssetID,
                )
            }
            mapped += eventsInRange.map { event ->
                PointerEventRecord(
                    time = segment.outputStart + (event.time - segment.sourceStart) / segment.playbackRate,
                    location = event.location,
                    kind = event.kind,
                    modifiers = event.modifiers,
                    cursorAssetID = event.cursorAssetID,
                )
            }
            TimelineMappedPointerSegment(segment.id, outputRange, mapped)
        }
    }

    private fun firstEventIndexAtOrAfter(time: Double, events: List<PointerEventRecord>): Int {
        var lower = 0
        var upper = events.size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (events[middle].time < time) lower = middle + 1 else upper = middle
        }
        return lower
    }

    companion object {
        const val FullRecordingSegmentId: String = "00000000-0000-4000-8000-000000000001"
    }
}
