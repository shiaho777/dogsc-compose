package dev.dogsc.core

import dev.dogsc.core.model.CanvasAspectRatio
import dev.dogsc.core.model.CanvasResolution
import dev.dogsc.core.model.CanvasStyle
import dev.dogsc.core.model.CompositionSize
import dev.dogsc.core.model.NormalizedCrop
import dev.dogsc.core.model.NormalizedPoint
import dev.dogsc.core.project.ProjectCodec
import dev.dogsc.core.model.RecorderProject
import dev.dogsc.core.model.ScreenMotionState
import dev.dogsc.core.model.ScreenMotionTrack
import dev.dogsc.core.model.ZoomAnimationClip
import dev.dogsc.core.model.ZoomAnimationTrack
import dev.dogsc.core.model.ZoomEasingPreset
import dev.dogsc.core.model.ZoomKeyframeOrigin
import dev.dogsc.core.scene.CompositionSceneEvaluator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZoomTrackTest {
    private fun clip(
        start: Double,
        end: Double,
        scale: Double = 2.0,
        enter: Double = 0.5,
        exit: Double = 0.5,
    ) = ZoomAnimationClip(
        startTime = start,
        endTime = end,
        scale = scale,
        focus = NormalizedPoint(0.3, 0.7),
        origin = ZoomKeyframeOrigin.MANUAL,
        easing = ZoomEasingPreset.LINEAR,
        enterDuration = enter,
        exitDuration = exit,
    )

    @Test
    fun enterHoldExitReachAuthoredEndpoints() {
        val track = ZoomAnimationTrack(listOf(clip(1.0, 3.0)))
        assertEquals(1.0, track.sample(1.0).scale, 0.0001)
        assertTrue(track.sample(1.25).scale in 1.0..2.0)
        assertEquals(2.0, track.sample(2.0).scale, 0.0001)
        assertTrue(track.sample(3.25).scale in 1.0..2.0)
        assertEquals(1.0, track.sample(3.5).scale, 0.0001)
        assertEquals(1.0, track.sample(10.0).scale, 0.0001)
    }

    @Test
    fun adjacentClipsHandOffWithoutReturningToBase() {
        val first = clip(1.0, 2.0, scale = 2.0, enter = 0.2, exit = 0.5)
        val second = clip(2.0, 3.0, scale = 3.0, enter = 0.5, exit = 0.5)
        val track = ZoomAnimationTrack(listOf(first, second))
        assertEquals(2.0, track.sample(2.0).scale, 0.0001)
        val midway = track.sample(2.25).scale
        assertTrue(midway > 2.0 && midway < 3.0, "expected a smooth handoff, got $midway")
        assertEquals(3.0, track.sample(2.75).scale, 0.0001)
    }

    @Test
    fun automaticFocusOverrideOnlyAppliesToAutomaticClips() {
        val automatic = clip(1.0, 2.0).copy(origin = ZoomKeyframeOrigin.AUTOMATIC)
        val manual = clip(1.0, 2.0)
        val track = ZoomAnimationTrack(listOf(automatic))
        val focus = track.sample(1.5, automaticFocus = NormalizedPoint(0.8, 0.2)).focus
        assertEquals(0.8, focus.x, 0.0001)
        assertEquals(0.2, focus.y, 0.0001)
        val manualTrack = ZoomAnimationTrack(listOf(manual))
        val manualFocus = manualTrack.sample(1.5, automaticFocus = NormalizedPoint(0.8, 0.2)).focus
        assertEquals(0.3, manualFocus.x, 0.0001)
    }
}

class SceneEvaluatorTest {
    @Test
    fun aspectFitAccountsForPadding() {
        val project = RecorderProject(canvas = CanvasStyle(padding = 100.0))
        val scene = CompositionSceneEvaluator.evaluate(
            project = project,
            time = 0.0,
            canvasWidth = 1920.0,
            canvasHeight = 1080.0,
            sourceAspectRatio = 16.0 / 9.0,
        )
        assertEquals(1564.4444, scene.screen.fittedRect.width, 0.01)
        assertEquals(880.0, scene.screen.fittedRect.height, 0.01)
        assertEquals(177.7778, scene.screen.fittedRect.x, 0.01)
        assertEquals(100.0, scene.screen.fittedRect.y, 0.01)
    }

