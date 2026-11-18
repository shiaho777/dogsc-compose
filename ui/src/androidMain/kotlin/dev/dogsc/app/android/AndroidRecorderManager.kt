package dev.dogsc.app.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.StatFs
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
import dev.dogsc.app.ui.Loc
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
import kotlinx.coroutines.withContext
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
import kotlin.math.roundToInt

private fun capCaptureDimensions(width: Int, height: Int, limit: String): Pair<Int, Int> {
    if (limit == "native") return width to height
    val maxLong = if (limit == "4k") 3840 else 1920
    val maxShort = if (limit == "4k") 2160 else 1080
    val long = max(width, height)
    val short = min(width, height)
    if (long <= maxLong && short <= maxShort) return width to height
    val scale = min(maxLong.toDouble() / long, maxShort.toDouble() / short).coerceAtMost(1.0)
    fun even(value: Int) = (value / 2 * 2).coerceAtLeast(2)
    return even((width * scale).roundToInt()) to even((height * scale).roundToInt())
}

class AndroidRecorderManager(
    private val activity: ComponentActivity,
    private val repository: AndroidProjectRepository,
) {
    private val mutableState = MutableStateFlow(RecorderUiState())
    val state: StateFlow<RecorderUiState> = mutableState.asStateFlow()
    private var pendingOptions: RecordingOptions? = null
    @Volatile private var setupPending = false
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
        if (mutableState.value.isActive || setupPending) return
        setupPending = true
        // Recordings are written to the app's external files dir, which can live on a different
        // volume than filesDir (and is the volume the exporter will later need).
        val storageRoot = activity.getExternalFilesDir(null) ?: activity.filesDir
        val freeBytes = runCatching { StatFs(storageRoot.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (freeBytes < 256L * 1024 * 1024) {
            fail("存储空间不足，至少需要 256 MB")
            return
        }
        pendingOptions = options
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
        // Notification denial only hides the progress notification; it must not block capture.
        val denied = result.filterValues { !it }.keys - Manifest.permission.POST_NOTIFICATIONS
        if (denied.isNotEmpty()) {
            // "Don't ask again" denials never show a system dialog again; without a settings
            // escape hatch every Start press fails with the same toast forever.
            val permanentlyDenied = denied.any {
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }
            if (permanentlyDenied) {
                fail("录音或摄像头权限已被拒绝，请在系统设置中开启后重试")
                runCatching {
                    activity.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", activity.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }
            } else {
                fail("需要录音或摄像头权限才能开始录制")
            }
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
        if (mutableState.value.isActive) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            fail("未授予屏幕录制权限")
            return
        }
        projectionResultCode = resultCode
        projectionData = data
        val options = pendingOptions ?: return
        runCatching { startForegroundService(options) }.onFailure {
            Log.e("DogScRecording", "foreground service start failed", it)
            fail("无法启动录制服务")
            return
        }
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
                microphoneAvailable = options.microphone,
                cameraEnabled = options.camera,
                cameraAvailable = options.camera,
                systemAudioEnabled = options.systemAudio,
                cameraLensFacing = options.cameraLensFacing,
            )
            val metrics = activity.resources.displayMetrics
            val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.windowManager.currentWindowMetrics.bounds
            } else {
                // currentWindowMetrics is API 30; minSdk is 29.
                val displayMetrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay.getRealMetrics(displayMetrics)
                android.graphics.Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
            }
        val limited = capCaptureDimensions(
            (bounds.width() / 2 * 2).coerceAtLeast(2),
            (bounds.height() / 2 * 2).coerceAtLeast(2),
            options.captureResolutionLimit,
        )
        val captureMime = dev.dogsc.app.android.recording.CaptureEncoderCapabilities.mimeFor(options.captureCodec)
        val (width, height) = dev.dogsc.app.android.recording.CaptureEncoderCapabilities.clamp(
            captureMime,
            limited.first,
            limited.second,
        )
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
                    activeCoordinator = null
                    projection?.stop()
                    projection = null
                    stopForegroundService()
                    bringAppForward()
                },
                onError = { message ->
                    Log.e("DogScRecording", "coordinator error: $message")
                    mutableState.value = mutableState.value.copy(error = message)
                },
                onFatalError = { message ->
                    Log.e("DogScRecording", "fatal recording error: $message")
                    finishRecordingSession(message)
                },
                onCancelled = {
                    finishRecordingSession(null)
                },
            )
            coordinator = newCoordinator
            activeCoordinator = newCoordinator
            setupPending = false
            newCoordinator.start()
            // A fatal setup error clears the active state synchronously; do not background the
            // app after it has already reported the failure.
            if (mutableState.value.isActive) activity.moveTaskToBack(true)
        }, 350)
    }

    /**
     * After an Activity recreation this manager is fresh while the capture keeps running in
     * the companion-held coordinator. Commands must reach the live session, not the empty
     * local field, or stop/pause/toggles silently do nothing.
     */
    private fun liveCoordinator(): RecordingCoordinator? = coordinator ?: activeCoordinator

    private fun adoptLiveSessionIfNeeded() {
        if (coordinator == null && activeCoordinator != null && !mutableState.value.isActive) {
            mutableState.value = mutableState.value.copy(isActive = true, isRecording = true)
        }
    }

    fun stop() {
        adoptLiveSessionIfNeeded()
        if (!mutableState.value.isActive) return
        mutableState.value = mutableState.value.copy(isFinishing = true)
        liveCoordinator()?.stop()
    }

    fun pause() {
        liveCoordinator()?.pause()
    }

    fun resume() {
        liveCoordinator()?.resume()
    }

    fun addMarker() {
        liveCoordinator()?.addMarker()
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        liveCoordinator()?.setMicrophoneEnabled(enabled)
        mutableState.value = mutableState.value.copy(microphoneEnabled = enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        liveCoordinator()?.setCameraEnabled(enabled)
        mutableState.value = mutableState.value.copy(cameraEnabled = enabled)
    }

    /** Refreshes toggle state after the overlay changed the live coordinator directly. */
    fun syncRecordingToggles() {
        val live = liveCoordinator() ?: return
        mutableState.value = mutableState.value.copy(
            microphoneEnabled = live.microphoneEnabled(),
            cameraEnabled = live.cameraEnabled(),
        )
    }

    fun discard() {
        setupPending = false
        val active = coordinator
        coordinator = null
        activeCoordinator = null
        active?.discard(notifyManager = false)
        projection?.stop()
        projection = null
        mutableState.value = RecorderUiState()
        stopForegroundService()
    }

    /** Ends the capture session after an unrecoverable setup error or a pre-start abort. */
    private fun finishRecordingSession(error: String?) {
        setupPending = false
        coordinator = null
        activeCoordinator = null
        projection?.stop()
        projection = null
        stopForegroundService()
        mutableState.value = RecorderUiState(error = error)
        bringAppForward()
    }

    fun updateNotification(elapsed: Double, paused: Boolean, markers: Int) {
        RecordingService.update(activity, elapsed, paused, markers)
    }

    private fun startForegroundService(options: RecordingOptions) {
        val intent = Intent(activity, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_USES_MICROPHONE, options.microphone || options.systemAudio)
            .putExtra(RecordingService.EXTRA_USES_CAMERA, options.camera)
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
        setupPending = false
        mutableState.value = mutableState.value.copy(error = message, isActive = false)
        pendingOptions = null
        stopForegroundService()
    }

    companion object {
        const val REQUEST_PROJECTION = 9001
        const val REQUEST_PERMISSIONS = 9002
        @Volatile var instance: AndroidRecorderManager? = null

        /**
         * The live coordinator, independent of any Activity-bound manager. After an
         * activity recreation the fresh manager has empty state; notification actions
         * must still reach the running capture instead of stopping the foreground
         * service underneath it.
         */
        @Volatile internal var activeCoordinator: RecordingCoordinator? = null

        /** Project currently being written; the library must not remux its live `.part` files. */
        @Volatile var activeProjectId: String? = null
            internal set
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
    private val onFatalError: (String) -> Unit,
    private val onCancelled: () -> Unit,
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
    private var startedAt = SystemClock.elapsedRealtime()
    private var screenStartedAtMs = 0L
    private var cameraStartedAtMs = 0L
    private var microphoneStartedAtMs = 0L
    private var systemAudioStartedAtMs = 0L
    private var screenStarted = false
    private var resolvedCaptureMime: String = android.media.MediaFormat.MIMETYPE_VIDEO_AVC
    private var pausedAt: Long? = null
    private var pausedTotalMs = 0L
    private val pauseIntervals = mutableListOf<Pair<Double, Double>>()
    private val markers = mutableListOf<RecordingMarker>()
    private val cameraToggles = mutableListOf<Pair<Double, Boolean>>()
    private var cameraRunning = false
    private var cameraVisible = true
    private var stopping = false
    @Volatile private var discarded = false
    /** Set when a recorder error forced the session to stop early; reported after finalize. */
    @Volatile private var pendingCaptureError: String? = null
    private var cameraErrorReported = false
    private var rawDuration = 0.0
    private val projectTitle = Loc.t("录制") + " " +
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())

    fun start() {
        AndroidRecorderManager.activeProjectId = projectId
        // The journal must exist before the first fragment is written; otherwise a crash in the
        // first moments would leave an orphan media directory that the library cannot surface.
        // Run the save off the main thread: it includes backup rotation copies and used to
        // block the UI (ANR exposure on slow storage).
        scope.launch {
            val journaled = runCatching {
                withContext(Dispatchers.IO) { repository.saveProject(preliminaryProject()) }
            }.getOrDefault(false)
            if (!journaled) {
                abortWithError("无法创建录制项目，请检查存储空间")
                return@launch
            }
            beginCapture()
        }
    }

    private fun beginCapture() {
        if (stopping) return
        if (options.camera) {
            val recorder = CameraRecorder(context, options.cameraLensFacing, options.cameraQuality, cameraFile)
            cameraRecorder = recorder
            recorder.start { ready ->
                if (stopping) return@start
                cameraRunning = ready
                cameraStartedAtMs = recorder.startedAtElapsedMs
                if (!ready) onError("摄像头启动失败")
                // CameraX takes a moment to open the lens. Start the screen only once the camera
                // is actually recording so the two tracks share a zero point (no lip-sync offset).
                startScreenAndAudio()
            }
            // A camera that never reports must not block the screen recording.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!stopping) startScreenAndAudio()
            }, 8_000)
        } else {
            startScreenAndAudio()
        }
    }

    private fun startScreenAndAudio() {
        if (screenStarted || stopping) return
        screenStarted = true
        startedAt = SystemClock.elapsedRealtime()
        screenStartedAtMs = startedAt
        runCatching {
            screenRecorder = ScreenRecorder(
                projection,
                width,
                height,
                densityDpi,
                options.frameRate,
                screenFile,
                if (options.captureCodec == "hevc") android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
                else android.media.MediaFormat.MIMETYPE_VIDEO_AVC,
            ).also {
                resolvedCaptureMime = it.resolvedMime
                it.start()
            }
        }.onFailure {
            abortWithError("无法启动屏幕录制：${it.message}")
            return
        }
        if (options.microphone) {
            runCatching {
                AacAudioRecorder(context, AudioSourceKind.MICROPHONE, projection, micFile)
            }.onSuccess { recorder ->
                if (runCatching { recorder.start() }.getOrDefault(false)) {
                    micRecorder = recorder
                    microphoneStartedAtMs = recorder.startedAtElapsedMs
                } else {
                    recorder.stop()
                    onError("麦克风启动失败")
                }
            }.onFailure {
                Log.e("DogScRecording", "microphone encoder unavailable", it)
                onError("麦克风启动失败")
            }
        }
        if (options.systemAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                AacAudioRecorder(context, AudioSourceKind.SYSTEM, projection, systemFile)
            }.onSuccess { recorder ->
                if (runCatching { recorder.start() }.getOrDefault(false)) {
                    systemRecorder = recorder
                    systemAudioStartedAtMs = recorder.startedAtElapsedMs
                } else {
                    recorder.stop()
                    onError("系统声音启动失败")
                }
            }.onFailure {
                Log.e("DogScRecording", "system audio encoder unavailable", it)
                onError("系统声音启动失败")
            }
        }
        if (options.touchCapture) {
            runCatching { TouchRecorder.start(pointerFile, width, height) }
                .onFailure { Log.w("DogScRecording", "touch recorder unavailable", it) }
        }
        val memo = context.getSharedPreferences("dogsc_prefs", android.content.Context.MODE_PRIVATE)
            .getString("rec_memo", "") ?: ""
        overlay = RecordingOverlay(context, this, memo).also { it.show() }
        timerJob = scope.launch {
            while (true) {
                delay(250)
                // The recorders surface disk-full / system-stopped-capture errors on their own
                // threads. Without polling them the timer kept counting and the UI claimed
                // "recording" while nothing reached disk. Stop and keep the recoverable footage.
                val captureFailure = screenRecorder?.error
                if (captureFailure != null) {
                    pendingCaptureError = captureFailure
                    stop()
                    break
                }
                cameraRecorder?.error?.let { failure ->
                    // The camera track died but the screen keeps recording; tell the user once.
                    if (!cameraErrorReported) {
                        cameraErrorReported = true
                        onError(failure)
                    }
                }
                val elapsed = elapsedSeconds()
                overlay?.updateTime(elapsed)
                onState(
                    RecorderUiState(
                        isActive = true,
                        isRecording = true,
                        isPaused = pausedAt != null,
                        elapsedSeconds = elapsed,
                        markerCount = markers.size,
                        microphoneEnabled = micRecorder != null && micRecorder?.muted == false,
                        microphoneAvailable = micRecorder != null,
                        cameraEnabled = cameraRunning,
                        cameraAvailable = cameraRunning,
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
        if (!cameraRunning) return
        cameraVisible = enabled
        cameraToggles += rawElapsedSeconds(SystemClock.elapsedRealtime()) to enabled
    }

    fun stop() {
        if (stopping) return
        stopping = true
        if (!screenStarted) {
            // Stopped during camera warm-up: there is no screen track to finalize.
            discard()
            return
        }
        if (pausedAt != null) resume()
        timerJob?.cancel()
        overlay?.hide()
        overlay = null
        // CameraX's Finalize event is delivered on the main executor. Waiting for it on the
        // main thread would always burn the full timeout and freeze the UI, so only the
        // non-blocking stop request runs here.
        runCatching { cameraRecorder?.stop() }
        val screen = screenRecorder
        val mic = micRecorder
        val system = systemRecorder
        val camera = cameraRecorder
        screenRecorder = null
        micRecorder = null
        systemRecorder = null
        cameraRecorder = null
        scope.launch(Dispatchers.IO) {
            runCatching { screen?.stop() }
            runCatching { mic?.stop() }
            runCatching { system?.stop() }
            runCatching { camera?.awaitFinalized() }
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
            val encodedDuration = screen?.lastPresentationTimeUs?.div(1_000_000.0) ?: 0.0
            rawDuration = when {
                probedDuration > 0.1 -> probedDuration
                encodedDuration > 0.1 && encodedDuration < 6 * 60 * 60 -> encodedDuration
                else -> elapsedSeconds()
            }
            finalizeProject()
        }
    }

    fun isPaused(): Boolean = pausedAt != null
    fun microphoneEnabled(): Boolean = micRecorder != null && micRecorder?.muted == false
    fun microphoneAvailable(): Boolean = micRecorder != null
    fun cameraEnabled(): Boolean = cameraRunning && cameraVisible
    fun cameraAvailable(): Boolean = cameraRunning
    fun elapsedSecondsPublic(): Double = elapsedSeconds()

    fun discard(notifyManager: Boolean = true) {
        discarded = true
        AndroidRecorderManager.activeProjectId = null
        stopping = true
        timerJob?.cancel()
        overlay?.hide()
        overlay = null
        runCatching { screenRecorder?.stop() }
        runCatching { micRecorder?.stop() }
        runCatching { systemRecorder?.stop() }
        runCatching { cameraRecorder?.stop() }
        TouchRecorder.stop()
        screenRecorder = null
        micRecorder = null
        systemRecorder = null
        cameraRecorder = null
        projectDir.deleteRecursively()
        if (notifyManager) {
            onState(RecorderUiState())
            onCancelled()
        }
    }

    /** Screen capture could not start: nothing usable can be finalized, so clean up fully. */
    private fun abortWithError(message: String) {
        if (stopping) return
        stopping = true
        AndroidRecorderManager.activeProjectId = null
        timerJob?.cancel()
        overlay?.hide()
        overlay = null
        runCatching { screenRecorder?.stop() }
        runCatching { micRecorder?.stop() }
        runCatching { systemRecorder?.stop() }
        runCatching { cameraRecorder?.stop() }
        TouchRecorder.stop()
        screenRecorder = null
        micRecorder = null
        systemRecorder = null
        cameraRecorder = null
        projectDir.deleteRecursively()
        onFatalError(message)
    }

    private fun preliminaryProject(): RecorderProject = RecorderProject(
        id = projectId,
        title = projectTitle,
        createdAtEpochMs = System.currentTimeMillis(),
        capture = CaptureConfiguration(
            source = CaptureSource.DISPLAY,
            captureFrameRate = if (options.frameRate == 30) dev.dogsc.core.model.OutputFrameRate.FPS_30 else dev.dogsc.core.model.OutputFrameRate.FPS_60,
            captureCodec = if (options.captureCodec == "hevc") dev.dogsc.core.model.CaptureCodec.HEVC else dev.dogsc.core.model.CaptureCodec.H264,
            captureResolutionLimit = when (options.captureResolutionLimit) {
                "4k" -> dev.dogsc.core.model.CaptureResolutionLimit.UHD
                "1080p" -> dev.dogsc.core.model.CaptureResolutionLimit.FULL_HD
                else -> dev.dogsc.core.model.CaptureResolutionLimit.NATIVE
            },
            recordsSystemAudio = options.systemAudio,
            recordsMicrophone = options.microphone,
            recordsCamera = options.camera,
            recordsTouch = options.touchCapture,
        ),
        audio = dev.dogsc.core.model.AudioStyle(systemVolume = 1.0, microphoneVolume = 1.0),
        media = ProjectMediaManifest(
            screen = ProjectMediaReference(
                relativePath = "media/screen.mp4",
                pixelWidth = width,
                pixelHeight = height,
            ),
            camera = if (options.camera) ProjectMediaReference(relativePath = "media/camera.mp4") else null,
            microphone = if (options.microphone) ProjectMediaReference(relativePath = "media/microphone.m4a") else null,
            systemAudio = if (options.systemAudio) ProjectMediaReference(relativePath = "media/system.m4a") else null,
            pointerEvents = if (options.touchCapture) ProjectMediaReference(relativePath = "media/pointer.jsonl") else null,
        ),
    )

    private data class VideoProbe(val duration: Double, val width: Int, val height: Int)

    private fun probeVideoCodec(file: File): String {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).firstNotNullOfOrNull { index ->
                extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
                    ?.takeIf { it.startsWith("video/") }
            } ?: android.media.MediaFormat.MIMETYPE_VIDEO_AVC
        } catch (_: Throwable) {
            android.media.MediaFormat.MIMETYPE_VIDEO_AVC
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun probeVideo(file: File): VideoProbe {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val rawWidth = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
            )?.toIntOrNull() ?: 0
            val rawHeight = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
            )?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
            )?.toIntOrNull() ?: 0
            VideoProbe(
                duration = (retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
                )?.toLongOrNull() ?: 0L) / 1000.0,
                width = if (rotation % 180 != 0) rawHeight else rawWidth,
                height = if (rotation % 180 != 0) rawWidth else rawHeight,
            )
        } catch (_: Throwable) {
            VideoProbe(0.0, 0, 0)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun finalizeProject() {
        if (discarded) {
            projectDir.deleteRecursively()
            return
        }
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
                camera = cameraFile.takeIf { it.exists() && it.length() > 0 }?.let { file ->
                    val probe = probeVideo(file)
                    // Align the camera track with the screen zero point. A camera that opened
                    // first contributes lead frames (trim them); a late camera appears at its
                    // measured offset instead of shifting every frame.
                    val lead = if (cameraStartedAtMs > 0 && screenStartedAtMs > 0) {
                        (screenStartedAtMs - cameraStartedAtMs) / 1000.0
                    } else {
                        0.0
                    }
                    val sourceStart = if (probe.duration > 0.1) {
                        lead.coerceIn(0.0, max(probe.duration - 0.1, 0.0))
                    } else {
                        0.0
                    }
                    val playable = if (probe.duration > 0.1) {
                        max(probe.duration - sourceStart, 0.1)
                    } else {
                        rawDuration
                    }
                    ProjectMediaReference(
                        relativePath = "media/camera.mp4",
                        startOffset = if (lead < 0.0) -lead else 0.0,
                        sourceStartTime = sourceStart,
                        sourceEndTime = probe.duration.takeIf { it > 0.1 },
                        durationSeconds = playable,
                        pixelWidth = probe.width.takeIf { it > 0 },
                        pixelHeight = probe.height.takeIf { it > 0 },
                    )
                },
                microphone = micFile.takeIf { it.exists() && it.length() > 0 }?.let {
                    ProjectMediaReference(
                        relativePath = "media/microphone.m4a",
                        startOffset = audioStartOffset(microphoneStartedAtMs),
                    )
                },
                systemAudio = systemFile.takeIf { it.exists() && it.length() > 0 }?.let {
                    ProjectMediaReference(
                        relativePath = "media/system.m4a",
                        startOffset = audioStartOffset(systemAudioStartedAtMs),
                    )
                },
                pointerEvents = pointerFile.takeIf { it.exists() && it.length() > 0 }?.let {
                    ProjectMediaReference(relativePath = "media/pointer.jsonl")
                },
            )
            val project = RecorderProject(
                id = projectId,
                title = projectTitle,
                createdAtEpochMs = System.currentTimeMillis(),
                capture = CaptureConfiguration(
                    source = CaptureSource.DISPLAY,
                    captureFrameRate = if (options.frameRate == 30) dev.dogsc.core.model.OutputFrameRate.FPS_30 else dev.dogsc.core.model.OutputFrameRate.FPS_60,
                    captureCodec = if (probeVideoCodec(screenFile) == android.media.MediaFormat.MIMETYPE_VIDEO_HEVC) dev.dogsc.core.model.CaptureCodec.HEVC else dev.dogsc.core.model.CaptureCodec.H264,
                    captureResolutionLimit = when (options.captureResolutionLimit) {
                        "4k" -> dev.dogsc.core.model.CaptureResolutionLimit.UHD
                        "1080p" -> dev.dogsc.core.model.CaptureResolutionLimit.FULL_HD
                        else -> dev.dogsc.core.model.CaptureResolutionLimit.NATIVE
                    },
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
            if (discarded) {
                projectDir.deleteRecursively()
                return
            }
            val saved = repository.saveProject(project)
            if (saved && !discarded) {
                pendingCaptureError?.let { onError("录制已提前结束：$it，已保存可用部分") }
                onCompleted(projectId)
            } else if (!discarded) {
                // End the session instead of leaving the recording screen stuck with isActive.
                onFatalError("录制已结束，但项目保存失败")
            }
        } catch (error: Throwable) {
            Log.e("DogScRecording", "finalize failed", error)
            if (!discarded) onFatalError("录制已结束，但项目保存失败：${error.message}")
        } finally {
            AndroidRecorderManager.activeProjectId = null
        }
    }

    /** AAC PTS starts at zero on the first sample; delay it if capture opened after the screen. */
    private fun audioStartOffset(startedAtMs: Long): Double {
        if (startedAtMs <= 0 || screenStartedAtMs <= 0) return 0.0
        return ((startedAtMs - screenStartedAtMs).coerceAtLeast(0L) / 1000.0)
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
            val direct = timelineMap.outputTimeForSource(raw)
            if (direct != null) {
                direct to enabled
            } else {
                // The toggle happened inside a paused/cut range. The state still applies from
                // the next visible frame, otherwise the camera would reappear after the cut.
                timelineMap.segments.firstOrNull { raw < it.sourceEnd }?.let { it.outputStart to enabled }
            }
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
