package dev.dogsc.core

import dev.dogsc.core.editor.EditorSession
import dev.dogsc.core.editor.TimelineEditor
import dev.dogsc.core.model.MosaicClip
import dev.dogsc.core.model.OverlayTiming
import dev.dogsc.core.model.PrimarySegmentAudioOverrides
import dev.dogsc.core.model.ProjectTimeline
import dev.dogsc.core.model.RecorderProject
import dev.dogsc.core.model.RecordingSegment
import dev.dogsc.core.model.SourceSequence
import dev.dogsc.core.model.StickerClip
import dev.dogsc.core.model.ZoomAnimationClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ripple edits must carry output-clock effects (zoom / mosaic / stickers / motion)
 * with the content they were authored against. A redaction mosaic drifting off its
 * sensitive region after a delete is a privacy bug, not a cosmetic one.
 */
class EffectRemapTest {
    private fun projectWithEffects(): RecorderProject = RecorderProject(
        timeline = ProjectTimeline(
            sourceSequence = SourceSequence.FullRecording,
            zoomClips = listOf(
                ZoomAnimationClip(id = "zoom-early", startTime = 1.0, endTime = 2.0),
                ZoomAnimationClip(id = "zoom-late", startTime = 6.0, endTime = 8.0),
            ),
            mosaicClips = listOf(
                MosaicClip(id = "mosaic-late", timing = OverlayTiming(startTime = 5.0, duration = 2.0)),
                MosaicClip(id = "mosaic-inside-cut", timing = OverlayTiming(startTime = 3.2, duration = 0.5)),
            ),
            stickerClips = listOf(
                StickerClip(id = "sticker-spanning", timing = OverlayTiming(startTime = 2.0, duration = 6.0), relativePath = "media/s.png"),
            ),
        ),
    )

    @Test
    fun rippleDeleteShiftsLaterEffectsOntoTheSameContent() {
        val project = projectWithEffects()
        val result = assertNotNull(TimelineEditor.deletePrimaryRange(project, 10.0, 3.0, 5.0))
        // 2 s removed: everything authored at output >= 5 shifts left by 2.
        val zoom = result.timeline.zoomClips.first { it.id == "zoom-late" }
        assertEquals(4.0, zoom.startTime, 1e-6)
        assertEquals(6.0, zoom.endTime, 1e-6)
        val mosaic = result.timeline.mosaicClips.first { it.id == "mosaic-late" }
        assertEquals(3.0, mosaic.timing.startTime, 1e-6)
        assertEquals(2.0, mosaic.timing.duration, 1e-6)
        // Untouched earlier effect stays put.
        val early = result.timeline.zoomClips.first { it.id == "zoom-early" }
        assertEquals(1.0, early.startTime, 1e-6)
    }

    @Test
    fun effectFullyInsideTheDeletedRangeIsRemoved() {
        val project = projectWithEffects()
        val result = assertNotNull(TimelineEditor.deletePrimaryRange(project, 10.0, 3.0, 5.0))
        assertTrue(result.timeline.mosaicClips.none { it.id == "mosaic-inside-cut" })
    }

    @Test
    fun effectSpanningTheCutIsCompressedNotDropped() {
        val project = projectWithEffects()
        val result = assertNotNull(TimelineEditor.deletePrimaryRange(project, 10.0, 3.0, 5.0))
        val sticker = result.timeline.stickerClips.first { it.id == "sticker-spanning" }
        // Authored 2..8; source 3..5 removed → 2..6 on the new clock.
        assertEquals(2.0, sticker.timing.startTime, 1e-6)
        assertEquals(4.0, sticker.timing.duration, 1e-6)
    }

    @Test
    fun retimeShiftsLaterEffectsByTheSpeedChange() {
        val project = projectWithEffects()
        val split = assertNotNull(TimelineEditor.splitPrimary(project, 10.0, 4.0))
        val firstId = (split.timeline.sourceSequence as SourceSequence.Edited).segments.first().id
        val fast = assertNotNull(TimelineEditor.setPlaybackRate(split, 10.0, firstId, 2.0))
        // First 4 s now play in 2 s: later effects shift left by 2.
        val mosaic = fast.timeline.mosaicClips.first { it.id == "mosaic-late" }
        assertEquals(3.0, mosaic.timing.startTime, 1e-6)
    }

