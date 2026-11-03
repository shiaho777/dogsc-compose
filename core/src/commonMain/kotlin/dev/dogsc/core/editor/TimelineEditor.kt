package dev.dogsc.core.editor

import dev.dogsc.core.model.CameraMotionClip
import dev.dogsc.core.model.MosaicClip
import dev.dogsc.core.model.OverlayTiming
import dev.dogsc.core.model.PrimarySegmentAudioOverrides
import dev.dogsc.core.model.ProjectTimeline
import dev.dogsc.core.model.RecorderProject
import dev.dogsc.core.model.RecordingSegment
import dev.dogsc.core.model.ScreenMotionClip
import dev.dogsc.core.model.SourceSequence
import dev.dogsc.core.model.StickerClip
import dev.dogsc.core.model.TimelineMap
import dev.dogsc.core.model.TimelineMath
import dev.dogsc.core.model.TransitionTiming
import dev.dogsc.core.model.ZoomAnimationClip
import dev.dogsc.core.model.ZoomEasingPreset
import dev.dogsc.core.model.ZoomInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Pure editing operations over the persisted project. All functions are total
 * unless they return null (operation would leave the timeline invalid). */
object TimelineEditor {
    fun segments(project: RecorderProject, sourceDuration: Double): List<RecordingSegment> =
        TimelineMath.segments(project.timeline.sourceSequence, sourceDuration)

    fun timelineMap(project: RecorderProject, sourceDuration: Double): TimelineMap =
        TimelineMap(project.timeline.sourceSequence, sourceDuration)

    fun outputDuration(project: RecorderProject, sourceDuration: Double): Double =
        timelineMap(project, sourceDuration).outputDuration

    fun materializeEdited(project: RecorderProject, sourceDuration: Double): RecorderProject {
        if (project.timeline.sourceSequence is SourceSequence.Edited) return project
        val map = timelineMap(project, sourceDuration)
        return project.copy(timeline = project.timeline.copy(sourceSequence = map.materializedSourceSequence()))
    }

    /** Splits the primary recording at an output time. Returns null at a cut. */
    fun splitPrimary(project: RecorderProject, sourceDuration: Double, outputTime: Double): RecorderProject? {
        val map = timelineMap(project, sourceDuration)
        val segment = map.segmentAtOutputTime(outputTime) ?: return null
        val splitOffset = outputTime - segment.outputStart
        if (splitOffset < 0.08 || segment.outputDuration - splitOffset < 0.08) return null
        val sourceSplit = segment.sourceStart + splitOffset * segment.playbackRate
        val left = RecordingSegment(
            id = segment.id,
            sourceStart = segment.sourceStart,
            sourceDuration = sourceSplit - segment.sourceStart,
            playbackRate = segment.playbackRate,
        )
        val right = RecordingSegment(
            sourceStart = sourceSplit,
            sourceDuration = segment.sourceEnd - sourceSplit,
            playbackRate = segment.playbackRate,
        )
        val updated = map.segments.flatMap {
            when (it.id) {
                segment.id -> listOf(left, right)
                else -> listOf(
                    RecordingSegment(
                        id = it.id,
                        sourceStart = it.sourceStart,
                        sourceDuration = it.sourceDuration,
                        playbackRate = it.playbackRate,
                    )
                )
            }
        }
        var timeline = project.timeline.copy(sourceSequence = SourceSequence.Edited(updated))
        val overrides = timeline.primarySegmentAudioOverrides.toMutableMap()
        overrides[segment.id]?.let { overrides[right.id] = it }
        timeline = timeline.copy(primarySegmentAudioOverrides = overrides)
        return project.copy(timeline = timeline)
    }

