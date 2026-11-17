package dev.dogsc.core.scene

/**
 * Single source of truth for renderer constants that must match between the Compose preview
 * and the export renderer. Historically these lived as magic numbers in each backend (shadow
 * alpha 90/80, blur downscale 12/18, preview Gaussian coefficient 0.22); centralizing them here
 * is the first step toward pixel-identical preview/export (stage 3).
 *
 * Values are authored against the export's 1080-short-edge canvas; callers scale by the same
 * `canonicalStyleScale` the scene geometry uses, so 1080p/1440p/4K stay compositionally identical.
 */
object RenderParams {
    /** Screen-card shadow: alpha multiplier on shadow.opacity (export BlurMaskFilter path). */
    const val ScreenShadowAlpha = 90.0

    /** Camera PiP shadow alpha multiplier. */
    const val CameraShadowAlpha = 80.0

    /** Sticker shadow alpha multiplier. */
    const val StickerShadowAlpha = 80.0

    /** Minimum shadow blur radius in canvas pixels so hairline shadows still soften. */
    const val MinShadowRadius = 2.0

    /** Mosaic blur downscale factor (export downsamples the screen frame by this). */
    const val MosaicBlurDivisor = 12

    /** Custom background blur downscale factor. */
    const val BackgroundBlurDivisor = 18

    /**
     * Preview Gaussian sigma coefficient relative to the mosaic block size. Tuned so the
     * preview's BlurEffect detail loss matches the export's downscale/upscale blur.
     */
    const val PreviewBlurSigmaFactor = 0.22

    /** Preview blur sigma clamp (canvas pixels). */
    const val PreviewBlurSigmaMin = 1.2
    const val PreviewBlurSigmaMax = 48.0

    /** Mosaic overlay alpha ceiling (235/255) shared by both backends. */
    const val MosaicAlphaCeiling = 235.0 / 255.0
}