    @Test
    fun reorderCarriesEffectsToTheNewOutputPositions() {
        val project = RecorderProject(
            timeline = ProjectTimeline(
                sourceSequence = SourceSequence.Edited(
                    listOf(
                        RecordingSegment(id = "a", sourceStart = 0.0, sourceDuration = 4.0),
                        RecordingSegment(id = "b", sourceStart = 4.0, sourceDuration = 4.0),
                    ),
                ),
                mosaicClips = listOf(
                    MosaicClip(id = "on-a", timing = OverlayTiming(startTime = 1.0, duration = 1.0)),
                    MosaicClip(id = "on-b", timing = OverlayTiming(startTime = 5.0, duration = 1.0)),
                ),
            ),
        )
        val moved = assertNotNull(TimelineEditor.movePrimarySegment(project, 8.0, "b", 0))
        val onA = moved.timeline.mosaicClips.first { it.id == "on-a" }
        val onB = moved.timeline.mosaicClips.first { it.id == "on-b" }
        assertEquals(5.0, onA.timing.startTime, 1e-6)
        assertEquals(1.0, onB.timing.startTime, 1e-6)
    }

    @Test
    fun trailingPieceKeepsItsAudioOverridesAfterDelete() {
        val project = RecorderProject(
            timeline = ProjectTimeline(
                sourceSequence = SourceSequence.FullRecording,
                primarySegmentAudioOverrides = mapOf(
                    dev.dogsc.core.model.TimelineMap.FullRecordingSegmentId to
                        PrimarySegmentAudioOverrides(systemVolume = 0.25),
                ),
            ),
        )
        val result = assertNotNull(TimelineEditor.deletePrimaryRange(project, 10.0, 3.0, 5.0))
        val segments = (result.timeline.sourceSequence as SourceSequence.Edited).segments
        assertEquals(2, segments.size)
        val trailing = segments[1]
        assertEquals(
            0.25,
            result.timeline.primarySegmentAudioOverrides[trailing.id]?.systemVolume,
        )
    }

    @Test
    fun restoreAllCutsRemapsEffectsBackToTheSourceClock() {
        val project = RecorderProject(
            timeline = ProjectTimeline(
                sourceSequence = SourceSequence.Edited(
                    listOf(
                        RecordingSegment(id = "a", sourceStart = 0.0, sourceDuration = 3.0),
                        RecordingSegment(id = "b", sourceStart = 5.0, sourceDuration = 5.0),
                    ),
                ),
                mosaicClips = listOf(
                    MosaicClip(id = "on-b", timing = OverlayTiming(startTime = 4.0, duration = 2.0)),
                ),
            ),
        )
        val restored = TimelineEditor.restoreAllCuts(project, 10.0)
        val mosaic = restored.timeline.mosaicClips.first { it.id == "on-b" }
        // Output 4..6 lived on source 6..8; with all cuts restored that is output 6..8.
        assertEquals(6.0, mosaic.timing.startTime, 1e-6)
        assertEquals(2.0, mosaic.timing.duration, 1e-6)
    }

    @Test
    fun cancelledGestureDoesNotLeavePhantomDirtyState() {
        val project = RecorderProject()
        val session = EditorSession(project, 10.0)
        session.beginGesture()
        assertTrue(session.previewGesture { it.copy(title = "dragged") })
        assertTrue(session.isDirty)
        session.cancelGesture()
        assertEquals(project, session.project)
        assertFalse(session.isDirty)
    }

    @Test
    fun cancelledGestureAfterRealEditsKeepsDirtyState() {
        val project = RecorderProject()
        val session = EditorSession(project, 10.0)
        session.commit("rename") { it.copy(title = "saved-soon") }
        session.markSaved()
        session.beginGesture()
        session.previewGesture { it.copy(title = "dragged") }
        session.cancelGesture()
        // canUndo is true (a committed edit exists), so dirty must survive the cancel.
        assertTrue(session.isDirty)
    }
}