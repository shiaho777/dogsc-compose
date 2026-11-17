package dev.dogsc.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** A normalized point with a top-left origin. */
@Serializable
data class NormalizedPoint(val x: Double = 0.5, val y: Double = 0.5) {
    fun constrained(safeInset: Double): NormalizedPoint {
        val inset = safeInset.coerceIn(0.0, 0.5)
        return NormalizedPoint(x.coerceIn(inset, 1 - inset), y.coerceIn(inset, 1 - inset))
    }

    companion object {
        val Center = NormalizedPoint(0.5, 0.5)
    }
}

@Serializable
data class CompositionPoint(val x: Double, val y: Double)

@Serializable
data class CompositionSize(val width: Double, val height: Double) {
    val shortEdge: Double get() = min(width, height)
    val aspectRatio: Double get() = max(width, 1e-6) / max(height, 1e-6)
}

@Serializable
data class CompositionRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    val midX: Double get() = x + width / 2
    val midY: Double get() = y + height / 2
    val right: Double get() = x + width
    val bottom: Double get() = y + height
}

/** A normalized crop rectangle measured from the source's top-left corner. */
@Serializable
data class NormalizedCrop(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = 1.0,
    val height: Double = 1.0,
) {
    val left: Double get() = x
    val top: Double get() = y
    val right: Double get() = 1 - x - width
    val bottom: Double get() = 1 - y - height

    fun clamped(minimumSize: Double = 0.02): NormalizedCrop {
        val minimum = minimumSize.coerceIn(0.001, 1.0)
        val safeX = x.coerceIn(0.0, 1 - minimum)
        val safeY = y.coerceIn(0.0, 1 - minimum)
        return NormalizedCrop(
            x = safeX,
            y = safeY,
            width = width.coerceIn(minimum, 1 - safeX),
            height = height.coerceIn(minimum, 1 - safeY),
        )
    }

    companion object {
        val Full = NormalizedCrop()

        fun fromEdges(
            left: Double,
            right: Double,
            top: Double,
            bottom: Double,
            minimumSize: Double = 0.02,
        ): NormalizedCrop {
            val minimum = minimumSize.coerceIn(0.001, 1.0)
            val safeLeft = left.coerceIn(0.0, 1 - minimum)
            val safeTop = top.coerceIn(0.0, 1 - minimum)
            val safeRight = right.coerceIn(0.0, 1 - safeLeft - minimum)
            val safeBottom = bottom.coerceIn(0.0, 1 - safeTop - minimum)
            return NormalizedCrop(
                x = safeLeft,
                y = safeTop,
                width = 1 - safeLeft - safeRight,
                height = 1 - safeTop - safeBottom,
            )
        }
    }
}

/** A normalized, top-left-origin rectangle authored directly on the preview. */
@Serializable
data class NormalizedOverlayRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    fun clamped(): NormalizedOverlayRect {
        val safeWidth = width.takeIf { it.isFinite() }?.coerceIn(0.01, 1.0) ?: 0.30
        val safeHeight = height.takeIf { it.isFinite() }?.coerceIn(0.01, 1.0) ?: 0.22
        return NormalizedOverlayRect(
            x = x.takeIf { it.isFinite() }?.coerceIn(0.0, 1 - safeWidth) ?: 0.35,
            y = y.takeIf { it.isFinite() }?.coerceIn(0.0, 1 - safeHeight) ?: 0.39,
            width = safeWidth,
            height = safeHeight,
        )
    }

    companion object {
        val Centered = NormalizedOverlayRect(0.35, 0.39, 0.30, 0.22)
    }
}

@Serializable
data class NormalizedRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    fun constrained(): NormalizedRect {
        val safeX = x.coerceIn(0.0, 1.0)
        val safeY = y.coerceIn(0.0, 1.0)
        return NormalizedRect(
            x = safeX,
            y = safeY,
            width = width.coerceIn(0.02, 1 - safeX),
            height = height.coerceIn(0.02, 1 - safeY),
        )
    }
}

/**
 * One canonical 24-bit sRGB color shared by persistence, preview and export.
 * Serialized as `#RRGGBB` so project files remain human-readable.
 */
@Serializable(with = HexColorSerializer::class)
data class HexColor(val rgb24: Int) {
    init {
        require(rgb24 in 0..0xFFFFFF) { "HexColor requires a 24-bit RGB value" }
    }

    val red: Double get() = ((rgb24 shr 16) and 0xFF) / 255.0
    val green: Double get() = ((rgb24 shr 8) and 0xFF) / 255.0
    val blue: Double get() = (rgb24 and 0xFF) / 255.0
    val alpha: Float get() = 1f

    val hexString: String get() = "#" + rgb24.toString(16).uppercase().padStart(6, '0')

    fun toArgb(alpha: Int = 255): Int =
        (alpha.coerceIn(0, 255) shl 24) or rgb24

    companion object {
        val White = HexColor(0xFFFFFF)
        val Black = HexColor(0x000000)
        val DefaultBackground = HexColor(0x2D3342)

        fun parse(source: String): HexColor? {
            val trimmed = source.trim()
            val digits = trimmed.removePrefix("#")
            if (digits.length != 6 || digits.any { it.digitToIntOrNull(16) == null }) return null
            return HexColor(digits.toInt(16))
        }

        fun fromComponents(r: Double, g: Double, b: Double): HexColor = HexColor(
            ((r.coerceIn(0.0, 1.0) * 255).roundToInt() shl 16) or
                ((g.coerceIn(0.0, 1.0) * 255).roundToInt() shl 8) or
                (b.coerceIn(0.0, 1.0) * 255).roundToInt()
        )
    }
}

object HexColorSerializer : KSerializer<HexColor> {
    override val descriptor = PrimitiveSerialDescriptor("HexColor", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: HexColor) {
        encoder.encodeString(value.hexString)
    }

    override fun deserialize(decoder: Decoder): HexColor {
        val source = decoder.decodeString()
        return HexColor.parse(source)
            ?: throw IllegalArgumentException("Expected #RRGGBB or RRGGBB sRGB color, got $source")
    }
}

/** A short, collision-resistant identifier stored as a plain string. */
fun randomId(): String {
    val bytes = ByteArray(16).also { Random.Default.nextBytes(it) }
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    return buildString {
        append(hex, 0, 8); append('-')
        append(hex, 8, 12); append('-')
        append(hex, 12, 16); append('-')
        append(hex, 16, 20); append('-')
        append(hex, 20, 32)
    }
}

fun Double.safeClamp(range: ClosedFloatingPointRange<Double>): Double =
    if (isFinite()) coerceIn(range) else range.start
