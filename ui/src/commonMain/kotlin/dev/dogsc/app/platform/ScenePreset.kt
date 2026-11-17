package dev.dogsc.app.platform

import dev.dogsc.core.model.CameraStyle
import dev.dogsc.core.model.CanvasStyle
import dev.dogsc.core.model.CursorStyle
import dev.dogsc.core.model.MotionStyle
import dev.dogsc.core.model.OpeningSequence
import kotlinx.serialization.Serializable

/** Reusable appearance snapshot. Timeline, media and recording edits stay out. */
@Serializable
data class ScenePreset(
    val id: String,
    val name: String,
    val canvas: CanvasStyle,
    val camera: CameraStyle,
    val cursorStyle: CursorStyle,
    val openingSequence: OpeningSequence,
    val motion: MotionStyle,
)
