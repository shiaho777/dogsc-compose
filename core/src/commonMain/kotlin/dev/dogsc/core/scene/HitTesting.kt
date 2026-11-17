package dev.dogsc.core.scene

import dev.dogsc.core.model.CompositionPoint
import dev.dogsc.core.model.CompositionRect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure canvas hit-testing for direct manipulation (stage 4). All functions work in canvas
 * space and share the exact geometry the renderers draw, so a tap resolves to the element
 * actually under the finger — including tilted/3D screen cards and rotated stickers.
 *
 * Touch tolerance is enlarged from upstream's 9px mouse slack to [touchTolerance] (24dp-equivalent
 * canvas units passed by the caller) so fingers hit reliably.
 */
object CanvasHitTesting {
    /** Default touch slack in canvas pixels; callers scale by preview density. */
    const val DefaultTouchTolerance = 24.0

    /** What a canvas tap landed on, resolved front-to-back by [hitTest]. */
    sealed interface Hit {
        data class Sticker(val id: String) : Hit
        data class Camera(val rect: CompositionRect) : Hit
        data class Mosaic(val id: String, val index: Int) : Hit
        data object Screen : Hit
        data object Empty : Hit
    }

    /** Axis-aligned rect containment with uniform slack. */
    fun rectContains(rect: CompositionRect, point: CompositionPoint, tolerance: Double = 0.0): Boolean =
        point.x >= rect.x - tolerance && point.x <= rect.right + tolerance &&
            point.y >= rect.y - tolerance && point.y <= rect.bottom + tolerance

    /**
     * Rotated-rect containment: un-rotates [point] about [center] by [rotationRadians], then
     * tests the axis-aligned box of [width]×[height]. Matches the sticker renderer's transform.
     */
    fun rotatedRectContains(
        center: CompositionPoint,
        width: Double,
        height: Double,
        rotationRadians: Double,
        point: CompositionPoint,
        tolerance: Double = 0.0,
    ): Boolean {
        val dx = point.x - center.x
        val dy = point.y - center.y
        val c = cos(-rotationRadians)
        val s = sin(-rotationRadians)
        val lx = dx * c - dy * s
        val ly = dx * s + dy * c
        return abs(lx) <= width / 2 + tolerance && abs(ly) <= height / 2 + tolerance
    }

    /**
     * Resolves a canvas-space tap to the topmost element. Stickers (front, by layer index)
     * win over camera, then mosaic regions, then the screen card; nothing matches -> Empty.
     * [stickerRect] supplies each sticker's drawn box (center/size/rotation) so hit-testing
     * stays identical to rendering without duplicating image-aspect math here.
     */
    fun hitTest(
        scene: FrameScene,
        point: CompositionPoint,
        stickerRect: (FrameStickerScene) -> StickerBox?,
        tolerance: Double = DefaultTouchTolerance,
    ): Hit {
        for (sticker in scene.stickers.sortedByDescending { it.layerIndex }) {
            if (sticker.opacity <= 0.01 || sticker.transitionProgress <= 0.01) continue
            val box = stickerRect(sticker) ?: continue
            if (rotatedRectContains(box.center, box.width, box.height, box.rotationRadians, point, tolerance)) {
                return Hit.Sticker(sticker.id)
            }
        }
        scene.camera?.let { camera ->
            if (camera.opacity > 0.01 && rectContains(camera.rect, point, tolerance)) {
                return Hit.Camera(camera.rect)
            }
        }
        val content = scene.screen.finalRect
        scene.screen.mosaics.forEachIndexed { index, mosaic ->
            if (mosaic.transitionProgress <= 0.01) return@forEachIndexed
            val rect = OverlayGeometry.overlayRect(content, mosaic.sourceRect)
            if (rectContains(rect, point, tolerance)) return Hit.Mosaic(mosaic.id, index)
        }
        // Screen card: test the projected quad so 3D tilt hits correctly, with slack via bounds.
        val quad = scene.screen.projectedQuad
        if (quad.contains(point) || rectContains(quad.bounds, point, tolerance)) {
            return Hit.Screen
        }
        return Hit.Empty
    }

    /** A sticker's drawn box in canvas space, supplied by the renderer-aware caller. */
    data class StickerBox(
        val center: CompositionPoint,
        val width: Double,
        val height: Double,
        val rotationRadians: Double,
    )

    /**
     * Snap a dragged value to the nearest guide within [tolerance]; returns the snapped value
     * and whether a snap engaged (for haptics + reference lines). Guides are canvas-space lines.
     */
    fun snap(value: Double, guides: List<Double>, tolerance: Double): SnapResult {
        var best: Double? = null
        var bestDist = tolerance
        for (guide in guides) {
            val dist = abs(value - guide)
            if (dist <= bestDist) {
                bestDist = dist
                best = guide
            }
        }
        return if (best != null) SnapResult(best, true) else SnapResult(value, false)
    }

    data class SnapResult(val value: Double, val snapped: Boolean)

    /** Nearest rotation snap to a right angle (±90°), mirroring upstream sticker rotation handles. */
    fun snapRotationRadians(radians: Double, toleranceRadians: Double): SnapResult {
        val quarter = kotlin.math.PI / 2
        val nearest = kotlin.math.round(radians / quarter) * quarter
        return if (abs(radians - nearest) <= toleranceRadians) {
            SnapResult(nearest, true)
        } else {
            SnapResult(radians, false)
        }
    }
}