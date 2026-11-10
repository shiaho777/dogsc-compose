package dev.dogsc.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.dogsc.app.platform.DogScPlatform
import dev.dogsc.app.platform.ExportProgress
import dev.dogsc.app.platform.ExportRequest
import dev.dogsc.app.platform.ExportResult
import dev.dogsc.app.platform.LoadedProject
import dev.dogsc.app.platform.ProjectSummary
import dev.dogsc.app.platform.RecordingOptions
import dev.dogsc.core.editor.EditorSession
import dev.dogsc.core.editor.TimelineEditor
import dev.dogsc.core.model.CameraStyle
import dev.dogsc.core.model.CanvasStyle
import dev.dogsc.core.model.CursorStyle
import dev.dogsc.core.model.ExportSettings
import dev.dogsc.core.model.MotionStyle
import dev.dogsc.core.model.OpeningSequence
import dev.dogsc.core.model.RecorderProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min

sealed interface AppScreen {
    data object Library : AppScreen
    data object RecordSetup : AppScreen
    data object Recording : AppScreen
    data class Editor(val projectId: String) : AppScreen
    data object Settings : AppScreen
}

enum class InspectorTab(val title: String) {
    CANVAS("画面"),
    MOTION("运镜"),
    CURSOR("光标"),
    CAMERA("摄像头"),
    AUDIO("声音"),
}

enum class CanvasPane(val title: String) {
    BACKGROUND("背景"),
    LAYOUT("布局"),
    FRAME("样机"),
    EFFECTS("效果"),
    STICKERS("贴图"),
}

sealed interface EditorSelection {
    data class Primary(val segmentId: String) : EditorSelection
    data class Zoom(val clipId: String) : EditorSelection
    data class ScreenMotion(val clipId: String) : EditorSelection
    data class CameraMotion(val clipId: String) : EditorSelection
    data class Mosaic(val clipId: String) : EditorSelection
    data class Sticker(val clipId: String) : EditorSelection
    data class Marker(val markerId: String) : EditorSelection
    data object None : EditorSelection
}

data class ToastMessage(val id: Long, val text: String, val isError: Boolean = false)

