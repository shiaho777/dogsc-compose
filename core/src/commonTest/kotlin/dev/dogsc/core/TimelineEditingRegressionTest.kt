package dev.dogsc.core

import dev.dogsc.core.editor.TimelineEditor
import dev.dogsc.core.model.BackgroundSource
import dev.dogsc.core.model.CanvasStyle
import dev.dogsc.core.model.MediaSyncAnchor
import dev.dogsc.core.model.MosaicClip
import dev.dogsc.core.model.OverlayTiming
import dev.dogsc.core.model.ProjectMediaReference
import dev.dogsc.core.model.ProjectTimeline
import dev.dogsc.core.model.ProjectValidationException
import dev.dogsc.core.model.ProjectValidator
import dev.dogsc.core.model.RecorderProject
import dev.dogsc.core.model.RecordingSegment
import dev.dogsc.core.model.SourceSequence
import dev.dogsc.core.model.TimelineMap
import dev.dogsc.core.model.TimelineMediaPlan
import dev.dogsc.core.model.MediaTimelinePlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TimelineEditingRegressionTest {
    /** P1: ripple delete used to sort by sourceStart and undo an intentional segment reorder. */
    @Test
    fun rippleDeletePreservesOutputOrder() {
        val reordered = SourceSequence.Edited(
            listOf(
                RecordingSegment(id = "first-in-output", sourceStart = 10.0, sourceDuration = 2.0),
                RecordingSegment(id = "second-in-output", sourceStart = 0.0, sourceDuration = 2.0),
            ),
        )
        val project = RecorderProject(timeline = ProjectTimeline(sourceSequence = reordered))
        val result = TimelineEditor.deletePrimaryRange(project, 20.0, 0.0, 1.0)
            ?: error("expected a remaining timeline")
        val segments = (result.timeline.sourceSequence as SourceSequence.Edited).segments
        assertEquals(2, segments.size)
        assertEquals(11.0, segments[0].sourceStart, 0.0001)
        assertEquals(0.0, segments[1].sourceStart, 0.0001)
    }

    /** P2: extending an edge must stop at the neighboring source range instead of failing validation. */
    @Test
    fun trimmingTrailingEdgeStopsAtNextSegment() {
        val sequence = SourceSequence.Edited(
            listOf(
                RecordingSegment(id = "a", sourceStart = 0.0, sourceDuration = 2.0),
                RecordingSegment(id = "b", sourceStart = 2.0, sourceDuration = 2.0),
            ),
        )
        val project = RecorderProject(timeline = ProjectTimeline(sourceSequence = sequence))
        // Negative delta expands the trailing edge; it must stop at segment b's start.
        assertNull(TimelineEditor.trimPrimaryEdge(project, 4.0, 1.0, leading = false, byDelta = -5.0))
    }

    @Test
    fun projectRelativeBackgroundPathCannotEscapeTheProject() {
        val project = RecorderProject(
            canvas = CanvasStyle(backgroundSource = BackgroundSource.ProjectImage("../secret.png")),
        )
        assertFailsWith<ProjectValidationException> { ProjectValidator.validate(project) }
    }

    @Test
    fun nonFiniteOverlayTimingIsRejected() {
        val project = RecorderProject(
            timeline = ProjectTimeline(
                mosaicClips = listOf(
                    MosaicClip(timing = OverlayTiming(0.0, Double.POSITIVE_INFINITY)),
                ),
            ),
        )
        assertFailsWith<ProjectValidationException> { ProjectValidator.validate(project) }
    }

    @Test
    fun editedTimelineMapsPlayheadToTrimmedSourceTime() {
        val map = TimelineMap(
            SourceSequence.Edited(
                listOf(RecordingSegment(id = "trimmed", sourceStart = 5.0, sourceDuration = 2.0)),
            ),
            10.0,
        )
        assertEquals(6.0, map.sourceTimeAtOutput(1.0) ?: error("expected mapping"), 0.0001)
    }

    @Test
    fun syncAnchorSlicesHaveUniqueIdsForAudioStretchers() {
        val map = TimelineMap(SourceSequence.FullRecording, 10.0)
        val placement = MediaTimelinePlacement.from(
            ProjectMediaReference(
                relativePath = "media/camera.mp4",
                syncAnchors = listOf(MediaSyncAnchor(sourceTime = 5.0, offset = 0.05)),
            ),
            sourceAvailableDuration = 10.0,
            timelineDuration = 10.0,
        )
        val slices = TimelineMediaPlan.auxiliary(
            map,
            placement,
            syncAnchors = placement.let { listOf(MediaSyncAnchor(sourceTime = 5.0, offset = 0.05)) },
        ).slices
        assertEquals(2, slices.size)
        assertEquals(slices.size, slices.map { it.id }.distinct().size)
    }
}
