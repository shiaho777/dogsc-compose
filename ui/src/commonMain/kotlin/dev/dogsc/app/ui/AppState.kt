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
import kotlinx.coroutines.CancellationException
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
    /** Multi-select batch (upstream ⇧/⌘ click). Each entry is a single-clip selection. */
    data class Multi(val items: List<EditorSelection>) : EditorSelection
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
    var trashedProjects by mutableStateOf<List<ProjectSummary>>(emptyList())
        private set
    var isLoadingProjects by mutableStateOf(true)
        private set
    var toast by mutableStateOf<ToastMessage?>(null)
    var showOnboarding by mutableStateOf(!platform.hasSeenOnboarding())
        private set
    var requestedTour by mutableStateOf<String?>(null)
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
    /** True while the user drags the playhead/ruler; shows a live time badge on the timeline. */
    var isScrubbing by mutableStateOf(false)
    var selection by mutableStateOf<EditorSelection>(EditorSelection.None)
        private set
    var inspectorTab by mutableStateOf(InspectorTab.CANVAS)
    var canvasPane by mutableStateOf(CanvasPane.BACKGROUND)
    /** Bumped when a canvas/timeline selection should auto-open the contextual inspector sheet. */
    var inspectorPanelRequest by mutableStateOf(0)
        private set
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
    var appearance by mutableStateOf(dev.dogsc.app.platform.AppAppearance.SYSTEM)
        private set
    var english by mutableStateOf(false)
        private set

    private var previewJob: Job? = null
    private var pendingPreviewTime: Double? = null
    private var previewMediaSignature: String? = null
    /** Bumped whenever the editor changes project; stale async loads must never publish into the new one. */
    private var mediaGeneration = 0L
    /** Invalidates an in-flight open/import when the user navigates away or picks another project. */
    private var projectLoadToken = 0L
    private var projectsRefreshToken = 0L
    private var exportGeneration = 0L
    private var exportInFlight = false
    private var preferencesGeneration = 0L
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
            if (w != null && h != null && w > 0 && h > 0) return w.toDouble() / h
            return loadedProject?.cameraAspectRatio
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
            appearance = prefs.appearance
            english = prefs.english
            dev.dogsc.app.ui.Loc.applyEnglish(english)
        }
    }

    fun updateEnglish(value: Boolean) {
        english = value
        dev.dogsc.app.ui.Loc.applyEnglish(value)
        persistPreferences()
    }

    fun updateAppearance(value: dev.dogsc.app.platform.AppAppearance) {
        appearance = value
        persistPreferences()
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
            appearance = appearance,
            english = english,
        )
        val generation = ++preferencesGeneration
        scope.launch {
            if (generation == preferencesGeneration) {
                runCatching { platform.savePreferences(snapshot) }
            }
        }
    }

    fun saveScenePreset(name: String) {
        val project = project ?: return
        val safeCanvas = when (project.canvas.backgroundSource) {
            // A relative project-image path would point into whichever project applies the
            // preset later; presets carry style, not another project's media.
            is dev.dogsc.core.model.BackgroundSource.ProjectImage,
            is dev.dogsc.core.model.BackgroundSource.ProjectVideo,
            -> project.canvas.copy(backgroundSource = dev.dogsc.core.model.BackgroundSource.SafeFallback)
            else -> project.canvas
        }
        val preset = dev.dogsc.app.platform.ScenePreset(
            id = dev.dogsc.core.model.randomId(),
            name = name.take(60),
            canvas = safeCanvas,
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
        val token = ++projectsRefreshToken
        scope.launch {
            // The coroutine can start after a newer refresh already finished.
            if (token != projectsRefreshToken) return@launch
            isLoadingProjects = true
            val fresh = runCatching { platform.listProjects() }
                .getOrDefault(emptyList())
                .sortedByDescending { it.updatedAtEpochMs }
            val trash = runCatching { platform.loadTrash() }.getOrDefault(emptyList())
            // Two refreshes can overlap (autosave, delete, import); the newest must win.
            if (token != projectsRefreshToken) return@launch
            projects = fresh
            trashedProjects = trash
            isLoadingProjects = false
        }
    }

    fun restoreProject(summary: ProjectSummary) {
        scope.launch {
            isBusy = true
            busyMessage = "正在恢复项目"
            val restored = runCatching { platform.restoreProject(summary.id) }.getOrDefault(false)
            isBusy = false
            if (restored) {
                refreshProjects()
                showToast("已恢复项目")
            } else {
                showToast("恢复失败", isError = true)
            }
        }
    }

    fun clearTrash() {
        scope.launch {
            isBusy = true
            busyMessage = "正在清空回收站"
            val cleared = runCatching { platform.clearTrash() }.getOrDefault(false)
            isBusy = false
            refreshProjects()
            if (cleared) showToast("回收站已清空") else showToast("清空失败", isError = true)
        }
    }

    fun requestTour(kind: String) {
        requestedTour = kind
    }

    fun consumeTourRequest() {
        requestedTour = null
    }

    fun finishOnboarding() {
        platform.markOnboardingSeen()
        showOnboarding = false
    }

    fun reopenOnboarding() {
        showOnboarding = true
    }

    fun openLibrary() {
        projectLoadToken++
        isBusy = false
        busyMessage = ""
        stopPlayback()
        flushPendingSave()
        resetEditorMediaState()
        screen = AppScreen.Library
        session = null
        loadedProject = null
        selection = EditorSelection.None
        refreshProjects()
    }

    /**
     * Drops every frame/audio resource owned by the previous project and cancels its async loads.
     * Decoder callbacks are not always cancellable, so [mediaGeneration] also guards publication.
     */
    private fun resetEditorMediaState() {
        mediaGeneration++
        previewJob?.cancel()
        previewJob = null
        waveformJob?.cancel()
        waveformJob = null
        pendingPreviewTime = null
        previewFrame = null
        cameraPreviewFrame = null
        backgroundPreviewFrame = null
        stickerFrames = emptyMap()
        filmstripFrames = emptyList()
        waveform = null
        isPreviewLoading = false
        previewMediaSignature = null
        showExportSheet = false
        showScenePresetSheet = false
        showMarkersSheet = false
        showShortcutSheet = false
        isPlaying = false
        activeScenePresetId = null
        platform.releasePreviewAudio()
        platform.releasePreviewFrames()
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
        val token = ++projectLoadToken
        stopPlayback()
        scope.launch {
            if (token != projectLoadToken) return@launch
            isBusy = true
            busyMessage = "正在载入项目"
            val loaded = runCatching { platform.loadProject(summary.id) }.getOrNull()
            if (token != projectLoadToken) return@launch
            isBusy = false
            if (loaded == null) {
                showToast("无法打开项目：文件缺失或已损坏", isError = true)
                refreshProjects()
                return@launch
            }
            resetEditorMediaState()
            session = EditorSession(loaded.project, loaded.sourceDuration)
            loadedProject = loaded
            playhead = 0.0
            selection = EditorSelection.None
            inspectorTab = InspectorTab.CANVAS
            canvasPane = CanvasPane.BACKGROUND
            screen = AppScreen.Editor(loaded.project.id)
            requestPreviewFrame(0.0)
            refreshStaticMedia(force = true)
            val generation = mediaGeneration
            scope.launch {
                val frames = platform.loadFilmstrip(loaded.project, 12)
                if (generation == mediaGeneration) filmstripFrames = frames
            }
        }
    }

    fun importVideo(uri: String) {
        val token = ++projectLoadToken
        stopPlayback()
        scope.launch {
            if (token != projectLoadToken) return@launch
            isBusy = true
            busyMessage = "正在导入视频"
            val loaded = runCatching { platform.importVideo(uri) }.getOrNull()
            if (token != projectLoadToken) return@launch
            isBusy = false
            if (loaded == null) {
                showToast("无法导入这个视频", isError = true)
                return@launch
            }
            resetEditorMediaState()
            refreshProjects()
            session = EditorSession(loaded.project, loaded.sourceDuration)
            loadedProject = loaded
            playhead = 0.0
            selection = EditorSelection.None
            inspectorTab = InspectorTab.CANVAS
            canvasPane = CanvasPane.BACKGROUND
            screen = AppScreen.Editor(loaded.project.id)
            requestPreviewFrame(0.0)
            refreshStaticMedia(force = true)
            val generation = mediaGeneration
            scope.launch {
                val frames = platform.loadFilmstrip(loaded.project, 12)
                if (generation == mediaGeneration) filmstripFrames = frames
            }
            showToast("已导入「${loaded.project.title}」")
        }
    }

    fun importSticker(uri: String) {
        val project = project ?: return
        val projectId = project.id
        scope.launch {
            isBusy = true
            busyMessage = "正在导入贴图"
            val path = platform.importSticker(projectId, uri)
            isBusy = false
            if (path == null) {
                showToast("无法导入这张图片", isError = true)
                return@launch
            }
            if (project?.id != projectId) return@launch
            updateProject("添加贴图") { current ->
                TimelineEditor.addSticker(
                    current,
                    dev.dogsc.core.model.OverlayTiming(
                        effectStartTime(playhead),
                        effectDuration(effectStartTime(playhead)),
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
        val projectId = project.id
        scope.launch {
            isBusy = true
            busyMessage = if (isVideo) "正在导入背景视频" else "正在导入背景图片"
            val path = platform.importBackground(projectId, uri, isVideo)
            isBusy = false
            if (path == null) {
                showToast("无法导入背景素材", isError = true)
                return@launch
            }
            if (project?.id != projectId) return@launch
            updateProject("更换背景") { current ->
                current.copy(canvas = current.canvas.copy(
                    backgroundSource = if (isVideo) {
                        dev.dogsc.core.model.BackgroundSource.ProjectVideo(path)
                    } else {
                        dev.dogsc.core.model.BackgroundSource.ProjectImage(path)
                    },
                ))
            }
            // refreshStaticMedia() was triggered by updateProject; loading the old project here
            // would race it and briefly publish the previous background.
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
            // Clicks are recorded against the source clock; zoom clips live on the edited
            // output clock. Mapping them here keeps auto motion aligned after cuts/retimes.
            val map = TimelineEditor.timelineMap(current, sourceDuration)
            val outputEvents = map.mapPointerEventSegments(events).flatMap { it.events }
            val generated = dev.dogsc.core.model.AutoZoomPlanner.clipped(
                dev.dogsc.core.model.AutoZoomPlanner.makeAnimations(
                    outputEvents,
                    zoomScale = current.motion.defaultZoomScale ?: 1.6,
                    easing = current.motion.defaultZoomEasing,
                    transitionDuration = current.motion.defaultZoomTransitionDuration,
                ),
                map.outputDuration,
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

    /** Adding at the very end must still create a usable clip instead of a dead button. */
    fun effectStartTime(requested: Double): Double =
        requested.coerceIn(0.0, max(outputDuration - 0.4, 0.0))

    fun effectDuration(start: Double, default: Double = 3.0): Double =
        min(default, max(outputDuration - start, 0.2))

    fun addMosaicAt(time: Double, style: dev.dogsc.core.model.MosaicEffectStyle) {
        val start = effectStartTime(time)
        val duration = effectDuration(start)
        updateProject(if (style == dev.dogsc.core.model.MosaicEffectStyle.BLUR) "添加柔化" else "添加突出") { current ->
            TimelineEditor.addMosaic(
                current,
                dev.dogsc.core.model.OverlayTiming(start, duration),
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
        projectLoadToken++
        stopPlayback()
        resetEditorMediaState()
        session = EditorSession(loaded.project, loaded.sourceDuration)
        loadedProject = loaded
        playhead = 0.0
        selection = EditorSelection.None
        inspectorTab = InspectorTab.CANVAS
        canvasPane = CanvasPane.BACKGROUND
        screen = AppScreen.Editor(loaded.project.id)
        requestPreviewFrame(0.0)
        refreshStaticMedia(force = true)
        val generation = mediaGeneration
        scope.launch {
            val frames = platform.loadFilmstrip(loaded.project, 12)
            if (generation == mediaGeneration) filmstripFrames = frames
        }
        refreshProjects()
    }

    fun saveProject(showConfirmation: Boolean = false) {
        autosaveJob?.cancel()
        val project = project ?: return
        scope.launch { persist(project, showConfirmation) }
    }

    /** Writes pending edits immediately: leaving the editor or the app going to the background. */
    fun flushPendingSave() {
        // A drag still open when the app backgrounds must settle into history first, or the
        // process can be killed mid-gesture and lose the whole drag.
        if (session?.isGestureActive == true) endGesture("调整")
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
        // While waiting for the mutex, newer edits may have arrived. Writing the captured
        // snapshot would then overwrite them with stale data if saves complete out of order.
        val written = saveMutex.withLock {
            val latest = session?.project?.takeIf { it.id == project.id } ?: project
            latest to runCatching { platform.saveProject(latest) }.getOrDefault(false)
        }
        val saved = written.second
        val persistedProject = written.first
        if (saved) {
            val current = session
            if (current != null && current.project === persistedProject) {
                current.markSaved()
                sessionRevision++
            }
            // While editing, a library refresh would probe every project's media again.
            // The library is refreshed when the editor closes (openLibrary).
            if (screen == AppScreen.Library) refreshProjects()
            if (showConfirmation) showToast("项目已保存")
        } else {
            showToast("保存失败，请检查存储空间", isError = true)
        }
    }

    fun updateProject(label: String, transform: (RecorderProject) -> RecorderProject?) {
        val session = session ?: return
        val committed = try {
            session.commit(label, transform)
        } catch (error: IllegalArgumentException) {
            // Core invariants use require(); a malformed edit must become a toast, not a crash.
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

    /** Opens a transient drag (canvas manipulation, timeline edge trim, position pad). */
    fun beginGesture() {
        session?.beginGesture()
    }

    /**
     * Mid-drag update: bumps the revision and requests a single-flight preview frame, but
     * skips waveform/audio/static-media reloads and autosave until the drag ends.
     */
    fun previewGesture(transform: (RecorderProject) -> RecorderProject?) {
        val session = session ?: return
        if (session.previewGesture(transform)) {
            sessionRevision++
            requestPreviewFrame(playhead)
        }
    }

    /** Closes the drag as one undo entry, then runs the normal post-edit refresh + autosave. */
    fun endGesture(label: String) {
        val session = session ?: return
        val changed = session.endGesture(label)
        if (changed) {
            sessionRevision++
            onProjectChanged()
        } else {
            // Drag ended at baseline: still flush any dirty state from earlier discrete edits.
            scheduleAutosave()
        }
    }

    /** Abandons a drag (e.g. system gesture stole the pointer); restores the baseline frame. */
    fun cancelGesture() {
        val session = session ?: return
        if (!session.isGestureActive) return
        session.cancelGesture()
        sessionRevision++
        requestPreviewFrame(playhead)
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
        // During a drag, waveform/audio rebuilds and autosave are deferred to endGesture;
        // only the single-flight preview frame keeps up with the pointer.
        if (session?.isGestureActive == true) return
        if (isPlaying) project?.let { platform.refreshPreviewAudioVolume(it, playhead) }
        if (showWaveform) requestWaveform()
        scheduleAutosave()
    }

    fun toggleWaveform() {
        showWaveform = !showWaveform
        if (showWaveform && waveform == null) requestWaveform()
    }

    fun requestWaveform() {
        val project = project ?: return
        val projectId = project.id
        val generation = mediaGeneration
        waveformJob?.cancel()
        waveformJob = scope.launch {
            val values = platform.loadWaveform(project, sourceDuration, 700)
            if (generation == mediaGeneration && project?.id == projectId) waveform = values
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
        if (isPlaying) {
            project?.let { platform.seekPreviewAudio(it, sourceDuration, clamped) }
            // The playback loop counts from its start origin; without restarting it the
            // playhead would snap back to the pre-seek position on the next tick.
            startPlaybackClock(clamped)
        }
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
        startPlaybackClock(playhead)
    }

    private fun startPlaybackClock(origin: Double) {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            // Monotonic clock instead of "playhead += 0.016": no drift and no dependence on
            // how long each frame's work takes.
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
        val projectId = project.id
        val generation = mediaGeneration
        // Coalesce requests: while a frame is decoding, keep only the newest target instead of
        // cancelling and restarting a decode (which leaves the native retriever working anyway).
        pendingPreviewTime = time
        if (previewJob?.isActive == true) return
        previewJob = scope.launch {
            while (true) {
                val target = pendingPreviewTime ?: break
                pendingPreviewTime = null
                if (generation != mediaGeneration || project?.id != projectId) break
                val current = project ?: break
                isPreviewLoading = previewFrame == null
                val sourceDuration = sourceDuration
                // The playhead is in output time. Cuts and speed changes move the matching
                // source frame, so decoding at the raw playhead made preview disagree with export.
                val screenTime = runCatching {
                    dev.dogsc.core.model.TimelineMap(
                        current.timeline.sourceSequence,
                        sourceDuration,
                    ).sourceTimeAtOutput(target)
                }.getOrNull() ?: target
                val frame = platform.loadThumbnail(current, screenTime)
                val cameraTime = previewCameraSourceTime(current, sourceDuration, target)
                val isVideoBackground =
                    current.canvas.backgroundSource is dev.dogsc.core.model.BackgroundSource.ProjectVideo
                val backgroundFrame = if (isVideoBackground) {
                    platform.loadBackgroundThumbnail(current, target)
                } else {
                    null
                }
                if (pendingPreviewTime == null && generation == mediaGeneration && project?.id == projectId) {
                    if (frame != null) previewFrame = frame
                    cameraPreviewFrame = if (cameraTime != null && !current.camera.isHidden) {
                        platform.loadCameraThumbnail(current, cameraTime)
                    } else {
                        null
                    }
                    if (backgroundFrame != null) backgroundPreviewFrame = backgroundFrame
                    isPreviewLoading = false
                }
                if (pendingPreviewTime == null) break
            }
        }
    }

    /** Camera leads/offsets and timeline cuts map the same way as the export camera plan. */
    private fun previewCameraSourceTime(
        project: RecorderProject,
        sourceDuration: Double,
        outputTime: Double,
    ): Double? {
        val reference = project.media?.camera ?: return null
        return runCatching {
            val map = dev.dogsc.core.model.TimelineMap(project.timeline.sourceSequence, sourceDuration)
            val available = reference.sourceEndTime ?: reference.durationSeconds ?: sourceDuration
            val placement = dev.dogsc.core.model.MediaTimelinePlacement.from(
                reference,
                sourceAvailableDuration = available,
                timelineDuration = sourceDuration,
            )
            dev.dogsc.core.model.TimelineMediaPlan.auxiliary(
                map,
                placement,
                syncAnchors = reference.syncAnchors,
            ).sourceTimeAtOutput(outputTime)
        }.getOrNull()
    }

    /**
     * Stickers and custom backgrounds only change with the project, never with the playhead;
     * loading them once per edit keeps playback from re-decoding them every frame.
     */
    fun refreshStaticMedia(force: Boolean = false) {
        val current = project ?: return
        val projectId = current.id
        val generation = mediaGeneration
        val signature = buildString {
            append(current.canvas.backgroundSource)
            append('|')
            current.timeline.stickerClips.forEach { append(it.relativePath).append(',') }
        }
        if (!force && signature == previewMediaSignature) return
        previewMediaSignature = signature
        scope.launch {
            val background = if (current.canvas.backgroundSource.usesWallpaperMedia) {
                platform.loadBackgroundBitmap(current)
            } else {
                null
            }
            val stickers = mutableMapOf<String, ImageBitmap>()
            for (clip in current.timeline.stickerClips) {
                platform.loadStickerBitmap(current, clip.relativePath)?.let { stickers[clip.relativePath] = it }
            }
            // A newer edit may have started its own load while this one was decoding.
            if (
                generation == mediaGeneration &&
                project?.id == projectId &&
                previewMediaSignature == signature
            ) {
                backgroundPreviewFrame = background
                stickerFrames = stickers
            }
        }
    }

    fun select(selection: EditorSelection) {
        this.selection = selection
    }

    /**
     * Mobile-native selection: selects the element AND routes the inspector to its tab, then
     * requests the contextual bottom panel so settings appear without hunting through tabs
     * (the desktop "click then find the right rail" habit does not transfer to touch).
     */
    fun selectAndInspect(selection: EditorSelection) {
        this.selection = selection
        inspectorTab = when (selection) {
            is EditorSelection.Zoom, is EditorSelection.ScreenMotion -> InspectorTab.MOTION
            is EditorSelection.CameraMotion -> InspectorTab.CAMERA
            is EditorSelection.Mosaic, is EditorSelection.Sticker -> InspectorTab.CANVAS
            else -> inspectorTab
        }
        if (selection !is EditorSelection.None) inspectorPanelRequest++
    }

    /**
     * Additive multi-select (upstream ⇧/⌘ click): toggles [item] in the current selection.
     * A single selection becomes a Multi; toggling the last item collapses back to None.
     */
    fun toggleMultiSelect(item: EditorSelection) {
        if (item is EditorSelection.None || item is EditorSelection.Multi) return
        val currentItems = when (val current = selection) {
            is EditorSelection.Multi -> current.items
            EditorSelection.None -> emptyList()
            else -> listOf(current)
        }
        val next = if (currentItems.any { sameSelection(it, item) }) {
            currentItems.filterNot { sameSelection(it, item) }
        } else {
            currentItems + item
        }
        selection = when (next.size) {
            0 -> EditorSelection.None
            1 -> next.first()
            else -> EditorSelection.Multi(next)
        }
    }

    private fun sameSelection(a: EditorSelection, b: EditorSelection): Boolean = a == b

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
            is EditorSelection.Multi -> {
                val zoomIds = current.items.filterIsInstance<EditorSelection.Zoom>().map { it.clipId }.toSet()
                val screenIds = current.items.filterIsInstance<EditorSelection.ScreenMotion>().map { it.clipId }.toSet()
                val cameraIds = current.items.filterIsInstance<EditorSelection.CameraMotion>().map { it.clipId }.toSet()
                val mosaicIds = current.items.filterIsInstance<EditorSelection.Mosaic>().map { it.clipId }.toSet()
                val stickerIds = current.items.filterIsInstance<EditorSelection.Sticker>().map { it.clipId }.toSet()
                val markerIds = current.items.filterIsInstance<EditorSelection.Marker>().map { it.markerId }.toSet()
                updateProject("删除选中片段") { p ->
                    val trimmed = TimelineEditor.deleteClips(p, zoomIds, screenIds, cameraIds, mosaicIds, stickerIds)
                    if (markerIds.isEmpty()) trimmed
                    else trimmed.copy(recordingMarkers = trimmed.recordingMarkers.filterNot { it.id in markerIds })
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
        val previousSourceEnd = map.segments
            .filter { it.id != segmentId && it.sourceEnd <= segment.sourceStart + 0.0001 }
            .maxOfOrNull { it.sourceEnd } ?: 0.0
        val nextSourceStart = map.segments
            .filter { it.id != segmentId && it.sourceStart >= segment.sourceEnd - 0.0001 }
            .minOfOrNull { it.sourceStart } ?: sourceDuration
        if (leading) {
            val newStart = (segment.sourceStart + deltaSeconds * segment.playbackRate)
                .coerceIn(previousSourceEnd, segment.sourceEnd - 0.08 * segment.playbackRate)
            val transform: (RecorderProject) -> RecorderProject = { current ->
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
            // During an edge drag each event streams through the gesture channel; the whole
            // drag settles as one undo entry on release. Discrete trims still commit normally.
            if (session?.isGestureActive == true) previewGesture(transform) else updateProject("裁切片段", transform)
        } else {
            val newEnd = (segment.sourceEnd + deltaSeconds * segment.playbackRate)
                .coerceIn(segment.sourceStart + 0.08 * segment.playbackRate, nextSourceStart)
            val transform: (RecorderProject) -> RecorderProject = { current ->
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
            if (session?.isGestureActive == true) previewGesture(transform) else updateProject("裁切片段", transform)
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

    /**
     * Ripple-trim the primary segment under the playhead (upstream Q/W): Q removes from the
     * segment start to the playhead, W from the playhead to the segment end. The gap closes
     * (ripple) so the timeline stays continuous.
     */
    fun rippleTrimToPlayhead(leading: Boolean) {
        val project = project ?: return
        val map = TimelineEditor.timelineMap(project, sourceDuration)
        val segment = map.segments.firstOrNull { playhead >= it.outputStart && playhead <= it.outputEnd } ?: run {
            showToast("播放头不在主片段上", isError = true)
            return
        }
        val from = if (leading) segment.outputStart else playhead
        val to = if (leading) playhead else segment.outputEnd
        if (to - from < 0.04) {
            showToast("没有可修剪的范围", isError = true)
            return
        }
        updateProject(if (leading) "波纹修剪片段头" else "波纹修剪片段尾") {
            TimelineEditor.deletePrimaryRange(it, sourceDuration, from, to)
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
        if (exportInFlight) {
            showToast("上一个导出仍在完成，请稍候", isError = true)
            return
        }
        val generation = ++exportGeneration
        exportOwnerId = project.id
        lastExportState = null
        exportProgressState = ExportProgress(0f, "正在准备素材")
        exportInFlight = true
        exportJob = scope.launch {
            try {
            val result = try {
                platform.export(
                    ExportRequest(
                        project = project,
                        sourceDuration = sourceDuration,
                        outputUri = outputUri,
                        audioOnly = audioOnly,
                        trimStart = trimStart,
                        trimEnd = trimEnd,
                    ),
                ) { progress ->
                    scope.launch {
                        if (generation == exportGeneration &&
                            exportJob?.isActive == true &&
                            lastExportState == null
                        ) {
                            exportProgressState = progress
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ExportResult.Failure(error.message ?: "导出失败")
            }
            if (generation != exportGeneration) return@launch
            lastExportState = result
            when (result) {
                is ExportResult.Success -> {
                    exportProgressState = null
                    if (exportSoundEnabled) platform.playExportSound()
                    showToast(
                        if (result.width > 0) {
                            val fps = if (result.frameRate > 0) " · ${result.frameRate} FPS" else ""
                            "已导出 ${result.width}×${result.height}$fps · ${formatFileSize(result.sizeBytes)}"
                        } else {
                            "已导出 ${formatFileSize(result.sizeBytes)}"
                        },
                    )
                }
                is ExportResult.Failure -> {
                    exportProgressState = null
                    showToast(result.message, isError = true)
                }
                ExportResult.Cancelled -> {
                    exportProgressState = null
                }
            }
            } finally {
                exportInFlight = false
            }
        }
    }

    fun clearExportResult() {
        lastExportState = null
        exportProgressState = null
    }

    fun cancelExport() {
        exportGeneration++
        platform.cancelExport()
        exportJob?.cancel()
        exportJob = null
        exportProgressState = null
        showToast("已取消导出")
    }

    fun deleteProject(summary: ProjectSummary) {
        if (exportInFlight && exportOwnerId == summary.id) {
            showToast("导出进行中，暂不能删除该项目", isError = true)
            return
        }
        scope.launch {
            isBusy = true
            busyMessage = "正在删除项目"
            val deleted = runCatching { platform.deleteProject(summary.id) }.getOrDefault(false)
            isBusy = false
            if (deleted) {
                refreshProjects()
                showToast("已移到回收站")
            } else {
                showToast("删除失败", isError = true)
            }
        }
    }

    /**
     * Editor deletion must not flush a pending edit after the folder moved to the trash.
     * The repository tombstones the id, and this path never calls openLibrary's save flush.
     */
    fun deleteCurrentProject() {
        val summary = loadedProject?.summary ?: return
        if (exportInFlight && exportOwnerId == summary.id) {
            showToast("导出进行中，暂不能删除该项目", isError = true)
            return
        }
        autosaveJob?.cancel()
        autosaveJob = null
        scope.launch {
            isBusy = true
            busyMessage = "正在删除项目"
            val deleted = runCatching { platform.deleteProject(summary.id) }.getOrDefault(false)
            isBusy = false
            if (deleted) {
                projectLoadToken++
                resetEditorMediaState()
                session = null
                loadedProject = null
                screen = AppScreen.Library
                refreshProjects()
                showToast("已移到回收站")
            } else {
                // Keep the unsaved edits safe if the folder could not be moved.
                scheduleAutosave()
                showToast("删除失败", isError = true)
            }
        }
    }

    fun duplicateProject(summary: ProjectSummary) {
        scope.launch {
            isBusy = true
            busyMessage = "正在复制项目"
            // Duplicating the open project from the editor must copy today's edits, not the
            // last autosave snapshot.
            val open = project?.takeIf { it.id == summary.id }
            if (open != null) {
                val saved = saveMutex.withLock { platform.saveProject(open) }
                if (!saved) {
                    isBusy = false
                    showToast("复制前保存失败", isError = true)
                    return@launch
                }
            }
            val duplicated = runCatching { platform.duplicateProject(summary.id) }.getOrDefault(false)
            isBusy = false
            if (duplicated) {
                refreshProjects()
                showToast("已创建副本")
            } else {
                showToast("复制失败", isError = true)
            }
        }
    }

    fun exportProjectPackage(summary: ProjectSummary, outputUri: String) {
        scope.launch {
            isBusy = true
            busyMessage = "正在导出项目"
            val exported = runCatching { platform.exportProjectPackage(summary.id, outputUri) }.getOrDefault(false)
            isBusy = false
            if (exported) showToast("项目已导出") else showToast("导出失败", isError = true)
        }
    }

    fun importProjectPackage(uri: String) {
        scope.launch {
            isBusy = true
            busyMessage = "正在导入项目"
            val id = runCatching { platform.importProjectPackage(uri) }.getOrNull()
            isBusy = false
            if (id != null) {
                refreshProjects()
                showToast("项目已导入")
            } else {
                showToast("导入失败", isError = true)
            }
        }
    }

    fun importProjectDirectory(uri: String) {
        scope.launch {
            isBusy = true
            busyMessage = "正在导入项目"
            val id = runCatching { platform.importProjectDirectory(uri) }.getOrNull()
            isBusy = false
            if (id != null) {
                refreshProjects()
                showToast("项目已导入")
            } else {
                showToast("导入失败", isError = true)
            }
        }
    }

    fun renameProject(summary: ProjectSummary, title: String) {
        val clean = title.trim().take(200)
        if (clean.isBlank() || clean == summary.title) return
        scope.launch {
            if (runCatching { platform.renameProject(summary.id, clean) }.getOrDefault(false)) {
                refreshProjects()
                showToast("项目已重命名")
            } else {
                showToast("重命名失败", isError = true)
            }
        }
    }

    fun renameProject(title: String) {
        val project = project ?: return
        if (title.isBlank() || title == project.title) return
        updateProject("重命名项目") { it.copy(title = title.take(200)) }
    }

    fun showToast(text: String, isError: Boolean = false) {
        val localized = dev.dogsc.app.ui.Loc.t(text)
        val message = ToastMessage(++toastCounter, localized, isError)
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
        // Touch-enlarged capture threshold (upstream 7px mouse -> 12dp touch).
        val threshold = 12f * secondsPerPixel
        val snapped = TimelineEditor.snapTime(time, TimelineEditor.clipBoundaries(project, sourceDuration), threshold)
        if (snapped != time) triggerSnapPulse(snapped)
        return snapped
    }

    /** Clipboard for clip copy/paste (upstream ⌘C/⌘V/⌘⇧V). Holds a zoom clip id snapshot. */
    private var copiedZoomId: String? = null

    /** Copies the selected zoom clip's attributes to the clipboard. */
    fun copySelection() {
        when (val current = selection) {
            is EditorSelection.Zoom -> {
                copiedZoomId = current.clipId
                showToast("已复制缩放片段")
            }
            else -> showToast("请先选中一个缩放片段", isError = true)
        }
    }

    /** Pastes the copied zoom clip at the playhead as a new clip. */
    fun pasteClip() {
        val sourceId = copiedZoomId ?: run {
            showToast("剪贴板为空", isError = true)
            return
        }
        updateProject("粘贴缩放片段") {
            TimelineEditor.duplicateZoom(it, sourceId, playhead, outputDuration)
        }
    }

    /** Pastes only the copied clip's attributes onto the selected zoom clip (upstream ⌘⇧V). */
    fun pasteAttributes() {
        val sourceId = copiedZoomId ?: run {
            showToast("剪贴板为空", isError = true)
            return
        }
        val targetId = (selection as? EditorSelection.Zoom)?.clipId ?: run {
            showToast("请先选中目标缩放片段", isError = true)
            return
        }
        updateProject("粘贴片段属性") {
            TimelineEditor.transferZoomAttributes(it, sourceId, targetId)
        }
    }

    /**
     * Timeline drag edge auto-scroll: -1 scroll left, +1 scroll right, 0 idle. Set by the drag
     * handler when the pointer nears the viewport edge; consumed by a scroll loop in the view.
     */
    var timelineDragEdge by mutableStateOf(0)

    /** Magnetic-snap pulse ring (upstream EditorTimelineMagneticSnap): time + monotonic id. */
    var snapPulseTime by mutableStateOf<Double?>(null)
        private set
    var snapPulseId by mutableStateOf(0L)
        private set

    fun triggerSnapPulse(time: Double) {
        snapPulseTime = time
        snapPulseId++
    }
}

private const val AutosaveDelayMs = 1_200L