class DogScAppState(
    val platform: DogScPlatform,
    private val scope: CoroutineScope,
) {
    var screen by mutableStateOf<AppScreen>(AppScreen.Library)
        private set
    var projects by mutableStateOf<List<ProjectSummary>>(emptyList())
        private set
    var isLoadingProjects by mutableStateOf(true)
        private set
    var toast by mutableStateOf<ToastMessage?>(null)
    var showOnboarding by mutableStateOf(!platform.hasSeenOnboarding())
        private set
    var isBusy by mutableStateOf(false)
        private set
    var busyMessage by mutableStateOf("")
        private set

    var session by mutableStateOf<EditorSession?>(null)
        private set

    /** EditorSession lives in core and is not snapshot-aware; bump this after every mutation so Compose recomposes. */
    private var sessionRevision by mutableStateOf(0L)
    var loadedProject by mutableStateOf<LoadedProject?>(null)
        private set
    var previewFrame by mutableStateOf<ImageBitmap?>(null)
        private set
    var cameraPreviewFrame by mutableStateOf<ImageBitmap?>(null)
        private set
    var backgroundPreviewFrame by mutableStateOf<ImageBitmap?>(null)
        private set
    var stickerFrames by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())
        private set
    var isPreviewLoading by mutableStateOf(false)
        private set

    var playhead by mutableStateOf(0.0)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var selection by mutableStateOf<EditorSelection>(EditorSelection.None)
        private set
    var inspectorTab by mutableStateOf(InspectorTab.CANVAS)
    var canvasPane by mutableStateOf(CanvasPane.BACKGROUND)
    var timelinePixelsPerSecond by mutableStateOf(44f)
    var timelineScroll by mutableStateOf(0f)
    var snappingEnabled by mutableStateOf(true)
    var hoverPreviewEnabled by mutableStateOf(false)
    var showExportSheet by mutableStateOf(false)
    var showScenePresetSheet by mutableStateOf(false)
    var scenePresets by mutableStateOf<List<dev.dogsc.app.platform.ScenePreset>>(emptyList())
    var activeScenePresetId by mutableStateOf<String?>(null)
    var showMarkersSheet by mutableStateOf(false)
    var showShortcutSheet by mutableStateOf(false)
    var isTimelineExpanded by mutableStateOf(true)
    var showWaveform by mutableStateOf(false)
    var waveform by mutableStateOf<FloatArray?>(null)
    var filmstripFrames by mutableStateOf<List<ImageBitmap>>(emptyList())
    private var waveformJob: Job? = null
    var hiddenTracks by mutableStateOf<Set<String>>(emptySet())
    var recordingOptions by mutableStateOf(RecordingOptions())
        private set
    var exportSoundEnabled by mutableStateOf(true)
        private set
    var memoText by mutableStateOf("")
        private set

    private var previewJob: Job? = null
    private var pendingPreviewTime: Double? = null
    private var previewMediaSignature: String? = null
    private var playbackJob: Job? = null
    private var toastJob: Job? = null
    private var exportJob: Job? = null
    private var autosaveJob: Job? = null
    private val saveMutex = Mutex()
    private var toastCounter = 0L
    // Export state belongs to the project that started it; other projects must not see it.
    private var exportOwnerId by mutableStateOf<String?>(null)
    private var exportProgressState by mutableStateOf<ExportProgress?>(null)
    private var lastExportState by mutableStateOf<ExportResult?>(null)
    val exportProgress: ExportProgress?
        get() = exportProgressState.takeIf { exportOwnerId != null && exportOwnerId == project?.id }
    val lastExport: ExportResult?
        get() = lastExportState.takeIf { exportOwnerId != null && exportOwnerId == project?.id }

    val project: RecorderProject? get() = sessionRevision.let { session?.project }
    val sourceDuration: Double get() = sessionRevision.let { session?.sourceDuration ?: 0.0 }
    val outputDuration: Double get() = sessionRevision.let { session?.outputDuration ?: 0.0 }
    val pointerEvents: List<dev.dogsc.core.model.PointerEventRecord>
        get() = loadedProject?.pointerEvents ?: emptyList()
    val sourceAspectRatio: Double
        get() {
            val w = loadedProject?.summary?.pixelWidth
            val h = loadedProject?.summary?.pixelHeight
            return if (w != null && h != null && w > 0 && h > 0) w.toDouble() / h else 16.0 / 9.0
        }
    val cameraAspectRatio: Double?
        get() {
            val camera = project?.media?.camera ?: return null
            val w = camera.pixelWidth
            val h = camera.pixelHeight
            return if (w != null && h != null && w > 0 && h > 0) w.toDouble() / h else null
        }
    val canvasAspectRatio: Double
        get() {
            val project = project ?: return 16.0 / 9.0
            return project.canvas.resolvedFixedAspectRatio
                ?: (sourceAspectRatio * project.canvas.crop.width / project.canvas.crop.height)
        }
    val canUndo: Boolean get() = sessionRevision.let { session?.canUndo == true }
    val canRedo: Boolean get() = sessionRevision.let { session?.canRedo == true }
    val isDirty: Boolean get() = sessionRevision.let { session?.isDirty == true }

    fun refreshScenePresets() {
        scope.launch {
            scenePresets = platform.loadScenePresets()
        }
    }

    fun refreshPreferences() {
        scope.launch {
            val prefs = runCatching { platform.loadPreferences() }.getOrNull() ?: return@launch
            recordingOptions = prefs.recording
            exportSoundEnabled = prefs.exportSoundEnabled
            memoText = prefs.memoText
        }
    }

    fun updateMemoText(text: String) {
        memoText = text
        persistPreferences()
    }

    fun updateRecordingDefaults(transform: (RecordingOptions) -> RecordingOptions) {
        recordingOptions = transform(recordingOptions)
        persistPreferences()
    }

    fun updateExportSoundEnabled(enabled: Boolean) {
        exportSoundEnabled = enabled
        persistPreferences()
    }

    private fun persistPreferences() {
        val snapshot = dev.dogsc.app.platform.AppPreferences(
            recording = recordingOptions,
            exportSoundEnabled = exportSoundEnabled,
            memoText = memoText,
        )
        scope.launch { runCatching { platform.savePreferences(snapshot) } }
    }

    fun saveScenePreset(name: String) {
        val project = project ?: return
        val preset = dev.dogsc.app.platform.ScenePreset(
            id = dev.dogsc.core.model.randomId(),
            name = name.take(60),
            canvas = project.canvas,
            camera = project.camera,
            cursorStyle = project.cursorStyle,
            openingSequence = project.openingSequence,
            motion = project.motion,
        )
        val next = scenePresets + preset
        scenePresets = next
        activeScenePresetId = preset.id
        scope.launch { platform.saveScenePresets(next) }
        showToast("已保存场景「${preset.name}」")
    }

    fun applyScenePreset(preset: dev.dogsc.app.platform.ScenePreset) {
        updateProject("应用场景预设") { current ->
            current.copy(
                canvas = preset.canvas,
                camera = preset.camera,
                cursorStyle = preset.cursorStyle,
                openingSequence = preset.openingSequence,
                motion = preset.motion,
            )
        }
        activeScenePresetId = preset.id
        showToast("已应用场景「${preset.name}」")
    }

    fun deleteScenePreset(preset: dev.dogsc.app.platform.ScenePreset) {
        val next = scenePresets.filterNot { it.id == preset.id }
        scenePresets = next
        if (activeScenePresetId == preset.id) activeScenePresetId = null
        scope.launch { platform.saveScenePresets(next) }
    }

    fun refreshProjects() {
        scope.launch {
            isLoadingProjects = true
            projects = platform.listProjects().sortedByDescending { it.updatedAtEpochMs }
            isLoadingProjects = false
        }
    }

    fun finishOnboarding() {
        platform.markOnboardingSeen()
        showOnboarding = false
    }

    fun reopenOnboarding() {
        showOnboarding = true
    }

    fun openLibrary() {
        stopPlayback()
        platform.releasePreviewAudio()
        platform.releasePreviewFrames()
        flushPendingSave()
        screen = AppScreen.Library
        session = null
        loadedProject = null
        previewFrame = null
        refreshProjects()
    }

    fun openRecordSetup() {
        screen = AppScreen.RecordSetup
    }

    fun enterRecording() {
        if (screen != AppScreen.Recording) screen = AppScreen.Recording
    }

    fun leaveRecording() {
        if (screen == AppScreen.Recording) openLibrary()
    }

    fun openSettings() {
        screen = AppScreen.Settings
    }

    fun openProject(summary: ProjectSummary) {
        scope.launch {
            isBusy = true
            busyMessage = "正在载入项目"
            val loaded = platform.loadProject(summary.id)
            isBusy = false
            if (loaded == null) {
                showToast("无法打开项目：文件缺失或已损坏", isError = true)
                refreshProjects()
                return@launch
            }
            session = EditorSession(loaded.project, loaded.sourceDuration)
            loadedProject = loaded
            playhead = 0.0
            selection = EditorSelection.None
            inspectorTab = InspectorTab.CANVAS
            canvasPane = CanvasPane.BACKGROUND
            screen = AppScreen.Editor(loaded.project.id)
            requestPreviewFrame(0.0)
            refreshStaticMedia(force = true)
            waveform = null
            filmstripFrames = emptyList()
            scope.launch { filmstripFrames = platform.loadFilmstrip(loaded.project, 12) }
        }
    }

    fun importVideo(uri: String) {
        scope.launch {
            isBusy = true
            busyMessage = "正在导入视频"
            val loaded = platform.importVideo(uri)
            isBusy = false
            if (loaded == null) {
                showToast("无法导入这个视频", isError = true)
                return@launch
            }
            refreshProjects()
            session = EditorSession(loaded.project, loaded.sourceDuration)
            loadedProject = loaded
            playhead = 0.0
            selection = EditorSelection.None
            screen = AppScreen.Editor(loaded.project.id)
            requestPreviewFrame(0.0)
            refreshStaticMedia(force = true)
            showToast("已导入「${loaded.project.title}」")
        }
    }

    fun importSticker(uri: String) {
        val project = project ?: return
        scope.launch {
            isBusy = true
            busyMessage = "正在导入贴图"
            val path = platform.importSticker(project.id, uri)
            isBusy = false
            if (path == null) {
                showToast("无法导入这张图片", isError = true)
                return@launch
            }
            updateProject("添加贴图") { current ->
                TimelineEditor.addSticker(
                    current,
                    dev.dogsc.core.model.OverlayTiming(
                        playhead.coerceAtLeast(0.0),
                        min(3.0, max(outputDuration - playhead, 0.4)),
                    ),
                    path,
                    0.38,
                )
            }
            showToast("贴图已添加")
        }
    }


    fun setBackground(uri: String, isVideo: Boolean) {
        val project = project ?: return
        scope.launch {
            isBusy = true
            busyMessage = if (isVideo) "正在导入背景视频" else "正在导入背景图片"
            val path = platform.importBackground(project.id, uri, isVideo)
            isBusy = false
            if (path == null) {
                showToast("无法导入背景素材", isError = true)
                return@launch
            }
            updateProject("更换背景") { current ->
                current.copy(canvas = current.canvas.copy(
                    backgroundSource = if (isVideo) {
                        dev.dogsc.core.model.BackgroundSource.ProjectVideo(path)
                    } else {
                        dev.dogsc.core.model.BackgroundSource.ProjectImage(path)
                    },
                ))
            }
            backgroundPreviewFrame = platform.loadBackgroundBitmap(project)
            showToast("背景已更新")
        }
    }

    fun applyAutoZoomFromTouch() {
        val events = pointerEvents
        if (events.none { it.kind == dev.dogsc.core.model.PointerEventKind.LEFT_CLICK || it.kind == dev.dogsc.core.model.PointerEventKind.RIGHT_CLICK }) {
            showToast("这个项目没有可用的触控点击记录", isError = true)
            return
        }
        updateProject("生成自动运镜") { current ->
            val generated = dev.dogsc.core.model.AutoZoomPlanner.makeAnimations(
                events,
                zoomScale = current.motion.defaultZoomScale ?: 1.6,
                easing = current.motion.defaultZoomEasing,
                transitionDuration = current.motion.defaultZoomTransitionDuration,
            )
            current.copy(timeline = current.timeline.copy(
                zoomClips = current.timeline.zoomClips.filter { it.origin == dev.dogsc.core.model.ZoomKeyframeOrigin.MANUAL } + generated,
            ))
        }
        showToast("已根据触控生成 ${project?.timeline?.zoomClips?.count { it.origin == dev.dogsc.core.model.ZoomKeyframeOrigin.AUTOMATIC } ?: 0} 段自动运镜")
    }

    fun applyZoomScaleToAll(scale: Double) {
        updateProject("统一缩放倍率") { current ->
            current.copy(timeline = current.timeline.copy(
                zoomClips = current.timeline.zoomClips.map { it.copy(scale = scale.coerceIn(1.0, 6.0)) },
            ))
        }
    }

    fun addMosaicAt(time: Double, style: dev.dogsc.core.model.MosaicEffectStyle) {
        val duration = min(3.0, max(outputDuration - time, 0.4))
        updateProject(if (style == dev.dogsc.core.model.MosaicEffectStyle.BLUR) "添加柔化" else "添加突出") { current ->
            TimelineEditor.addMosaic(
                current,
                dev.dogsc.core.model.OverlayTiming(time.coerceAtLeast(0.0), duration),
                dev.dogsc.core.model.NormalizedOverlayRect.Centered,
                style,
            )
        }
    }

    fun selectedZoom(): dev.dogsc.core.model.ZoomAnimationClip? =
        (selection as? EditorSelection.Zoom)?.let { sel -> project?.timeline?.zoomClips?.firstOrNull { it.id == sel.clipId } }

    fun selectedScreenMotion(): dev.dogsc.core.model.ScreenMotionClip? =
        (selection as? EditorSelection.ScreenMotion)?.let { sel -> project?.timeline?.screenMotionClips?.firstOrNull { it.id == sel.clipId } }

    fun selectedCameraMotion(): dev.dogsc.core.model.CameraMotionClip? =
        (selection as? EditorSelection.CameraMotion)?.let { sel -> project?.timeline?.cameraMotionClips?.firstOrNull { it.id == sel.clipId } }

    fun selectedMosaic(): dev.dogsc.core.model.MosaicClip? =
        (selection as? EditorSelection.Mosaic)?.let { sel -> project?.timeline?.mosaicClips?.firstOrNull { it.id == sel.clipId } }

    fun selectedSticker(): dev.dogsc.core.model.StickerClip? =
        (selection as? EditorSelection.Sticker)?.let { sel -> project?.timeline?.stickerClips?.firstOrNull { it.id == sel.clipId } }

    fun openRecordingResult(loaded: LoadedProject) {
        session = EditorSession(loaded.project, loaded.sourceDuration)
        loadedProject = loaded
        playhead = 0.0
        selection = EditorSelection.None
        screen = AppScreen.Editor(loaded.project.id)
        requestPreviewFrame(0.0)
        refreshStaticMedia(force = true)
        filmstripFrames = emptyList()
        scope.launch { filmstripFrames = platform.loadFilmstrip(loaded.project, 12) }
        refreshProjects()
    }

    fun saveProject(showConfirmation: Boolean = false) {
        autosaveJob?.cancel()
        val project = project ?: return
        scope.launch { persist(project, showConfirmation) }
    }

    /** Writes pending edits immediately: leaving the editor or the app going to the background. */
    fun flushPendingSave() {
        autosaveJob?.cancel()
        autosaveJob = null
        if (!isDirty) return
        val project = project ?: return
        scope.launch { persist(project, showConfirmation = false) }
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        if (!isDirty) return
        autosaveJob = scope.launch {
            delay(AutosaveDelayMs)
            val project = project ?: return@launch
            // Separate job so a newer edit cancelling the debounce never interrupts an in-flight write.
            scope.launch { persist(project, showConfirmation = false) }
        }
    }

    private suspend fun persist(project: RecorderProject, showConfirmation: Boolean) {
        val saved = saveMutex.withLock { platform.saveProject(project) }
        if (saved) {
            val current = session
            if (current != null && current.project === project) {
                current.markSaved()
                sessionRevision++
            }
            refreshProjects()
            if (showConfirmation) showToast("项目已保存")
        } else {
            showToast("保存失败，请检查存储空间", isError = true)
        }
    }

    fun updateProject(label: String, transform: (RecorderProject) -> RecorderProject?) {
        val session = session ?: return
        val committed = try {
            session.commit(label, transform)
        } catch (error: dev.dogsc.core.model.ProjectValidationException) {
            showToast(error.message ?: "操作无效", isError = true)
            false
        }
        if (committed) {
            sessionRevision++
            onProjectChanged()
        }
    }

    fun mutateProject(label: String, transform: (RecorderProject) -> RecorderProject) {
        updateProject(label) { transform(it) }
    }

    fun updateCanvas(label: String = "调整画面", transform: (CanvasStyle) -> CanvasStyle) {
        updateProject(label) { it.copy(canvas = transform(it.canvas)) }
    }

    fun updateCamera(label: String = "调整摄像头", transform: (CameraStyle) -> CameraStyle) {
        updateProject(label) { it.copy(camera = transform(it.camera)) }
    }

    fun updateCursor(label: String = "调整光标", transform: (CursorStyle) -> CursorStyle) {
        updateProject(label) { it.copy(cursorStyle = transform(it.cursorStyle)) }
    }

    fun updateMotion(label: String = "调整运镜", transform: (MotionStyle) -> MotionStyle) {
        updateProject(label) { it.copy(motion = transform(it.motion)) }
    }

    fun updateOpening(label: String = "调整开场", transform: (OpeningSequence) -> OpeningSequence) {
        updateProject(label) { it.copy(openingSequence = transform(it.openingSequence).normalized()) }
    }

    fun updateExport(label: String = "调整导出规格", transform: (ExportSettings) -> ExportSettings) {
        updateProject(label) { it.copy(exportSettings = transform(it.exportSettings)) }
    }

    private fun onProjectChanged() {
        requestPreviewFrame(playhead)
        refreshStaticMedia()
        if (showWaveform) requestWaveform()
        scheduleAutosave()
    }

    fun toggleWaveform() {
        showWaveform = !showWaveform
        if (showWaveform && waveform == null) requestWaveform()
    }

    fun requestWaveform() {
        val project = project ?: return
        waveformJob?.cancel()
        waveformJob = scope.launch {
            waveform = platform.loadWaveform(project, sourceDuration, 700)
        }
    }

    fun undo() {
        if (session?.undo() == true) {
            sessionRevision++
            onProjectChanged()
            showToast("已撤销")
        }
    }

    fun redo() {
        if (session?.redo() == true) {
            sessionRevision++
            onProjectChanged()
            showToast("已重做")
        }
    }

    fun seekTo(time: Double, updatePreview: Boolean = true) {
        val duration = outputDuration
        val clamped = time.coerceIn(0.0, max(duration, 0.0))
        playhead = clamped
        if (updatePreview) requestPreviewFrame(clamped)
        if (isPlaying) project?.let { platform.seekPreviewAudio(it, sourceDuration, clamped) }
    }

    fun stepFrames(frames: Int) {
        val fps = project?.exportSettings?.frameRate?.value ?: 60
        seekTo(playhead + frames.toDouble() / fps)
    }

    fun togglePlayback() {
        if (isPlaying) stopPlayback() else startPlayback()
    }

    fun startPlayback() {
        if (outputDuration <= 0) return
        if (playhead >= outputDuration - 0.01) playhead = 0.0
        isPlaying = true
        project?.let { platform.startPreviewAudio(it, sourceDuration, playhead) }
        playbackJob?.cancel()
        playbackJob = scope.launch {
            // Monotonic clock instead of "playhead += 0.016": no drift and no dependence on
            // how long each frame's work takes.
            val origin = playhead
            val startedAt = kotlin.time.TimeSource.Monotonic.markNow()
            var lastFrameRequest = -1.0
            while (isPlaying) {
                delay(16)
                val next = origin + startedAt.elapsedNow().inWholeMicroseconds / 1_000_000.0
                if (next >= outputDuration) {
                    playhead = outputDuration
                    isPlaying = false
                    platform.pausePreviewAudio()
                    requestPreviewFrame(playhead)
                    break
                }
                playhead = next
                if (playhead - lastFrameRequest >= 0.033) {
                    lastFrameRequest = playhead
                    requestPreviewFrame(playhead)
                }
            }
        }
    }

    fun stopPlayback() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
        platform.pausePreviewAudio()
    }

    fun requestPreviewFrame(time: Double) {
        val project = project ?: return
        // Coalesce requests: while a frame is decoding, keep only the newest target instead of
        // cancelling and restarting a decode (which leaves the native retriever working anyway).
        pendingPreviewTime = time
        if (previewJob?.isActive == true) return
        previewJob = scope.launch {
            while (true) {
                val target = pendingPreviewTime ?: break
                pendingPreviewTime = null
                val current = project ?: break
                isPreviewLoading = previewFrame == null
                val frame = platform.loadThumbnail(current, target)
                if (pendingPreviewTime == null) {
                    if (frame != null) previewFrame = frame
                    cameraPreviewFrame = if (current.media?.camera != null && !current.camera.isHidden) {
                        platform.loadCameraThumbnail(current, target)
                    } else {
                        null
                    }
                    isPreviewLoading = false
                }
                if (pendingPreviewTime == null) break
            }
        }
    }

    /**
     * Stickers and custom backgrounds only change with the project, never with the playhead;
     * loading them once per edit keeps playback from re-decoding them every frame.
     */
    fun refreshStaticMedia(force: Boolean = false) {
        val current = project ?: return
        val signature = buildString {
            append(current.canvas.backgroundSource)
            append('|')
            current.timeline.stickerClips.forEach { append(it.relativePath).append(',') }
        }
        if (!force && signature == previewMediaSignature) return
        previewMediaSignature = signature
        scope.launch {
            backgroundPreviewFrame = if (current.canvas.backgroundSource.usesWallpaperMedia) {
                platform.loadBackgroundBitmap(current)
            } else {
                null
            }
            val stickers = mutableMapOf<String, ImageBitmap>()
            for (clip in current.timeline.stickerClips) {
                platform.loadStickerBitmap(current, clip.relativePath)?.let { stickers[clip.relativePath] = it }
            }
            stickerFrames = stickers
        }
    }

    fun select(selection: EditorSelection) {
        this.selection = selection
    }

    fun deleteSelection() {
        val project = project ?: return
        val current = selection
        when (current) {
            is EditorSelection.Primary -> {
                val segment = (project.timeline.sourceSequence as? dev.dogsc.core.model.SourceSequence.Edited)
                    ?.segments?.firstOrNull { it.id == current.segmentId }
                if (segment != null) {
                    val map = TimelineEditor.timelineMap(project, sourceDuration)
                    val resolved = map.segments.firstOrNull { it.id == segment.id }
                    if (resolved != null) {
                        updateProject("删除片段") {
                            TimelineEditor.deletePrimaryRange(it, sourceDuration, resolved.outputStart, resolved.outputEnd)
                        }
                    }
                }
            }
            is EditorSelection.Zoom -> updateProject("删除缩放") {
                TimelineEditor.deleteZoom(it, current.clipId)
            }
            is EditorSelection.ScreenMotion -> updateProject("删除屏幕 3D") {
                TimelineEditor.deleteScreenMotion(it, current.clipId)
            }
            is EditorSelection.CameraMotion -> updateProject("删除摄像运动") {
                TimelineEditor.deleteCameraMotion(it, current.clipId)
            }
            is EditorSelection.Mosaic -> updateProject("删除打码") {
                TimelineEditor.deleteMosaic(it, current.clipId)
            }
            is EditorSelection.Sticker -> updateProject("删除贴图") {
                TimelineEditor.deleteSticker(it, current.clipId)
            }
            is EditorSelection.Marker -> {
                updateProject("删除录制标记") { project ->
                    project.copy(recordingMarkers = project.recordingMarkers.filterNot { it.id == current.markerId })
                }
            }
            EditorSelection.None -> Unit
        }
        selection = EditorSelection.None
    }

    fun restoreCutAtPlayhead() {
        val project = project ?: return
        val restored = TimelineEditor.restoreCut(project, sourceDuration, playhead)
        if (restored == null) {
            showToast("播放头附近没有可还原的剪切缺口", isError = true)
        } else {
            updateProject("还原剪切缺口") { restored }
            showToast("已还原剪切缺口")
        }
    }

    fun restoreAllCuts() {
        val project = project ?: return
        updateProject("还原全部剪辑") {
            TimelineEditor.restoreAllCuts(it, sourceDuration)
        }
        showToast("已还原全部剪辑")
    }

    fun trimPrimary(segmentId: String, leading: Boolean, deltaSeconds: Double) {
        val project = project ?: return
        val map = TimelineEditor.timelineMap(project, sourceDuration)
        val segment = map.segments.firstOrNull { it.id == segmentId } ?: return
        if (leading) {
            val newStart = (segment.sourceStart + deltaSeconds * segment.playbackRate)
                .coerceIn(0.0, segment.sourceEnd - 0.08 * segment.playbackRate)
            updateProject("裁切片段") { current ->
                current.copy(timeline = current.timeline.copy(
                    sourceSequence = dev.dogsc.core.model.SourceSequence.Edited(
                        map.segments.map {
                            if (it.id == segmentId) dev.dogsc.core.model.RecordingSegment(
                                id = it.id,
                                sourceStart = newStart,
                                sourceDuration = it.sourceEnd - newStart,
                                playbackRate = it.playbackRate,
                            ) else dev.dogsc.core.model.RecordingSegment(
                                id = it.id,
                                sourceStart = it.sourceStart,
                                sourceDuration = it.sourceDuration,
                                playbackRate = it.playbackRate,
                            )
                        }
                    )
                ))
            }
        } else {
            val newEnd = (segment.sourceEnd + deltaSeconds * segment.playbackRate)
                .coerceIn(segment.sourceStart + 0.08 * segment.playbackRate, sourceDuration)
            updateProject("裁切片段") { current ->
                current.copy(timeline = current.timeline.copy(
                    sourceSequence = dev.dogsc.core.model.SourceSequence.Edited(
                        map.segments.map {
                            if (it.id == segmentId) dev.dogsc.core.model.RecordingSegment(
                                id = it.id,
                                sourceStart = it.sourceStart,
                                sourceDuration = newEnd - it.sourceStart,
                                playbackRate = it.playbackRate,
                            ) else dev.dogsc.core.model.RecordingSegment(
                                id = it.id,
                                sourceStart = it.sourceStart,
                                sourceDuration = it.sourceDuration,
                                playbackRate = it.playbackRate,
                            )
                        }
                    )
                ))
            }
        }
    }

    fun movePrimarySegment(segmentId: String, targetIndex: Int) {
        updateProject("重排主片段") {
            TimelineEditor.movePrimarySegment(it, sourceDuration, segmentId, targetIndex)
        }
    }

    fun setPrimaryPlaybackRate(segmentId: String, rate: Double) {
        updateProject("调整片段速度") {
            TimelineEditor.setPlaybackRate(it, sourceDuration, segmentId, rate)
        }
    }

    fun updatePrimaryAudio(
        segmentId: String,
        transform: (dev.dogsc.core.model.PrimarySegmentAudioOverrides) -> dev.dogsc.core.model.PrimarySegmentAudioOverrides,
    ) {
        val current = project ?: return
        val existing = current.timeline.primarySegmentAudioOverrides[segmentId]
            ?: dev.dogsc.core.model.PrimarySegmentAudioOverrides()
        val next = transform(existing)
        updateProject("调整片段声音") {
            TimelineEditor.updatePrimaryAudioOverride(it, segmentId, next)
        }
    }

    fun addMarkerAtPlayhead() {
        val project = project ?: return
        val map = TimelineEditor.timelineMap(project, sourceDuration)
        val sourceTime = map.segmentAtOutputTime(playhead)?.let { segment ->
            segment.sourceStart + (playhead - segment.outputStart) * segment.playbackRate
        } ?: playhead
        val nextNumber = (project.recordingMarkers.maxOfOrNull { it.number } ?: 0) + 1
        updateProject("添加录制标记") { current ->
            current.copy(
                recordingMarkers = current.recordingMarkers + dev.dogsc.core.model.RecordingMarker(
                    sourceTime = sourceTime,
                    number = nextNumber,
                ),
            )
        }
        showToast("已添加标记 $nextNumber")
    }

    fun seekToMarker(markerId: String) {
        val project = project ?: return
        val marker = project.recordingMarkers.firstOrNull { it.id == markerId } ?: return
        val map = TimelineEditor.timelineMap(project, sourceDuration)
        val outputTime = map.outputTimeForSource(marker.sourceTime)
        if (outputTime == null) {
            showToast("标记所在素材已被剪掉，恢复剪辑后可重新定位", isError = true)
        } else {
            seekTo(outputTime)
        }
    }

    fun deleteMarker(markerId: String) {
        updateProject("删除录制标记") { current ->
            current.copy(recordingMarkers = current.recordingMarkers.filterNot { it.id == markerId })
        }
    }

    fun splitAtPlayhead() {
        val project = project ?: return
        when (val current = selection) {
            is EditorSelection.Zoom -> updateProject("分割缩放") {
                TimelineEditor.splitZoom(it, current.clipId, playhead)
            }
            is EditorSelection.ScreenMotion, is EditorSelection.CameraMotion,
            is EditorSelection.Mosaic, is EditorSelection.Sticker -> {
                splitOverlayAtPlayhead(current)
            }
            else -> {
                updateProject("分割主片段") {
                    TimelineEditor.splitPrimary(it, sourceDuration, playhead)
                }
            }
        }
    }

    private fun splitOverlayAtPlayhead(selection: EditorSelection) {
        val project = project ?: return
        when (selection) {
            is EditorSelection.ScreenMotion -> {
                val clip = project.timeline.screenMotionClips.firstOrNull { it.id == selection.clipId } ?: return
                if (playhead - clip.timing.startTime < 0.08 || clip.timing.endTime - playhead < 0.08) return
                val left = clip.copy(timing = clip.timing.copy(duration = playhead - clip.timing.startTime))
                val right = clip.copy(
                    id = dev.dogsc.core.model.randomId(),
                    timing = clip.timing.copy(
                        startTime = playhead,
                        duration = clip.timing.endTime - playhead,
                    ),
                )
                updateProject("分割屏幕 3D") {
                    it.copy(timeline = it.timeline.copy(
                        screenMotionClips = it.timeline.screenMotionClips.flatMap { c ->
                            if (c.id == clip.id) listOf(left, right) else listOf(c)
                        },
                    ))
                }
            }
            is EditorSelection.CameraMotion -> {
                val clip = project.timeline.cameraMotionClips.firstOrNull { it.id == selection.clipId } ?: return
                if (playhead - clip.timing.startTime < 0.08 || clip.timing.endTime - playhead < 0.08) return
                val left = clip.copy(timing = clip.timing.copy(duration = playhead - clip.timing.startTime))
                val right = clip.copy(
                    id = dev.dogsc.core.model.randomId(),
                    timing = clip.timing.copy(
                        startTime = playhead,
                        duration = clip.timing.endTime - playhead,
                    ),
                )
                updateProject("分割摄像运动") {
                    it.copy(timeline = it.timeline.copy(
                        cameraMotionClips = it.timeline.cameraMotionClips.flatMap { c ->
                            if (c.id == clip.id) listOf(left, right) else listOf(c)
                        },
                    ))
                }
            }
            is EditorSelection.Mosaic -> {
                val clip = project.timeline.mosaicClips.firstOrNull { it.id == selection.clipId } ?: return
                if (playhead - clip.timing.startTime < 0.08 || clip.timing.endTime - playhead < 0.08) return
                val left = clip.copy(timing = clip.timing.copy(duration = playhead - clip.timing.startTime))
                val right = clip.copy(
                    id = dev.dogsc.core.model.randomId(),
                    timing = clip.timing.copy(
                        startTime = playhead,
                        duration = clip.timing.endTime - playhead,
                    ),
                )
                updateProject("分割打码") {
                    it.copy(timeline = it.timeline.copy(
                        mosaicClips = it.timeline.mosaicClips.flatMap { c ->
                            if (c.id == clip.id) listOf(left, right) else listOf(c)
                        },
                    ))
                }
            }
            is EditorSelection.Sticker -> {
                val clip = project.timeline.stickerClips.firstOrNull { it.id == selection.clipId } ?: return
                if (playhead - clip.timing.startTime < 0.08 || clip.timing.endTime - playhead < 0.08) return
                val left = clip.copy(timing = clip.timing.copy(duration = playhead - clip.timing.startTime))
                val right = clip.copy(
                    id = dev.dogsc.core.model.randomId(),
                    timing = clip.timing.copy(
                        startTime = playhead,
                        duration = clip.timing.endTime - playhead,
                    ),
                )
                updateProject("分割贴图") {
                    it.copy(timeline = it.timeline.copy(
                        stickerClips = it.timeline.stickerClips.flatMap { c ->
                            if (c.id == clip.id) listOf(left, right) else listOf(c)
                        },
                    ))
                }
            }
            else -> Unit
        }
    }

    fun startExport(outputUri: String, audioOnly: Boolean = false, trimStart: Double = 0.0, trimEnd: Double? = null) {
        val project = project ?: return
        exportJob?.cancel()
        exportOwnerId = project.id
        lastExportState = null
        exportProgressState = ExportProgress(0f, "正在准备素材")
        exportJob = scope.launch {
            val result = platform.export(
                ExportRequest(
                    project = project,
                    sourceDuration = sourceDuration,
                    outputUri = outputUri,
                    audioOnly = audioOnly,
                    trimStart = trimStart,
                    trimEnd = trimEnd,
                ),
            ) { progress ->
                scope.launch { if (lastExportState == null && exportJob?.isActive == true) exportProgressState = progress }
            }
            lastExportState = result
            if (result is ExportResult.Success) {
                exportProgressState = null
                if (exportSoundEnabled) platform.playExportSound()
                showToast("已导出 ${formatFileSize(result.sizeBytes)}")
            } else if (result is ExportResult.Failure) {
                exportProgressState = null
                showToast(result.message, isError = true)
            }
        }
    }

    fun clearExportResult() {
        lastExportState = null
        exportProgressState = null
    }

    fun cancelExport() {
        platform.cancelExport()
        exportJob?.cancel()
        exportProgressState = null
        showToast("已取消导出")
    }

    fun deleteProject(summary: ProjectSummary) {
        scope.launch {
            if (platform.deleteProject(summary.id)) {
                refreshProjects()
                showToast("已移到废纸篓")
            } else {
                showToast("删除失败", isError = true)
            }
        }
    }

    fun duplicateProject(summary: ProjectSummary) {
        scope.launch {
            if (platform.duplicateProject(summary.id)) {
                refreshProjects()
                showToast("已创建副本")
            } else {
                showToast("复制失败", isError = true)
            }
        }
    }

    fun renameProject(title: String) {
        val project = project ?: return
        if (title.isBlank() || title == project.title) return
        updateProject("重命名项目") { it.copy(title = title.take(200)) }
    }

    fun showToast(text: String, isError: Boolean = false) {
        val message = ToastMessage(++toastCounter, text, isError)
        toast = message
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(2600)
            if (toast?.id == message.id) toast = null
        }
    }

    fun clearToast() {
        toast = null
    }

    fun onRecordingSaved(loaded: LoadedProject) {
        openRecordingResult(loaded)
        showToast("录制已保存")
    }

    fun nearestSnap(time: Double): Double {
        val project = project ?: return time
        if (!snappingEnabled) return time
        val secondsPerPixel = 1.0 / timelinePixelsPerSecond
        val threshold = 7f * secondsPerPixel
        return TimelineEditor.snapTime(time, TimelineEditor.clipBoundaries(project, sourceDuration), threshold)
    }
}

private const val AutosaveDelayMs = 1_200L
