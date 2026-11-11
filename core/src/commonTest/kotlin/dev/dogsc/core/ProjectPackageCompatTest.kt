package dev.dogsc.core

import dev.dogsc.core.model.CameraShape
import dev.dogsc.core.model.CaptureCodec
import dev.dogsc.core.model.CaptureResolutionLimit
import dev.dogsc.core.model.CaptureSource
import dev.dogsc.core.model.CursorMotionStyle
import dev.dogsc.core.model.OpeningSequencePreset
import dev.dogsc.core.model.OutputFrameRate
import dev.dogsc.core.model.ScreenFrameStyle
import dev.dogsc.core.model.ScreenMotionStyle
import dev.dogsc.core.model.ZoomEasingPreset
import dev.dogsc.core.project.ProjectCodec
import dev.dogsc.core.project.ProjectPackageCompat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ProjectPackageCompatTest {
    @Test
    fun upstreamEnumRawValuesAndBackgroundShapeAreMapped() {
        val upstream = """
            {
              "version": 9,
              "title": "Upstream",
              "createdAt": "2026-01-01T00:00:00Z",
              "capture": {
                "source": "整块屏幕",
                "captureFrameRate": 30,
                "captureCodec": "H.264",
                "captureResolutionLimit": "最高 1080p",
                "systemAudioScope": "全部系统声音",
                "recordsSystemAudio": true,
                "recordsMicrophone": false,
                "recordsCamera": false
              },
              "canvas": {
                "screenFrame": "devicePhonePortrait",
                "backgroundSource": { "kind": "pattern", "patternPreset": "obsidianGrid" }
              },
              "camera": { "shape": "方形" },
              "motion": { "screen": "聚焦", "cursor": "平滑" },
              "openingSequence": {
                "isEnabled": true,
                "preset": "轻 3D",
                "includedElements": ["屏幕", "摄像头", "贴图"],
                "elementOrder": ["屏幕", "摄像头", "贴图"]
              },
              "exportSettings": { "frameRate": 30, "resolution": "source" },
              "timeline": {
                "zoomClips": [{
                  "id": "00000000-0000-0000-0000-000000000001",
                  "startTime": 0.0, "endTime": 1.0, "scale": 1.4,
                  "focus": {"x":0.5,"y":0.5}, "origin": "manual",
                  "easing": "三次方缓动"
                }]
              },
              "media": { "screen": { "relativePath": "media/screen.mp4" } },
              "recordingMarkers": []
            }
        """.trimIndent()
        val adapted = assertNotNull(ProjectPackageCompat.adapt(upstream))
        val project = ProjectCodec.decode(adapted)
        assertEquals(CaptureSource.DISPLAY, project.capture.source)
        assertEquals(OutputFrameRate.FPS_30, project.capture.captureFrameRate)
        assertEquals(CaptureCodec.H264, project.capture.captureCodec)
        assertEquals(CaptureResolutionLimit.FULL_HD, project.capture.captureResolutionLimit)
        assertEquals(ScreenFrameStyle.DEVICE_PHONE, project.canvas.screenFrame)
        assertEquals(CameraShape.SQUARE, project.camera.shape)
        assertEquals(ScreenMotionStyle.FOCUSED, project.motion.screen)
        assertEquals(CursorMotionStyle.SMOOTH, project.motion.cursor)
        assertEquals(OpeningSequencePreset.LIGHT_3D, project.openingSequence.preset)
        assertEquals(ZoomEasingPreset.CUBIC, project.timeline.zoomClips.single().easing)
        assertEquals(OutputFrameRate.FPS_30, project.exportSettings.frameRate)
    }
}
