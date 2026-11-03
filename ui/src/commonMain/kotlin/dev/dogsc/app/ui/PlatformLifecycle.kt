package dev.dogsc.app.ui

import androidx.compose.runtime.Composable

/** Invokes [onBackground] whenever the host app stops being visible, so pending edits can be flushed. */
@Composable
expect fun OnAppBackground(onBackground: () -> Unit)
