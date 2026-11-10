package dev.dogsc.app.ui.record

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Live camera framing for the capture setup screen. Android binds CameraX PreviewView. */
@Composable
expect fun CameraSetupPreview(lensFacing: Int, modifier: Modifier = Modifier)
