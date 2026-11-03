package dev.dogsc.core

import dev.dogsc.core.model.CanvasAspectRatio
import dev.dogsc.core.model.CanvasDimensions
import dev.dogsc.core.model.CanvasResolution
import dev.dogsc.core.model.CanvasStyle
import dev.dogsc.core.model.ExportEstimator
import dev.dogsc.core.model.ExportSettings
import dev.dogsc.core.model.OutputFrameRate
import dev.dogsc.core.model.RecorderProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportEstimatorTest {
    private val project = RecorderProject(
        canvas = CanvasStyle(aspectRatio = CanvasAspectRatio.ADAPTIVE),
        exportSettings = ExportSettings(resolution = CanvasResolution.SOURCE, frameRate = OutputFrameRate.FPS_60),
    )
    private val source = CanvasDimensions(1280, 720)

    @Test
    fun sourceResolutionUsesRealSourcePixels() {
        val estimate = ExportEstimator.estimate(project, 12.0, 16.0 / 9.0, sourcePixelSize = source)
        assertEquals(1280, estimate.width)
        assertEquals(720, estimate.height)
    }

    @Test
    fun estimateFollowsCurrentSelectionNotSavedSettings() {
        val estimate = ExportEstimator.estimate(
            project, 12.0, 16.0 / 9.0,
            resolution = CanvasResolution.FULL_HD,
            frameRate = OutputFrameRate.FPS_30,
            sourcePixelSize = source,
        )
        assertEquals(1920, estimate.width)
        assertEquals(1080, estimate.height)
        assertEquals(30, estimate.frameRate)
    }

    @Test
    fun higherFrameRateNeverShrinksEstimate() {
        val at30 = ExportEstimator.estimate(project, 12.0, 16.0 / 9.0, CanvasResolution.FULL_HD, OutputFrameRate.FPS_30, source)
        val at60 = ExportEstimator.estimate(project, 12.0, 16.0 / 9.0, CanvasResolution.FULL_HD, OutputFrameRate.FPS_60, source)
        assertTrue(at60.estimatedBytes >= at30.estimatedBytes)
    }

    @Test
    fun dimensionsAreEvenLikeTheEncoder() {
        val estimate = ExportEstimator.estimate(project, 1.0, 1.0, sourcePixelSize = CanvasDimensions(1281, 721))
        assertEquals(0, estimate.width % 2)
        assertEquals(0, estimate.height % 2)
    }
}
