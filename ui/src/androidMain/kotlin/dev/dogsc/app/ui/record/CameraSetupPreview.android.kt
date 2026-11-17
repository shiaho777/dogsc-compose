package dev.dogsc.app.ui.record

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
actual fun CameraSetupPreview(lensFacing: Int, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var previewUseCase by remember { mutableStateOf<Preview?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }.also { previewView = it }
        },
        update = {
            it.scaleX = if (lensFacing == 1) -1f else 1f
            previewView = it
        },
    )

    LaunchedEffect(lensFacing, previewView) {
        val view = previewView ?: return@LaunchedEffect
        val cameraProvider = runCatching { awaitCameraProvider(context) }.getOrNull()
            ?: return@LaunchedEffect
        provider = cameraProvider
        runCatching {
            previewUseCase?.let { runCatching { cameraProvider.unbind(it) } }
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val selector = if (lensFacing == 1) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            runCatching { cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview) }
                .recoverCatching {
                    val fallback = if (lensFacing == 1) {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    }
                    cameraProvider.bindToLifecycle(lifecycleOwner, fallback, preview)
                }.getOrThrow()
            previewUseCase = preview
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            previewUseCase?.let { runCatching { provider?.unbind(it) } }
            previewUseCase = null
            provider = null
        }
    }
}

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { continuation.resume(it) }
                    .onFailure { continuation.resumeWithException(it) }
            },
            ContextCompat.getMainExecutor(context),
        )
        continuation.invokeOnCancellation { future.cancel(true) }
    }