    /** Removes an output-time range and closes the gap (ripple delete). */
    fun deletePrimaryRange(
        project: RecorderProject,
        sourceDuration: Double,
        start: Double,
        end: Double,
    ): RecorderProject? {
        if (end - start < 0.02) return null
        val map = timelineMap(project, sourceDuration)
        val segments = map.segments.flatMap { segment ->
            val removeStart = max(start, segment.outputStart)
            val removeEnd = min(end, segment.outputEnd)
            if (removeEnd <= removeStart) {
                return@flatMap listOf(RecordingSegment(
                    id = segment.id,
                    sourceStart = segment.sourceStart,
                    sourceDuration = segment.sourceDuration,
                    playbackRate = segment.playbackRate,
                ))
            }
            val kept = mutableListOf<RecordingSegment>()
            val leadingOutput = removeStart - segment.outputStart
            if (leadingOutput > 0.01) {
                kept += RecordingSegment(
                    id = segment.id,
                    sourceStart = segment.sourceStart,
                    sourceDuration = leadingOutput * segment.playbackRate,
                    playbackRate = segment.playbackRate,
                )
            }
            val trailingOutput = segment.outputEnd - removeEnd
            if (trailingOutput > 0.01) {
                kept += RecordingSegment(
                    sourceStart = segment.sourceEnd - trailingOutput * segment.playbackRate,
                    sourceDuration = trailingOutput * segment.playbackRate,
                    playbackRate = segment.playbackRate,
                )
            }
            kept
        }
        if (segments.isEmpty()) return null
        val normalized = segments.sortedBy { it.sourceStart }
        return project.copy(
            timeline = project.timeline.copy(
                sourceSequence = SourceSequence.Edited(normalized),
                primarySegmentAudioOverrides = project.timeline.primarySegmentAudioOverrides
                    .filterKeys { id -> normalized.any { it.id == id } },
            ),
        )
    }

    fun trimPrimaryEdge(
        project: RecorderProject,
        sourceDuration: Double,
        outputTime: Double,
        leading: Boolean,
        byDelta: Double,
    ): RecorderProject? {
        val map = timelineMap(project, sourceDuration)
        val segment = map.segmentAtOutputTime(outputTime) ?: return null
        val minimum = 0.08
        val delta = if (leading) byDelta else -byDelta
        return if (leading) {
            val newSourceStart = (segment.sourceStart + delta * segment.playbackRate)
                .coerceIn(0.0, segment.sourceEnd - minimum * segment.playbackRate)
            if (abs(newSourceStart - segment.sourceStart) < 0.0001) return null
            replaceSegment(project, map, segment.id) {
                it.copy(
                    sourceStart = newSourceStart,
                    sourceDuration = segment.sourceEnd - newSourceStart,
                )
            }
        } else {
            val newSourceEnd = (segment.sourceEnd + delta * segment.playbackRate)
                .coerceIn(segment.sourceStart + minimum * segment.playbackRate, sourceDuration)
            if (abs(newSourceEnd - segment.sourceEnd) < 0.0001) return null
            replaceSegment(project, map, segment.id) {
                it.copy(sourceDuration = newSourceEnd - segment.sourceStart)
            }
        }
    }

    fun setPlaybackRate(
        project: RecorderProject,
        sourceDuration: Double,
        segmentID: String,
        rate: Double,
    ): RecorderProject? {
        val map = timelineMap(project, sourceDuration)
        val segment = map.segments.firstOrNull { it.id == segmentID } ?: return null
        val safeRate = rate.coerceIn(RecordingSegment.MinimumPlaybackRate, RecordingSegment.MaximumPlaybackRate)
        if (abs(safeRate - segment.playbackRate) < 0.0001) return null
        return replaceSegment(project, map, segmentID) { it.copy(playbackRate = safeRate) }
    }