    @Test
    fun canvasDimensionsFollowPresetRules() {
        val style = CanvasStyle(aspectRatio = CanvasAspectRatio.LANDSCAPE)
        val dimensions = style.pixelDimensions(CanvasResolution.FULL_HD)
        assertEquals(1920, dimensions.width)
        assertEquals(1080, dimensions.height)

        val portrait = CanvasStyle(aspectRatio = CanvasAspectRatio.PORTRAIT)
        val portraitDimensions = portrait.pixelDimensions(CanvasResolution.FULL_HD)
        assertEquals(1080, portraitDimensions.width)
        assertEquals(1920, portraitDimensions.height)

        val square = CanvasStyle(aspectRatio = CanvasAspectRatio.SQUARE)
        val squareDimensions = square.pixelDimensions(CanvasResolution.ULTRA_HD)
        assertEquals(2160, squareDimensions.width)
        assertEquals(2160, squareDimensions.height)
    }

    @Test
    fun sourceResolutionRespectsCrop() {
        val style = CanvasStyle(
            aspectRatio = CanvasAspectRatio.ADAPTIVE,
            crop = NormalizedCrop(x = 0.1, y = 0.1, width = 0.5, height = 0.5),
        )
        val dimensions = style.pixelDimensions(
            CanvasResolution.SOURCE,
            sourcePixelSize = dev.dogsc.core.model.CanvasDimensions(3840, 2160),
        )
        assertEquals(1920, dimensions.width)
        assertEquals(1080, dimensions.height)
    }

    @Test
    fun screenMotionScaleAndPositionAreResolved() {
        val track = ScreenMotionTrack(emptyList())
        val base = ScreenMotionState(position = NormalizedPoint(0.5, 0.5), scale = 1.0)
        val sample = track.sample(0.0, base)
        assertEquals(1.0, sample.scale, 0.0001)
        assertEquals(0.5, sample.position.x, 0.0001)
    }
}

class ProjectCodecTest {
    @Test
    fun roundTripPreservesProject() {
        val project = RecorderProject(
            title = "测试项目",
            canvas = CanvasStyle(padding = 72.0, aspectRatio = CanvasAspectRatio.PORTRAIT),
        )
        val encoded = ProjectCodec.encode(project)
        val decoded = ProjectCodec.decode(encoded)
        assertEquals(project.title, decoded.title)
        assertEquals(project.canvas, decoded.canvas)
        assertEquals(project.id, decoded.id)
        assertTrue(encoded.contains("schemaVersion"))
    }
}

class TimelineEditingTest {
    @Test
    fun splitThenDeleteClosesGap() {
        val project = RecorderProject()
        val split = dev.dogsc.core.editor.TimelineEditor.splitPrimary(project, 10.0, 4.0)!!
        val edited = (split.timeline.sourceSequence as dev.dogsc.core.model.SourceSequence.Edited).segments
        assertEquals(2, edited.size)
        assertEquals(4.0, edited[0].sourceDuration, 0.0001)
        assertEquals(6.0, edited[1].sourceDuration, 0.0001)

        val deleted = dev.dogsc.core.editor.TimelineEditor.deletePrimaryRange(split, 10.0, 3.0, 5.0)!!
        val remaining = (deleted.timeline.sourceSequence as dev.dogsc.core.model.SourceSequence.Edited).segments
        assertEquals(8.0, remaining.sumOf { it.outputDuration }, 0.0001)
        assertEquals(8.0, dev.dogsc.core.editor.TimelineEditor.outputDuration(deleted, 10.0), 0.0001)
    }

    @Test
    fun restoreCutMergesAdjacentSource() {
        val project = RecorderProject()
        val split = dev.dogsc.core.editor.TimelineEditor.splitPrimary(project, 10.0, 4.0)!!
        val deleted = dev.dogsc.core.editor.TimelineEditor.deletePrimaryRange(split, 10.0, 3.0, 5.0)!!
        val restored = dev.dogsc.core.editor.TimelineEditor.restoreCut(deleted, 10.0, 3.0)!!
        val segments = (restored.timeline.sourceSequence as dev.dogsc.core.model.SourceSequence.Edited).segments
        assertEquals(1, segments.size)
        assertEquals(10.0, segments[0].sourceDuration, 0.0001)
    }
}

class AutoZoomPlannerTest {
    @Test
    fun nearbyClicksShareOneStableCameraRegion() {
        val events = listOf(
            dev.dogsc.core.model.PointerEventRecord(1.0, NormalizedPoint(0.2, 0.3), dev.dogsc.core.model.PointerEventKind.LEFT_CLICK),
            dev.dogsc.core.model.PointerEventRecord(1.2, NormalizedPoint(0.24, 0.32), dev.dogsc.core.model.PointerEventKind.LEFT_CLICK),
        )
        val clips = dev.dogsc.core.model.AutoZoomPlanner.makeAnimations(events, zoomScale = 2.0)
        assertEquals(1, clips.size)
        assertEquals(0.22, clips[0].focus.x, 0.001)
        assertEquals(0.31, clips[0].focus.y, 0.001)
        assertTrue(clips[0].startTime < 1.0)
        assertTrue(clips[0].endTime > 2.0)
    }
}

