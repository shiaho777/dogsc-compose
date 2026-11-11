package dev.dogsc.app.android

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.dogsc.app.android.export.AndroidExporter
import dev.dogsc.app.platform.AppAppearance
import dev.dogsc.app.platform.AppPreferences
import dev.dogsc.app.platform.DogScPlatform
import dev.dogsc.app.platform.ExportProgress
import dev.dogsc.app.platform.ExportRequest
import dev.dogsc.app.platform.ExportResult
import dev.dogsc.app.platform.LoadedProject
import dev.dogsc.app.platform.MediaProbe
import dev.dogsc.app.platform.ProjectSummary
import dev.dogsc.app.platform.RecordingOptions
import dev.dogsc.app.platform.ScenePreset
import dev.dogsc.core.model.RecorderProject
import java.io.File

class AndroidDogScPlatform(
    private val context: Context,
    private val repository: AndroidProjectRepository,
) : DogScPlatform {
    private val exporter = AndroidExporter(context, repository)
    private val waveformLoader = AndroidWaveformLoader(repository)
    private val previewAudio = AndroidPreviewAudio(context, repository)

    private fun prefs() = context.getSharedPreferences("dogsc_prefs", Context.MODE_PRIVATE)

    override val platformName: String = "Android ${Build.VERSION.RELEASE}"
    override val supportsSystemAudio: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    override val supportsCamera: Boolean = true
    override val supportsTouchCapture: Boolean = true
    override val supportsExport: Boolean = true
    override val supportsScreenRecording: Boolean = true
    override val supportsHevcCapture: Boolean by lazy {
        runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .any { it.isEncoder && it.supportedTypes.any { type -> type.equals("video/hevc", true) } }
        }.getOrDefault(false)
    }

    override suspend fun listProjects(): List<ProjectSummary> = repository.listProjects()

    override suspend fun loadProject(id: String): LoadedProject? = repository.loadProject(id)

    override suspend fun saveProject(project: RecorderProject): Boolean = repository.saveProject(project)

    override suspend fun deleteProject(id: String): Boolean = repository.deleteProject(id)

    override suspend fun loadTrash(): List<ProjectSummary> = repository.loadTrash()

    override suspend fun restoreProject(id: String): Boolean = repository.restoreProject(id)

    override suspend fun clearTrash(): Boolean = repository.clearTrash()

    override suspend fun exportProjectPackage(id: String, outputUri: String): Boolean =
        repository.exportProjectPackage(id, outputUri)

    override suspend fun importProjectPackage(uri: String): String? =
        repository.importProjectPackage(uri)

    override suspend fun duplicateProject(id: String): Boolean = repository.duplicateProject(id)

    override suspend fun renameProject(id: String, title: String): Boolean = repository.renameProject(id, title)

    override suspend fun importVideo(uri: String): LoadedProject? = repository.importVideo(uri)

    override suspend fun importSticker(projectId: String, uri: String): String? =
        repository.importSticker(projectId, uri)

    override suspend fun importBackground(projectId: String, uri: String, isVideo: Boolean): String? =
        repository.importBackground(projectId, uri, isVideo)

    override suspend fun loadThumbnail(project: RecorderProject, atTime: Double): ImageBitmap? =
        repository.loadThumbnail(project, atTime)

    override suspend fun loadCameraThumbnail(project: RecorderProject, atTime: Double): ImageBitmap? =
        repository.loadCameraThumbnail(project, atTime)

    override suspend fun loadStickerBitmap(project: RecorderProject, relativePath: String): ImageBitmap? =
        repository.loadStickerBitmap(project, relativePath)

    override suspend fun loadBackgroundBitmap(project: RecorderProject): ImageBitmap? =
        repository.loadBackgroundBitmap(project)

    override suspend fun loadWaveform(project: RecorderProject, sourceDuration: Double, buckets: Int): FloatArray? =
        waveformLoader.load(project, sourceDuration, buckets)

    override suspend fun loadFilmstrip(project: RecorderProject, count: Int): List<ImageBitmap> {
        val path = project.media?.screen?.relativePath ?: return emptyList()
        val file = File(repository.projectDirectory(project.id), path)
        if (!file.exists()) return emptyList()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                if (durationMs <= 0) return@withContext emptyList()
                (0 until count).mapNotNull { index ->
                    val timeUs = (durationMs * 1000L * index / count.coerceAtLeast(1))
                    retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.asImageBitmap()
                }
            } catch (_: Throwable) {
                emptyList()
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    override suspend fun loadScenePresets(): List<ScenePreset> = repository.loadScenePresets()

    override suspend fun saveScenePresets(presets: List<ScenePreset>) {
        repository.saveScenePresets(presets)
    }

    override suspend fun loadProjectThumbnail(summary: ProjectSummary): ImageBitmap? {
        val path = summary.thumbnailPath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
    }

    override suspend fun probeMedia(project: RecorderProject): MediaProbe? = repository.probe(project)

    override suspend fun export(
        request: ExportRequest,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult = exporter.export(request, onProgress)

    override fun cancelExport() {
        exporter.cancel()
    }

    override fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun hasSeenTour(kind: String): Boolean =
        prefs().getBoolean("tour_${kind}_seen", false)

    override fun markTourSeen(kind: String) {
        prefs().edit().putBoolean("tour_${kind}_seen", true).apply()
    }

    override fun resetTours() {
        prefs().edit()
            .remove("tour_library_seen")
            .remove("tour_record_seen")
            .remove("tour_editor_seen")
            .apply()
    }

    override fun hasSeenOnboarding(): Boolean =
        context.getSharedPreferences("dogsc_prefs", Context.MODE_PRIVATE).getBoolean("onboarding_seen", false)

    override fun markOnboardingSeen() {
        context.getSharedPreferences("dogsc_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_seen", true).apply()
    }

    override suspend fun loadPreferences(): AppPreferences {
        val prefs = context.getSharedPreferences("dogsc_prefs", Context.MODE_PRIVATE)
        return AppPreferences(
            recording = RecordingOptions(
                systemAudio = prefs.getBoolean("rec_system_audio", true),
                microphone = prefs.getBoolean("rec_microphone", false),
                camera = prefs.getBoolean("rec_camera", false),
                touchCapture = prefs.getBoolean("rec_touch", false),
                cameraLensFacing = prefs.getInt("rec_lens", 1),
                cameraQuality = prefs.getString("rec_camera_quality", "auto") ?: "auto",
                captureCodec = prefs.getString("rec_capture_codec", "h264") ?: "h264",
                captureResolutionLimit = prefs.getString("rec_capture_limit", "native") ?: "native",
                frameRate = prefs.getInt("rec_fps", 60),
            ),
            exportSoundEnabled = prefs.getBoolean("export_sound", true),
            memoText = prefs.getString("rec_memo", "") ?: "",
            appearance = runCatching {
                AppAppearance.valueOf(prefs.getString("appearance", "SYSTEM") ?: "SYSTEM")
            }.getOrDefault(AppAppearance.SYSTEM),
            english = prefs.getBoolean("english", false),
        )
    }

    override fun startPreviewAudio(project: RecorderProject, sourceDuration: Double, outputTime: Double) {
        previewAudio.start(project, sourceDuration, outputTime)
    }

    override fun seekPreviewAudio(project: RecorderProject, sourceDuration: Double, outputTime: Double) {
        previewAudio.seek(project, sourceDuration, outputTime)
    }

    override fun pausePreviewAudio() {
        previewAudio.pause()
    }

    override fun releasePreviewAudio() {
        previewAudio.release()
    }

    override fun releasePreviewFrames() {
        repository.releasePreviewFrames()
    }

    override fun clampExportDimensions(width: Int, height: Int): Pair<Int, Int> =
        dev.dogsc.app.android.export.EncoderCapabilities.clamp(width, height)

    override fun playExportSound() {
        runCatching {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 220)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { tone.release() } }, 400)
        }
    }

    override suspend fun savePreferences(preferences: AppPreferences) {
        context.getSharedPreferences("dogsc_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("rec_system_audio", preferences.recording.systemAudio)
            .putBoolean("rec_microphone", preferences.recording.microphone)
            .putBoolean("rec_camera", preferences.recording.camera)
            .putBoolean("rec_touch", preferences.recording.touchCapture)
            .putInt("rec_lens", preferences.recording.cameraLensFacing)
            .putString("rec_camera_quality", preferences.recording.cameraQuality)
            .putString("rec_capture_codec", preferences.recording.captureCodec)
            .putString("rec_capture_limit", preferences.recording.captureResolutionLimit)
            .putInt("rec_fps", preferences.recording.frameRate)
            .putBoolean("export_sound", preferences.exportSoundEnabled)
            .putString("rec_memo", preferences.memoText)
            .putString("appearance", preferences.appearance.name)
            .putBoolean("english", preferences.english)
            .apply()
    }
}