    fun restoreCut(
        project: RecorderProject,
        sourceDuration: Double,
        outputTime: Double,
    ): RecorderProject? {
        val map = timelineMap(project, sourceDuration)
        val index = map.segments.indexOfLast { it.outputStart <= outputTime + 0.001 }
        if (index < 0) return null
        val current = map.segments[index]
        val previous = map.segments.getOrNull(index - 1)
        val next = map.segments.getOrNull(index + 1)
        val gapBefore = previous?.let { current.sourceStart - it.sourceEnd } ?: 0.0
        val gapAfter = next?.let { it.sourceStart - current.sourceEnd } ?: 0.0
        val candidates = listOf(gapBefore to "before", gapAfter to "after").filter { it.first > 0.001 }
        if (candidates.isEmpty()) return null
        val (gap, side) = candidates.maxBy { it.first }
        val segments = map.segments.map {
            RecordingSegment(
                id = it.id,
                sourceStart = it.sourceStart,
                sourceDuration = it.sourceDuration,
                playbackRate = it.playbackRate,
            )
        }.toMutableList()
        if (side == "before" && previous != null) {
            segments[index - 1] = RecordingSegment(
                id = previous.id,
                sourceStart = previous.sourceStart,
                sourceDuration = previous.sourceDuration + gap,
                playbackRate = previous.playbackRate,
            )
        } else if (side == "after" && next != null) {
            segments[index] = RecordingSegment(
                id = current.id,
                sourceStart = current.sourceStart,
                sourceDuration = current.sourceDuration + gap,
                playbackRate = current.playbackRate,
            )
        } else {
            return null
        }
        val coalesced = mutableListOf<RecordingSegment>()
        for (segment in segments) {
            val last = coalesced.lastOrNull()
            if (last != null && abs(last.sourceEnd - segment.sourceStart) <= 0.0001 &&
                abs(last.playbackRate - segment.playbackRate) <= 0.0001
            ) {
                coalesced[coalesced.lastIndex] = last.copy(sourceDuration = last.sourceDuration + segment.sourceDuration)
            } else {
                coalesced += segment
            }
        }
        return project.copy(timeline = project.timeline.copy(sourceSequence = SourceSequence.Edited(coalesced)))
    }

    fun restoreAllCuts(project: RecorderProject, sourceDuration: Double): RecorderProject {
        return project.copy(
            timeline = project.timeline.copy(
                sourceSequence = SourceSequence.Edited(
                    listOf(RecordingSegment(sourceStart = 0.0, sourceDuration = sourceDuration))
                ),
                primarySegmentAudioOverrides = emptyMap(),
            ),
        )
    }

    private fun replaceSegment(
        project: RecorderProject,
        map: TimelineMap,
        segmentID: String,
        transform: (RecordingSegment) -> RecordingSegment,
    ): RecorderProject {
        val segments = map.segments.map {
            if (it.id == segmentID) {
                transform(
                    RecordingSegment(
                        id = it.id,
                        sourceStart = it.sourceStart,
                        sourceDuration = it.sourceDuration,
                        playbackRate = it.playbackRate,
                    )
                )
            } else {
                RecordingSegment(
                    id = it.id,
                    sourceStart = it.sourceStart,
                    sourceDuration = it.sourceDuration,
                    playbackRate = it.playbackRate,
                )
            }
        }
        return project.copy(timeline = project.timeline.copy(sourceSequence = SourceSequence.Edited(segments)))
    }

    // ---- Overlay / motion clip operations ---------------------------------

    fun splitZoom(project: RecorderProject, clipID: String, time: Double): RecorderProject? {
        val clip = project.timeline.zoomClips.firstOrNull { it.id == clipID } ?: return null
        val minimum = 0.16
        if (time - clip.startTime < minimum || clip.endTime - time < minimum) return null
        val left = clip.copy(endTime = time, exitDuration = 0.0).preservingTransitionIntent()
        val right = clip.copy(
            id = dev.dogsc.core.model.randomId(),
            startTime = time,
            enterDuration = max(clip.enterDuration, 0.15),
        ).preservingTransitionIntent()
        val clips = project.timeline.zoomClips.flatMap { if (it.id == clipID) listOf(left, right) else listOf(it) }
        return project.copy(timeline = project.timeline.copy(zoomClips = clips))
    }

    fun deleteZoom(project: RecorderProject, clipID: String): RecorderProject =
        project.copy(timeline = project.timeline.copy(zoomClips = project.timeline.zoomClips.filterNot { it.id == clipID }))

    fun deleteScreenMotion(project: RecorderProject, clipID: String): RecorderProject =
        project.copy(timeline = project.timeline.copy(screenMotionClips = project.timeline.screenMotionClips.filterNot { it.id == clipID }))