class OpeningAndStickerTest {
    @Test
    fun openingStartsHiddenAndSettlesExactly() {
        val project = RecorderProject(
            openingSequence = dev.dogsc.core.model.OpeningSequence(
                isEnabled = true,
                duration = 2.0,
                includedElements = listOf(dev.dogsc.core.model.OpeningSequenceElement.SCREEN),
                elementOrder = listOf(dev.dogsc.core.model.OpeningSequenceElement.SCREEN),
            ),
        )
        val start = dev.dogsc.core.scene.FrameSceneEvaluator.scene(
            project = project,
            time = 0.0,
            canvasSize = CompositionSize(1920.0, 1080.0),
            sourceAspectRatio = 16.0 / 9.0,
        )
        assertTrue(start.screen.opacity < 0.01)
        val settled = dev.dogsc.core.scene.FrameSceneEvaluator.scene(
            project = project,
            time = 2.0,
            canvasSize = CompositionSize(1920.0, 1080.0),
            sourceAspectRatio = 16.0 / 9.0,
        )
        assertEquals(1.0, settled.screen.opacity, 0.0001)
        assertEquals(1.0, settled.screen.projectedQuad.topLeft.x / settled.screen.finalRect.x, 0.0001)
    }
}

class ProjectionAndTimelinePlanTest {
    @Test
    fun identityQuadProjectsBackToSourcePoint() {
        val rect = dev.dogsc.core.model.CompositionRect(10.0, 20.0, 100.0, 50.0)
        val quad = dev.dogsc.core.scene.ScreenProjection.project(rect, 0.0, 0.0, 0.0, 0.0)
        val point = dev.dogsc.core.model.CompositionPoint(60.0, 45.0)
        val projected = quad.project(point, rect)!!
        assertEquals(point.x, projected.x, 0.0001)
        assertEquals(point.y, projected.y, 0.0001)
    }

    @Test
    fun timelinePlacementMapsBothDirections() {
        val placement = dev.dogsc.core.model.MediaTimelinePlacement(
            timelineStart = 2.0,
            sourceStart = 10.0,
            playableDuration = 5.0,
            sourceTimeScale = 2.0,
        )
        assertEquals(10.0, placement.sourceTimeAt(2.0)!!, 0.0001)
        assertEquals(18.0, placement.sourceTimeAt(6.0)!!, 0.0001)
        assertEquals(2.0, placement.timelineTimeForSource(10.0)!!, 0.0001)
        assertEquals(6.5, placement.timelineTimeForSource(19.0)!!, 0.0001)
        assertEquals(null, placement.sourceTimeAt(7.1))
    }

    @Test
    fun trimAndRestoreRoundTrip() {
        val project = RecorderProject()
        val split = dev.dogsc.core.editor.TimelineEditor.splitPrimary(project, 10.0, 4.0)!!
        val trimmed = dev.dogsc.core.editor.TimelineEditor.trimPrimaryEdge(split, 10.0, 2.0, leading = true, byDelta = 1.0)!!
        val segments = (trimmed.timeline.sourceSequence as dev.dogsc.core.model.SourceSequence.Edited).segments
        assertEquals(1.0, segments[0].sourceStart, 0.0001)
        assertEquals(9.0, segments.sumOf { it.outputDuration }, 0.0001)
    }

    @Test
    fun stickerFadeProducesPartialVisibility() {
        val clip = dev.dogsc.core.model.StickerClip(
            timing = dev.dogsc.core.model.OverlayTiming(1.0, 2.0),
            relativePath = "media/sticker.png",
            animation = dev.dogsc.core.model.StickerAnimationPreset.FADE,
            enterDuration = 0.5,
            exitDuration = 0.5,
        )
        val track = dev.dogsc.core.model.StickerTransitionTrack(listOf(clip))
        val atStart = track.sample(1.0, false).first.firstOrNull()
        assertEquals(0.0, atStart?.visibility ?: 0.0, 0.05)
        val middle = track.sample(2.0, false).first.firstOrNull()
        assertEquals(1.0, middle?.visibility ?: 0.0, 0.001)
        val atEnd = track.sample(3.0, false).first.firstOrNull()
        assertEquals(0.0, atEnd?.visibility ?: 0.0, 0.05)
    }
}
