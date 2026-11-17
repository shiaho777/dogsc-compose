package dev.dogsc.core.scene

import dev.dogsc.core.model.CompositionPoint
import dev.dogsc.core.model.CompositionRect
import dev.dogsc.core.model.NormalizedOverlayRect

/**
 * Single source of truth for overlay coordinate semantics.
 *
 * Mosaic/spotlight regions and focus centers are authored relative to the **screen content
 * rect** (the fitted, cropped screen card), NOT the full canvas. Both the Compose preview and
 * the export renderer must map through these helpers so a region lands on the same pixels in
 * preview and export. Historically the preview's spotlight/focus layers used full-canvas
 * coordinates while export used content coordinates — these functions remove that divergence.
 */
object OverlayGeometry {
    /** Maps a content-relative normalized overlay rect into canvas space. */
    fun overlayRect(content: CompositionRect, rect: NormalizedOverlayRect): CompositionRect =
        CompositionRect(
            x = content.x + rect.x * content.width,
            y = content.y + rect.y * content.height,
            width = rect.width * content.width,
            height = rect.height * content.height,
        )

    /** Maps a content-relative normalized center into canvas space. */
    fun focusCenter(content: CompositionRect, centerX: Double, centerY: Double): CompositionPoint =
        CompositionPoint(
            x = content.x + centerX * content.width,
            y = content.y + centerY * content.height,
        )

    /**
     * Focus clear-region half width in canvas pixels. [normalized] is the fraction returned by
     * `FocusEffect.clearHalfWidth(shortEdge)`; both renderers multiply it by the content width,
     * so radial and linear focus match between preview and export.
     */
    fun focusHalfWidth(content: CompositionRect, normalized: Double): Double =
        normalized * content.width

    /**
     * The focus clear region in canvas space: the area that stays sharp while everything
     * outside it is blurred/dimmed. Both backends derive their blur mask and feather gradient
     * from this single description so radial/linear focus match pixel-for-pixel.
     */
    data class FocusClearRegion(
        val center: CompositionPoint,
        /** Radial: clear circle radius. Linear: half-width of the clear band. */
        val halfWidth: Double,
        /** Feather band thickness beyond the clear edge (softness). */
        val feather: Double,
        val isLinear: Boolean,
        val angleDegrees: Double,
    )

    fun focusClearRegion(
        content: CompositionRect,
        effect: dev.dogsc.core.model.FocusEffect,
        centerX: Double,
        centerY: Double,
    ): FocusClearRegion {
        val shortEdge = kotlin.math.min(content.width, content.height)
        return FocusClearRegion(
            center = focusCenter(content, centerX, centerY),
            halfWidth = focusHalfWidth(content, effect.clearHalfWidth(shortEdge)),
            feather = effect.featherWidth(shortEdge) * (content.width / shortEdge),
            isLinear = effect.shape == dev.dogsc.core.model.FocusEffectShape.LINEAR,
            angleDegrees = effect.angleDegrees ?: 0.0,
        )
    }
}