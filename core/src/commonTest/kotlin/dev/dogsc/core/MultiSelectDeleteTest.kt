package dev.dogsc.core

import dev.dogsc.core.editor.EditorSession
import dev.dogsc.core.editor.TimelineEditor
import dev.dogsc.core.model.MosaicClip
import dev.dogsc.core.model.NormalizedOverlayRect
import dev.dogsc.core.model.OverlayTiming
import dev.dogsc.core.model.ProjectTimeline
import dev.dogsc.core.model.RecorderProject
import dev.dogsc.core.model.StickerClip
import dev.dogsc.core.model.ZoomAnimationClip
import dev.dogsc.core.model.ZoomKeyframeOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiSelectDeleteTest {
    private fun project() = RecorderProject(
        timeline = ProjectTimeline(
            zoomClips = listOf(
                ZoomAnimationClip(id = "z1", startTime = 0.0, endTime = 1.0, origin = ZoomKeyframeOrigin.MANUAL),
                ZoomAnimationClip(id = "z2", startTime = 2.0, endTime = 3.0, origin = ZoomKeyframeOrigin.MANUAL),
            ),
            mosaicClips = listOf(
                MosaicClip(id = "m1", timing = OverlayTiming(0.0, 1.0), sourceRect = NormalizedOverlayRect(0.1, 0.1, 0.3, 0.3)),
            ),
            stickerClips = listOf(
                StickerClip(id = "s1", timing = OverlayTiming(0.0, 2.0), relativePath = "a.png"),
            ),
        ),
    )

    @Test
    fun batchDeleteRemovesAcrossTracks() {
        val result = TimelineEditor.deleteClips(
            project(),
            zoomIds = setOf("z1"),
            mosaicIds = setOf("m1"),
            stickerIds = setOf("s1"),
        )
        assertEquals(listOf("z2"), result.timeline.zoomClips.map { it.id })
        assertTrue(result.timeline.mosaicClips.isEmpty())
        assertTrue(result.timeline.stickerClips.isEmpty())
    }

    @Test
    fun batchDeleteIsOneUndoEntry() {
        val session = EditorSession(project(), sourceDuration = 10.0)
        session.commit("删除选中片段") {
            TimelineEditor.deleteClips(it, zoomIds = setOf("z1", "z2"), mosaicIds = setOf("m1"))
        }
        assertTrue(session.canUndo)
        assertEquals("删除选中片段", session.undoLabel)
        session.undo()
        assertEquals(2, session.project.timeline.zoomClips.size)
        assertEquals(1, session.project.timeline.mosaicClips.size)
        assertFalse(session.canUndo)
    }

    @Test
    fun emptyBatchIsNoOp() {
        val original = project()
        val result = TimelineEditor.deleteClips(original)
        assertEquals(original, result)
    }

    @Test
    fun transferZoomAttributesCopiesAppearanceNotIdentity() {
        val p = RecorderProject(
            timeline = ProjectTimeline(
                zoomClips = listOf(
                    ZoomAnimationClip(id = "src", startTime = 0.0, endTime = 1.0, scale = 2.5, origin = ZoomKeyframeOrigin.MANUAL),
                    ZoomAnimationClip(id = "dst", startTime = 3.0, endTime = 4.0, scale = 1.2, origin = ZoomKeyframeOrigin.MANUAL),
                ),
            ),
        )
        val result = TimelineEditor.transferZoomAttributes(p, "src", "dst")!!
        val dst = result.timeline.zoomClips.first { it.id == "dst" }
        assertEquals(2.5, dst.scale, 0.0001)
        assertEquals(3.0, dst.startTime, 0.0001) // timing preserved
        assertEquals("dst", dst.id) // identity preserved
    }

    @Test
    fun cameraSyncAnchorAddUpdateRemove() {
        val camera = dev.dogsc.core.model.ProjectMediaReference(relativePath = "camera.mp4", durationSeconds = 10.0)
        val p = RecorderProject(media = dev.dogsc.core.model.ProjectMediaManifest(
            screen = dev.dogsc.core.model.ProjectMediaReference(relativePath = "screen.mp4"),
            camera = camera,
        ))
        val added = TimelineEditor.addCameraSyncAnchor(p, sourceTime = 2.0, offset = 0.3)!!
        assertEquals(1, added.media!!.camera!!.syncAnchors.size)
        val anchorId = added.media!!.camera!!.syncAnchors.first().id
        val updated = TimelineEditor.updateCameraSyncAnchor(added, anchorId, offset = 0.6)!!
        assertEquals(0.6, updated.media!!.camera!!.syncAnchors.first().offset, 0.0001)
        val removed = TimelineEditor.removeCameraSyncAnchor(updated, anchorId)!!
        assertTrue(removed.media!!.camera!!.syncAnchors.isEmpty())
    }

    @Test
    fun duplicateZoomCreatesFreshIdAtNewStart() {
        val p = RecorderProject(
            timeline = ProjectTimeline(
                zoomClips = listOf(
                    ZoomAnimationClip(id = "src", startTime = 0.0, endTime = 1.0, origin = ZoomKeyframeOrigin.MANUAL),
                ),
            ),
        )
        val result = TimelineEditor.duplicateZoom(p, "src", newStart = 5.0, duration = 10.0)!!
        assertEquals(2, result.timeline.zoomClips.size)
        val copy = result.timeline.zoomClips.last()
        assertTrue(copy.id != "src")
        assertEquals(5.0, copy.startTime, 0.0001)
        assertEquals(6.0, copy.endTime, 0.0001)
    }
}