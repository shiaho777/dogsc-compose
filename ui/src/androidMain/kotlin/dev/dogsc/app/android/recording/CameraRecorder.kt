package dev.dogsc.app.android.recording

import android.content.Context
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

class CameraRecorder(
    private val context: Context,
    private val lensFacing: Int,
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

    fun start(onReady: (Boolean) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.fromOrderedList(
                            listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                        )
                    )
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                videoCapture = capture
                val selector = if (lensFacing == 1) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                lifecycleOwner.start()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, capture)
                val options = FileOutputOptions.Builder(outputFile).build()
                val pending = capture.output.prepareRecording(context, options)
                recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            started = true
                            onReady(true)
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) error = "摄像头录制失败：${event.error}"
                            finalized.countDown()
                        }
                        else -> Unit
                    }
                }
            } catch (throwable: Throwable) {
                error = throwable.message ?: "无法启动摄像头"
                onReady(false)
                finalized.countDown()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        runCatching { recording?.stop() }
        recording = null
        runCatching { finalized.await(4, TimeUnit.SECONDS) }
        runCatching { provider?.unbindAll() }
        lifecycleOwner.stop()
        provider = null
        videoCapture = null
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
