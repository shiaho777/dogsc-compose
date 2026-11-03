package dev.dogsc.app.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.dogsc.app.android.recording.AacAudioRecorder
import dev.dogsc.app.android.recording.AudioSourceKind
import dev.dogsc.app.android.recording.CameraRecorder
import dev.dogsc.app.android.recording.ScreenRecorder
import dev.dogsc.app.platform.RecorderUiState
import dev.dogsc.app.platform.RecordingOptions
import dev.dogsc.core.model.CameraLayoutMode
import dev.dogsc.core.model.CameraMotionClip
import dev.dogsc.core.model.CameraMotionState
import dev.dogsc.core.model.CaptureConfiguration
import dev.dogsc.core.model.CaptureSource
import dev.dogsc.core.model.ProjectMediaManifest
import dev.dogsc.core.model.ProjectMediaReference
import dev.dogsc.core.model.RecorderProject
import dev.dogsc.core.model.RecordingMarker
import dev.dogsc.core.model.SourceSequence
import dev.dogsc.core.model.TimelineMap
import dev.dogsc.core.model.TransitionTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class AndroidRecorderManager(
    private val activity: ComponentActivity,
    private val repository: AndroidProjectRepository,
) {
    private val mutableState = MutableStateFlow(RecorderUiState())
    val state: StateFlow<RecorderUiState> = mutableState.asStateFlow()
    private var pendingOptions: RecordingOptions? = null
        get() = field ?: pendingOptionsStatic
    private var coordinator: RecordingCoordinator? = null
    private var projection: MediaProjection? = null
    private var projectionData: Intent? = null
    private var projectionResultCode: Int = Activity.RESULT_CANCELED

    var requestPermissions: ((Array<String>) -> Unit)? = null
    var requestProjection: ((Intent) -> Unit)? = null

    init {
        instance = this
    }

    fun start(options: RecordingOptions) {
        Log.d("DogScRecording", "start requested $options")
        if (mutableState.value.isActive) return
        pendingOptions = options
        pendingOptionsStatic = options
        Log.d("DogScRecording", "permissions required for $options")
        val required = buildList {
            if (options.microphone || options.systemAudio) add(Manifest.permission.RECORD_AUDIO)
            if (options.camera) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (required.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, required.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        beginProjectionRequest()
    }

    fun onPermissionsResult(result: Map<String, Boolean>) {
        Log.d("DogScRecording", "permissions result=$result pending=${pendingOptions != null}")
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            fail("需要录音或摄像头权限才能开始录制")
            return
        }
        beginProjectionRequest()
    }

    private fun beginProjectionRequest() {
        val manager = activity.getSystemService(MediaProjectionManager::class.java)
        Log.d("DogScRecording", "begin projection request")
        runCatching {
            activity.startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
        }.onFailure { fail("无法请求屏幕录制权限") }
    }

    fun onProjectionResult(resultCode: Int, data: Intent?) {
        Log.d("DogScRecording", "projection result code=$resultCode data=${data != null}")
        if (resultCode != Activity.RESULT_OK || data == null) {
            fail("未授予屏幕录制权限")
            return
        }
        projectionResultCode = resultCode
        projectionData = data
        val options = pendingOptions ?: return
        startForegroundService()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val manager = activity.getSystemService(MediaProjectionManager::class.java)
            projection = runCatching { manager.getMediaProjection(resultCode, data) }
                .onFailure { Log.e("DogScRecording", "getMediaProjection failed", it) }
                .getOrNull()
            if (projection == null) {
                fail("无法启动屏幕捕获")
                return@postDelayed
            }
            mutableState.value = RecorderUiState(
                isActive = true,
                isRecording = true,
                microphoneEnabled = options.microphone,
                cameraEnabled = options.camera,
                systemAudioEnabled = options.systemAudio,
                cameraLensFacing = options.cameraLensFacing,
            )
            val metrics = activity.resources.displayMetrics
        val bounds = activity.windowManager.currentWindowMetrics.bounds
        val width = (bounds.width() / 2 * 2).coerceAtLeast(2)
        val height = (bounds.height() / 2 * 2).coerceAtLeast(2)
            val newCoordinator = RecordingCoordinator(
                context = activity,
                repository = repository,
                projection = projection!!,
                options = options,
                width = width,
                height = height,
                densityDpi = metrics.densityDpi,
                onState = { update -> mutableState.value = update },
                onCompleted = { projectId ->
                    mutableState.value = mutableState.value.copy(
                        isActive = false,
                        isRecording = false,
                        isFinishing = false,
                        completedProjectId = projectId,
                    )
                    coordinator = null
                    projection?.stop()
                    projection = null
                    stopForegroundService()
                    bringAppForward()
                },
                onError = { message ->
                    Log.e("DogScRecording", "coordinator error: $message")
                    mutableState.value = mutableState.value.copy(error = message)
                },
            )
            coordinator = newCoordinator
            newCoordinator.start()
            activity.moveTaskToBack(true)
        }, 350)
    }

    fun stop() {
        if (!mutableState.value.isActive) return
        mutableState.value = mutableState.value.copy(isFinishing = true)
        coordinator?.stop()
    }

    fun pause() {
        coordinator?.pause()
    }

    fun resume() {
        coordinator?.resume()
    }

    fun addMarker() {
        coordinator?.addMarker()
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        coordinator?.setMicrophoneEnabled(enabled)
        mutableState.value = mutableState.value.copy(microphoneEnabled = enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        coordinator?.setCameraEnabled(enabled)
        mutableState.value = mutableState.value.copy(cameraEnabled = enabled)
    }

    fun flipCamera() {
        if (mutableState.value.isActive) {
            mutableState.value = mutableState.value.copy(error = "录制中暂不支持切换镜头")
        }
    }

    fun discard() {
        coordinator?.discard()
        coordinator = null
        projection?.stop()
        projection = null
        mutableState.value = RecorderUiState()
        stopForegroundService()
    }

    fun updateNotification(elapsed: Double, paused: Boolean, markers: Int) {
        RecordingService.update(activity, elapsed, paused, markers)
    }

    private fun startForegroundService() {
        val intent = Intent(activity, RecordingService::class.java).setAction(RecordingService.ACTION_START)
        ContextCompat.startForegroundService(activity, intent)
    }

    private fun stopForegroundService() {
        activity.startService(Intent(activity, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }

    private fun bringAppForward() {
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent != null) activity.startActivity(intent)
    }

    private fun fail(message: String) {
        mutableState.value = mutableState.value.copy(error = message, isActive = false)
        pendingOptions = null
        stopForegroundService()
    }

    companion object {
        const val REQUEST_PROJECTION = 9001
        const val REQUEST_PERMISSIONS = 9002
        @Volatile var instance: AndroidRecorderManager? = null
        @Volatile private var pendingOptionsStatic: RecordingOptions? = null
    }
}

internal class RecordingCoordinator(
    private val context: android.content.Context,
    private val repository: AndroidProjectRepository,
    private val projection: MediaProjection,
    private val options: RecordingOptions,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val onState: (RecorderUiState) -> Unit,
    private val onCompleted: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val projectId = UUID.randomUUID().toString()
    private val projectDir = repository.projectDirectory(projectId)
    private val mediaDir = File(projectDir, "media").apply { mkdirs() }
    private val screenFile = File(mediaDir, "screen.mp4")
    private val cameraFile = File(mediaDir, "camera.mp4")
    private val micFile = File(mediaDir, "microphone.m4a")
    private val systemFile = File(mediaDir, "system.m4a")
    private val pointerFile = File(mediaDir, "pointer.jsonl")
    private var screenRecorder: ScreenRecorder? = null
    private var micRecorder: AacAudioRecorder? = null
    private var systemRecorder: AacAudioRecorder? = null
    private var cameraRecorder: CameraRecorder? = null
    private var overlay: RecordingOverlay? = null
    private var timerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val startedAt = SystemClock.elapsedRealtime()
    private var pausedAt: Long? = null
    private var pausedTotalMs = 0L
    private val pauseIntervals = mutableListOf<Pair<Double, Double>>()
    private val markers = mutableListOf<RecordingMarker>()
    private val cameraToggles = mutableListOf<Pair<Double, Boolean>>()
    private var cameraRunning = false
    private var stopping = false
    private var rawDuration = 0.0

    fun start() {
        runCatching {
            screenRecorder = ScreenRecorder(projection, width, height, densityDpi, options.frameRate, screenFile).also { it.start() }
        }.onFailure {
            onError("无法启动屏幕录制：${it.message}")
            return
        }
        if (options.microphone) {
            micRecorder = AacAudioRecorder(context, AudioSourceKind.MICROPHONE, projection, micFile).also {
                if (!it.start()) {
                    onError("麦克风启动失败")
                    micRecorder = null
                }
            }
        }
        if (options.systemAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemRecorder = AacAudioRecorder(context, AudioSourceKind.SYSTEM, projection, systemFile).also {
                if (!it.start()) {
                    onError("系统声音启动失败")
                    systemRecorder = null
                }
            }
        }
        if (options.camera) {
            cameraRecorder = CameraRecorder(context, options.cameraLensFacing, cameraFile)
            cameraRecorder?.start { ready ->
                cameraRunning = ready
                if (!ready) onError("摄像头启动失败")
            }
        }
        if (options.touchCapture) {
            TouchRecorder.start(pointerFile, width, height)
        }
        overlay = RecordingOverlay(context, this).also { it.show() }
        timerJob = scope.launch {
            while (true) {
                delay(250)
                val elapsed = elapsedSeconds()
                overlay?.updateTime(elapsed)
                onState(
                    RecorderUiState(
                        isActive = true,
                        isRecording = true,
                        isPaused = pausedAt != null,
                        elapsedSeconds = elapsed,
                        markerCount = markers.size,
                        microphoneEnabled = micRecorder != null,
                        cameraEnabled = cameraRunning,
                        systemAudioEnabled = systemRecorder != null,
                        cameraLensFacing = options.cameraLensFacing,
                    )
                )
                AndroidRecorderManager.instance?.updateNotification(elapsed, pausedAt != null, markers.size)
            }
        }
    }

    fun pause() {
        if (pausedAt != null) return
        pausedAt = SystemClock.elapsedRealtime()
        overlay?.setPaused(true)
        AndroidRecorderManager.instance?.updateNotification(elapsedSeconds(), true, markers.size)
    }

    fun resume() {
        val start = pausedAt ?: return
        val now = SystemClock.elapsedRealtime()
        pausedTotalMs += now - start
        pauseIntervals += rawElapsedSeconds(start) to rawElapsedSeconds(now)
        pausedAt = null
        overlay?.setPaused(false)
        AndroidRecorderManager.instance?.updateNotification(elapsedSeconds(), false, markers.size)
    }

    fun addMarker() {
        val raw = rawElapsedSeconds(SystemClock.elapsedRealtime())
        markers += RecordingMarker(sourceTime = raw, number = markers.size + 1)
        overlay?.flashMarker()
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        micRecorder?.muted = !enabled
    }

    fun setCameraEnabled(enabled: Boolean) {
        cameraToggles += rawElapsedSeconds(SystemClock.elapsedRealtime()) to enabled
    }

    fun stop() {
        if (stopping) return
        stopping = true
        if (pausedAt != null) resume()
        timerJob?.cancel()
        overlay?.hide()
        overlay = null
        runCatching { screenRecorder?.stop() }
        runCatching { micRecorder?.stop() }
        runCatching { systemRecorder?.stop() }
        runCatching { cameraRecorder?.stop() }
        TouchRecorder.stop()
        val probedDuration = runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(screenFile.absolutePath)
                (retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrDefault(0.0)
        val encodedDuration = screenRecorder?.lastPresentationTimeUs?.div(1_000_000.0) ?: 0.0
        rawDuration = when {
            probedDuration > 0.1 -> probedDuration
            encodedDuration > 0.1 && encodedDuration < 6 * 60 * 60 -> encodedDuration
            else -> elapsedSeconds()
        }
        screenRecorder = null
        micRecorder = null
        systemRecorder = null
        cameraRecorder = null
        scope.launch(Dispatchers.IO) { finalizeProject() }
    }

    fun isPaused(): Boolean = pausedAt != null
    fun microphoneEnabled(): Boolean = micRecorder != null && micRecorder?.muted == false
    fun cameraEnabled(): Boolean = cameraRunning
    fun elapsedSecondsPublic(): Double = elapsedSeconds()

    fun discard() {
        stopping = true
        timerJob?.cancel()
        overlay?.hide()
        overlay = null
        runCatching { screenRecorder?.stop() }
        runCatching { micRecorder?.stop() }
        runCatching { systemRecorder?.stop() }
        runCatching { cameraRecorder?.stop() }
        TouchRecorder.stop()
        projectDir.deleteRecursively()
        onState(RecorderUiState())
    }

    private suspend fun finalizeProject() {
        try {
            val segments = buildSegments(rawDuration)
            val sourceSequence = if (segments.size == 1 && segments[0].sourceStart < 0.001 &&
                kotlin.math.abs(segments[0].sourceDuration - rawDuration) < 0.05
            ) {
                SourceSequence.FullRecording
            } else {
                SourceSequence.Edited(segments)
            }
            val timelineMap = TimelineMap(sourceSequence, max(rawDuration, 0.1))
            val cameraClips = if (cameraRunning) buildCameraClips(timelineMap, projectId) else emptyList()
            val media = ProjectMediaManifest(
                screen = ProjectMediaReference(
                    relativePath = "media/screen.mp4",
                    durationSeconds = rawDuration,
                    pixelWidth = width,
                    pixelHeight = height,
                ),
                camera = cameraFile.takeIf { it.exists() && it.length() > 0 }?.let {
                    ProjectMediaReference(relativePath = "media/camera.mp4", durationSeconds = rawDuration)
                },
                microphone = micFile.takeIf { it.exists() && it.length() > 0 }?.let {
                    ProjectMediaReference(relativePath = "media/microphone.m4a")
                },
                systemAudio = systemFile.takeIf { it.exists() && it.length() > 0 }?.let {
                    ProjectMediaReference(relativePath = "media/system.m4a")
                },
                pointerEvents = pointerFile.takeIf { it.exists() && it.length() > 0 }?.let {
                    ProjectMediaReference(relativePath = "media/pointer.jsonl")
                },
            )
            val project = RecorderProject(
                id = projectId,
                title = "录制 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date()),
                createdAtEpochMs = System.currentTimeMillis(),
                capture = CaptureConfiguration(
                    source = CaptureSource.DISPLAY,
                    captureFrameRate = if (options.frameRate == 30) dev.dogsc.core.model.OutputFrameRate.FPS_30 else dev.dogsc.core.model.OutputFrameRate.FPS_60,
                    recordsSystemAudio = media.systemAudio != null,
                    recordsMicrophone = media.microphone != null,
                    recordsCamera = media.camera != null,
                    recordsTouch = media.pointerEvents != null,
                ),
                audio = dev.dogsc.core.model.AudioStyle(systemVolume = 1.0, microphoneVolume = 1.0),
                timeline = dev.dogsc.core.model.ProjectTimeline(
                    sourceSequence = sourceSequence,
                    cameraMotionClips = cameraClips,
                ),
                media = media,
                recordingMarkers = markers,
            )
            val saved = repository.saveProject(project)
            if (saved) onCompleted(projectId) else onError("录制已结束，但项目保存失败")
        } catch (error: Throwable) {
            Log.e("DogScRecording", "finalize failed", error)
            onError("录制已结束，但项目保存失败：${error.message}")
        }
    }

    private fun buildSegments(duration: Double): List<dev.dogsc.core.model.RecordingSegment> {
        if (duration <= 0.01) return emptyList()
        val merged = mutableListOf<Pair<Double, Double>>()
        for (interval in pauseIntervals.sortedBy { it.first }) {
            val start = interval.first.coerceIn(0.0, duration)
            val end = interval.second.coerceIn(0.0, duration)
            if (end <= start) continue
            val last = merged.lastOrNull()
            if (last != null && start <= last.second + 0.001) {
                merged[merged.lastIndex] = last.first to max(last.second, end)
            } else {
                merged += start to end
            }
        }
        val segments = mutableListOf<dev.dogsc.core.model.RecordingSegment>()
        var cursor = 0.0
        for ((start, end) in merged) {
            if (start - cursor > 0.08) {
                segments += dev.dogsc.core.model.RecordingSegment(sourceStart = cursor, sourceDuration = start - cursor)
            }
            cursor = max(cursor, end)
        }
        if (duration - cursor > 0.08) {
            segments += dev.dogsc.core.model.RecordingSegment(sourceStart = cursor, sourceDuration = duration - cursor)
        }
        if (segments.isEmpty()) {
            segments += dev.dogsc.core.model.RecordingSegment(sourceStart = 0.0, sourceDuration = duration)
        }
        return segments
    }

    private fun buildCameraClips(timelineMap: TimelineMap, projectId: String): List<CameraMotionClip> {
        if (cameraToggles.isEmpty()) return emptyList()
        val events = cameraToggles.mapNotNull { (raw, enabled) ->
            timelineMap.outputTimeForSource(raw)?.let { it to enabled }
        }.sortedBy { it.first }
        if (events.isEmpty()) return emptyList()
        val outputDuration = timelineMap.outputDuration
        return events.mapIndexedNotNull { index, (start, enabled) ->
            val end = events.getOrNull(index + 1)?.first ?: outputDuration
            if (end - start <= 0.01) return@mapIndexedNotNull null
            CameraMotionClip(
                timing = TransitionTiming(
                    startTime = start,
                    duration = end - start,
                    leadInDuration = 0.0,
                    returnDuration = 0.0,
                ),
                target = CameraMotionState(
                    layout = CameraLayoutMode.Shape(dev.dogsc.core.model.CameraShape.SQUARE),
                    position = dev.dogsc.core.model.NormalizedPoint(0.96, 0.47),
                    size = 0.29,
                    roundness = 0.5,
                    opacity = if (enabled) 1.0 else 0.0,
                ),
            )
        }
    }

    private fun elapsedSeconds(): Double {
        val now = SystemClock.elapsedRealtime()
        val pausedNow = pausedAt?.let { now - it } ?: 0L
        return max((now - startedAt - pausedTotalMs - pausedNow).toDouble() / 1000.0, 0.0)
    }

    private fun rawElapsedSeconds(now: Long): Double =
        max((now - startedAt).toDouble() / 1000.0, 0.0)
}
