package dev.dogsc.app.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dogsc.core.model.CompositionPoint

/**
 * Shared drag feedback between [CanvasManipulationLayer] (producer) and the overlay (consumer):
 * active drag kind, snapped guide lines, and a live value capsule. Mirrors upstream's
 * EditorCanvasDragFeedback (dashed reference lines + numeric capsule) adapted for touch.
 */
class CanvasDragFeedback {
    var active by mutableStateOf(false)
        internal set
    var label by mutableStateOf("")
        internal set
    /** Canvas-space guide lines to draw while snapping (vertical x / horizontal y). */
    var verticalGuides by mutableStateOf<List<Double>>(emptyList())
        internal set
    var horizontalGuides by mutableStateOf<List<Double>>(emptyList())
        internal set
    /** Capsule anchor in canvas space (near the dragged element). */
    var capsuleAnchor by mutableStateOf<CompositionPoint?>(null)
        internal set
    var snapped by mutableStateOf(false)
        internal set

    fun clear() {
        active = false
        label = ""
        verticalGuides = emptyList()
        horizontalGuides = emptyList()
        capsuleAnchor = null
        snapped = false
    }
}