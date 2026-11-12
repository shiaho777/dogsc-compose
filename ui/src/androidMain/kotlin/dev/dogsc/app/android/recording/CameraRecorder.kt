package dev.dogsc.app.android.recording

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CameraRecorder(
    private val context: Context,
    private val lensFacing: Int,
    private val quality: String,
    private val outputFile: File,
) {
    private val lifecycleOwner = RecorderLifecycleOwner()
    private var provider: ProcessCameraProvider? = null
    private var recording: Recording? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private val finalized = CountDownLatch(1)
    @Volatile var error: String? = null
        private set
    @Volatile var started = false
        private set
    @Volatile var startedAtElapsedMs: Long = 0L
        private set
    @Volatile private var released = false

    fun start(onReady: (Boolean) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                if (released) {
                    runCatching { cameraProvider.unbindAll() }
                    finalized.countDown()
                    return@addListener
                }
                provider = cameraProvider
                val preferred = when (quality) {
                    "uhd" -> Quality.UHD
                    "fhd" -> Quality.FHD
                    "hd" -> Quality.HD
                    "sd" -> Quality.SD
                    else -> null
                }
                val qualities = when (preferred) {
                    Quality.UHD -> listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                    Quality.FHD -> listOf(Quality.FHD, Quality.HD, Quality.SD)
                    Quality.HD -> listOf(Quality.HD, Quality.SD)
                    Quality.SD -> listOf(Quality.SD)
                    else -> listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.fromOrderedList(
                            qualities,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                        )
                    )
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                videoCapture = capture
                val requested = if (lensFacing == 1) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                lifecycleOwner.start()
                runCatching {
                    cameraProvider.bindToLifecycle(lifecycleOwner, requested, capture)
                }.onFailure {
                    // Tablets/emulators may expose only one lens. Recording a
                    // usable camera is better than dropping the track.
                    val fallback = if (lensFacing == 1) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    cameraProvider.bindToLifecycle(lifecycleOwner, fallback, capture)
                }
                if (released) {
                    runCatching { cameraProvider.unbindAll() }
                    lifecycleOwner.stop()
                    finalized.countDown()
                    return@addListener
                }
                val options = FileOutputOptions.Builder(outputFile).build()
                val pending = capture.output.prepareRecording(context, options)
                val startedRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            started = true
                            startedAtElapsedMs = SystemClock.elapsedRealtime()
                            onReady(true)
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) error = "摄像头录制失败：${event.error}"
                            finalized.countDown()
                        }
                        else -> Unit
                    }
                }
                recording = startedRecording
                if (released) {
                    runCatching { startedRecording.stop() }
                    runCatching { cameraProvider.unbindAll() }
                }
            } catch (throwable: Throwable) {
                error = throwable.message ?: "无法启动摄像头"
                onReady(false)
                finalized.countDown()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Requests the stop without blocking the main thread. */
    fun stop() {
        released = true
        runCatching { recording?.stop() }
        recording = null
        runCatching { provider?.unbindAll() }
        lifecycleOwner.stop()
        provider = null
        videoCapture = null
    }

    /**
     * CameraX delivers `VideoRecordEvent.Finalize` on the main executor. Await it from IO,
     * otherwise calling this on the main thread can only ever hit the timeout.
     */
    suspend fun awaitFinalized(timeoutMs: Long = 4_000) {
        withContext(Dispatchers.IO) {
            runCatching { finalized.await(timeoutMs, TimeUnit.MILLISECONDS) }
        }
    }

    private class RecorderLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun start() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
