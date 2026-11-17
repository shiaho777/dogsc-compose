package dev.dogsc.app.ui

import androidx.compose.runtime.Composable

/** Invokes [onBackground] whenever the host app stops being visible, so pending edits can be flushed. */
@Composable
expect fun OnAppBackground(onBackground: () -> Unit)

/** Invokes [onForeground] when the app becomes visible again, e.g. after granting a system permission. */
@Composable
expect fun OnAppForeground(onForeground: () -> Unit)