    fun deleteCameraMotion(project: RecorderProject, clipID: String): RecorderProject =
        project.copy(timeline = project.timeline.copy(cameraMotionClips = project.timeline.cameraMotionClips.filterNot { it.id == clipID }))

    fun deleteMosaic(project: RecorderProject, clipID: String): RecorderProject =
        project.copy(timeline = project.timeline.copy(mosaicClips = project.timeline.mosaicClips.filterNot { it.id == clipID }))

    fun deleteSticker(project: RecorderProject, clipID: String): RecorderProject =
        project.copy(timeline = project.timeline.copy(stickerClips = project.timeline.stickerClips.filterNot { it.id == clipID }))

    fun addZoom(
        project: RecorderProject,
        start: Double,
        end: Double,
        scale: Double,
        focus: dev.dogsc.core.model.NormalizedPoint,
        easing: ZoomEasingPreset,
        origin: dev.dogsc.core.model.ZoomKeyframeOrigin = dev.dogsc.core.model.ZoomKeyframeOrigin.MANUAL,
        duration: Double,
    ): RecorderProject? {
        val fitted = fitZoom(
            start, end, project.timeline.zoomClips, duration, scale, focus, origin, easing,
            project.motion.defaultZoomTransitionDuration,
        ) ?: return null
        return project.copy(timeline = project.timeline.copy(zoomClips = project.timeline.zoomClips + fitted))
    }

    fun fitZoom(
        start: Double,
        end: Double,
        among: List<ZoomAnimationClip>,
        duration: Double,
        scale: Double,
        focus: dev.dogsc.core.model.NormalizedPoint,
        origin: dev.dogsc.core.model.ZoomKeyframeOrigin,
        easing: ZoomEasingPreset,
        transitionDuration: Double,
    ): ZoomAnimationClip? {
        if (!duration.isFinite() || duration <= 0) return null
        val rawStart = min(max(min(start, end), 0.0), duration)
        val previous = among.filter { it.startTime <= rawStart }.maxByOrNull { it.startTime }
        val next = among.filter { it.startTime > rawStart }.minByOrNull { it.startTime }
        val lowerTouch = previous?.endTime ?: 0.0
        val lowerClear = previous?.let {
            it.endTime + (if (it.exitDuration > 0.0001) it.exitDuration else transitionDuration)
        } ?: 0.0
        val upper = next?.startTime ?: duration
        if (upper - lowerTouch <= 0.001) return null
        var s = min(max(min(start, end), lowerTouch), upper)
        if (s < lowerClear) s = lowerTouch
        val isClick = abs(end - start) < 0.08
        val requestedLength = if (isClick) min(1.5, max(duration * 0.2, 0.6)) else max(abs(end - start), 0.08)
        var e = s + requestedLength
        if (e > upper) {
            e = upper
            val shifted = max(e - requestedLength, lowerTouch)
            s = if (upper - shifted >= 0.08) shifted else lowerTouch
        }
        if (e - s < 0.08 - 0.0001 || e - s <= 0.001) return null
        val fittedExit = min(transitionDuration, max(upper - e, 0.0))
        return ZoomAnimationClip(
            startTime = s,
            endTime = e,
            scale = scale,
            focus = focus,
            origin = origin,
            easing = easing,
            enterDuration = transitionDuration,
            exitDuration = fittedExit,
            preferredEnterDuration = transitionDuration,
            preferredExitDuration = transitionDuration,
        )
    }

    fun updateZoom(
        project: RecorderProject,
        clipID: String,
        transform: (ZoomAnimationClip) -> ZoomAnimationClip,
    ): RecorderProject {
        val clips = project.timeline.zoomClips.map { if (it.id == clipID) transform(it) else it }
        return project.copy(timeline = project.timeline.copy(zoomClips = clips))
    }

