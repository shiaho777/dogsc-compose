package dev.dogsc.app.ui

import androidx.compose.ui.Modifier

/**
 * Keeps horizontal drags on this element from being claimed by platform edge gestures
 * (for example Android's back swipe), so sliders near the screen edge stay draggable.
 */
expect fun Modifier.excludeSystemGestures(): Modifier
