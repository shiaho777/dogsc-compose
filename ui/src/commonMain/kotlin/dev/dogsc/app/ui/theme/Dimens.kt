package dev.dogsc.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing / radius / control-height / shadow tokens, ported from upstream
 * `EditorDesignTokens`. Author new UI against these instead of raw dp literals so
 * the workspace keeps one rhythm. Values mirror upstream (14/16/8/6 spacing,
 * 8–20 radii, 30/32 control heights) with touch targets floored at 48dp.
 */
object DogScDimens {
    // Spacing scale (upstream 14/16/8/6 plus a 4dp micro step).
    val SpaceXXS: Dp = 4.dp
    val SpaceXS: Dp = 6.dp
    val SpaceS: Dp = 8.dp
    val SpaceM: Dp = 14.dp
    val SpaceL: Dp = 16.dp
    val SpaceXL: Dp = 24.dp

    // Corner radii (upstream 8/10/12/14/18/20).
    val RadiusXS: Dp = 8.dp
    val RadiusS: Dp = 10.dp
    val RadiusM: Dp = 12.dp
    val RadiusL: Dp = 14.dp
    val RadiusXL: Dp = 18.dp
    val RadiusXXL: Dp = 20.dp

    // Control heights (upstream 30/32); touch targets never below 48dp.
    val ControlS: Dp = 30.dp
    val ControlM: Dp = 32.dp
    val TouchTarget: Dp = 48.dp

    // Three-layer shadow elevations (upstream hairline / card / float).
    val ShadowHairline: Dp = 2.dp
    val ShadowCard: Dp = 10.dp
    val ShadowFloat: Dp = 22.dp

    // Interaction washes (upstream hover .07 / press .12).
    const val HoverWashAlpha = 0.07f
    const val PressWashAlpha = 0.12f
    const val PressScale = 0.97f

    // Canvas direct-manipulation affordances (touch-enlarged from upstream 9px).
    val SnapTolerance: Dp = 24.dp
    val HandleSize: Dp = 28.dp
}

/**
 * One color per track, carried consistently across canvas, timeline and inspector
 * (upstream `EditorInspectorPalette`: lime / amber / coral / teal / rose).
 * These are the saturated identity hues; [DogScColors] keeps the soft clip fills.
 */
object TrackPalette {
    /** Auto/manual zoom — lime B5E655. */
    val Zoom = Color(0xFFB5E655)

    /** Focus / spotlight — amber F7C548. */
    val Focus = Color(0xFFF7C548)

    /** Stickers — coral FF8A5C. */
    val Sticker = Color(0xFFFF8A5C)

    /** Camera motion — teal 45CBB8. */
    val Camera = Color(0xFF45CBB8)

    /** Mosaic / redaction — rose F27E9F. */
    val Mosaic = Color(0xFFF27E9F)

    /** Screen 3D / primary — graphite ink, adapts to theme. */
    val Screen: Color get() = DogScColors.PrimaryText
}