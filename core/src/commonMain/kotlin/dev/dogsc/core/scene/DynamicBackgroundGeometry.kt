package dev.dogsc.core.scene

import dev.dogsc.core.model.DynamicBackgroundPreset
import kotlin.math.cos
import kotlin.math.sin

/**
 * Single source of truth for dynamic-background motion. Both the Compose preview and the
 * export renderer sample these pure functions, so drifting gradients, starfield dots and
 * glow orbs land on identical pixels at any time `t`. Previously the preview drew 90 stars /
 * 6 drifting orbs while export drew 100 stars / a static radial glow — this removes that gap.
 *
 * All outputs are normalized (0..1) fractions of the canvas; callers multiply by pixel size.
 */
object DynamicBackgroundGeometry {
    /** Gradient stop colors (0xRRGGBB) per preset, shared by both backends. */
    fun gradientColors(preset: DynamicBackgroundPreset): List<Int> = when (preset) {
        DynamicBackgroundPreset.CYBER_DRIFT_GRID -> listOf(0x1A1206, 0x3D2A0C, 0x0B0B10)
        DynamicBackgroundPreset.STARFIELD_DOTS -> listOf(0x0A0C14, 0x1A1E2C, 0x07080C)
        DynamicBackgroundPreset.AURORA_FLUID -> listOf(0x0A1B33, 0x2C5F8A, 0xE7EEF5)
    }

    /** Normalized gradient drift offset at time [t]. */
    fun drift(t: Double): Pair<Double, Double> {
        val phase = t.toFloat()
        return (sin(phase * 0.18f) * 0.05f).toDouble() to (cos(phase * 0.14f) * 0.04f).toDouble()
    }

    data class Star(val x: Double, val y: Double, val radius: Double, val alpha: Double)

    /** Starfield dot count shared by both backends. */
    const val StarCount = 100

    /**
     * Starfield dots at time [t]. [radiusScale] converts the authored radius (canvas short-edge
     * relative) into the caller's pixel units; positions are normalized 0..1.
     */
    fun stars(t: Double, radiusScale: Double): List<Star> {
        val phase = t.toFloat()
        return (0 until StarCount).map { i ->
            val x = (((i * 73 + phase * 9) % 997) / 997f).toDouble()
            val y = (((i * 151 + phase * 4) % 991) / 991f).toDouble()
            val radius = (0.7f + (i % 3) * 0.6f) * radiusScale.toFloat()
            val alpha = 70.0 / 255.0
            Star(x, y, radius.toDouble(), alpha)
        }
    }

    data class Orb(val x: Double, val y: Double, val radius: Double, val alpha: Double)

    /** Glow-orb count for non-starfield presets. */
    const val OrbCount = 6

    /**
     * Drifting glow orbs at time [t] for CYBER_DRIFT_GRID / AURORA_FLUID. Positions normalized
     * 0..1; [radiusScale] converts the short-edge-relative radius into caller pixel units.
     */
    fun orbs(t: Double, radiusScale: Double): List<Orb> {
        val (driftX, driftY) = drift(t)
        return (0 until OrbCount).map { i ->
            val x = (((i * 211) % 100) / 100f).toDouble() + driftX * (i + 1) * 0.3f
            val y = (((i * 137) % 100) / 100f).toDouble() + driftY * (i + 1) * 0.25f
            val radius = (0.18f + i * 0.05f) * radiusScale.toFloat()
            Orb(x, y, radius.toDouble(), 0.035)
        }
    }
}