    fun addScreenMotion(
        project: RecorderProject,
        start: Double,
        duration: Double,
        target: dev.dogsc.core.model.ScreenMotionState,
        easing: ZoomEasingPreset,
        leadIn: Double,
        returnDuration: Double,
        focusEffect: dev.dogsc.core.model.FocusEffect? = null,
    ): RecorderProject {
        val clip = ScreenMotionClip(
            timing = TransitionTiming(
                startTime = start,
                duration = duration,
                leadInDuration = min(leadIn, duration),
                easing = easing,
                returnDuration = returnDuration,
            ),
            target = target,
            focusEffect = focusEffect,
        )
        return project.copy(timeline = project.timeline.copy(screenMotionClips = project.timeline.screenMotionClips + clip))
    }

    fun addCameraMotion(
        project: RecorderProject,
        start: Double,
        duration: Double,
        target: dev.dogsc.core.model.CameraMotionState,
        easing: ZoomEasingPreset,
        leadIn: Double,
        returnDuration: Double,
    ): RecorderProject {
        val clip = CameraMotionClip(
            timing = TransitionTiming(
                startTime = start,
                duration = duration,
                leadInDuration = min(leadIn, duration),
                easing = easing,
                returnDuration = returnDuration,
            ),
            target = target,
        )
        return project.copy(timeline = project.timeline.copy(cameraMotionClips = project.timeline.cameraMotionClips + clip))
    }

    fun addMosaic(
        project: RecorderProject,
        timing: OverlayTiming,
        sourceRect: dev.dogsc.core.model.NormalizedOverlayRect,
        style: dev.dogsc.core.model.MosaicEffectStyle,
    ): RecorderProject {
        val clip = MosaicClip(timing = timing, sourceRect = sourceRect, style = style)
        return project.copy(timeline = project.timeline.copy(mosaicClips = project.timeline.mosaicClips + clip))
    }

    fun addSticker(
        project: RecorderProject,
        timing: OverlayTiming,
        relativePath: String,
        width: Double,
        position: dev.dogsc.core.model.NormalizedPoint = dev.dogsc.core.model.NormalizedPoint.Center,
    ): RecorderProject {
        val clip = StickerClip(
            timing = timing,
            relativePath = relativePath,
            width = width,
            position = position,
        )
        return project.copy(timeline = project.timeline.copy(stickerClips = project.timeline.stickerClips + clip))
    }

    fun updateTimeline(project: RecorderProject, transform: (ProjectTimeline) -> ProjectTimeline): RecorderProject =
        project.copy(timeline = transform(project.timeline))

    fun updatePrimaryAudioOverride(
        project: RecorderProject,
        segmentID: String,
        overrides: PrimarySegmentAudioOverrides?,
    ): RecorderProject {
        val map = project.timeline.primarySegmentAudioOverrides.toMutableMap()
        if (overrides == null || overrides.isEmpty) map.remove(segmentID) else map[segmentID] = overrides
        return project.copy(timeline = project.timeline.copy(primarySegmentAudioOverrides = map))
    }

    /** Snapping helper shared by the timeline UI and tests. */
    fun snapTime(
        value: Double,
        candidates: List<Double>,
        threshold: Double,
    ): Double {
        val nearest = candidates.minByOrNull { abs(it - value) } ?: return value
        return if (abs(nearest - value) <= threshold) nearest else value
    }

    fun clipBoundaries(project: RecorderProject, sourceDuration: Double): List<Double> {
        val map = timelineMap(project, sourceDuration)
        val boundaries = mutableListOf(0.0, map.outputDuration)
        map.segments.forEach {
            boundaries += it.outputStart
            boundaries += it.outputEnd
        }
        project.timeline.zoomClips.forEach {
            boundaries += it.startTime
            boundaries += it.endTime
        }
        project.timeline.screenMotionClips.forEach {
            boundaries += it.timing.startTime
            boundaries += it.timing.endTime
        }
        project.timeline.cameraMotionClips.forEach {
            boundaries += it.timing.startTime
            boundaries += it.timing.endTime
        }
        project.timeline.mosaicClips.forEach {
            boundaries += it.timing.startTime
            boundaries += it.timing.endTime
        }
        project.timeline.stickerClips.forEach {
            boundaries += it.timing.startTime
            boundaries += it.timing.endTime
        }
        return boundaries.distinct().sorted()
    }
}
