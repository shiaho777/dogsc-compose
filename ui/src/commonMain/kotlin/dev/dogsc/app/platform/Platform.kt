package dev.dogsc.app.platform

import androidx.compose.ui.graphics.ImageBitmap
import dev.dogsc.core.model.RecorderProject

data class ProjectSummary(
    val id: String,
    val title: String,
    val updatedAtEpochMs: Long,
    val createdAtEpochMs: Long,
    val durationSeconds: Double,
    val hasCamera: Boolean,
    val hasMicrophone: Boolean,
    val hasSystemAudio: Boolean,
    val pixelWidth: Int?,
    val pixelHeight: Int?,
    val thumbnailPath: String?,
)

data class LoadedProject(
    val project: RecorderProject,
    val sourceDuration: Double,
    val summary: ProjectSummary,
    val pointerEvents: List<dev.dogsc.core.model.PointerEventRecord> = emptyList(),
    /** Camera aspect from the actual file; older projects predate stored pixel dimensions. */
    val cameraAspectRatio: Double? = null,
)

data class ImportedMedia(
    val title: String,
    val durationSeconds: Double,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
)

data class ExportRequest(
    val project: RecorderProject,
    val sourceDuration: Double,
    val outputUri: String,
    val audioOnly: Boolean = false,
    val trimStart: Double = 0.0,
    val trimEnd: Double? = null,
)

data class ExportProgress(
    val fraction: Float,
    val message: String,
    val etaSeconds: Double? = null,
)

sealed class ExportResult {
    data class Success(
        val outputUri: String,
        val sizeBytes: Long,
        val width: Int = 0,
        val height: Int = 0,
    ) : ExportResult()
    data class Failure(val message: String) : ExportResult()
    data object Cancelled : ExportResult()
}

data class RecordingOptions(
    val systemAudio: Boolean = true,
    val microphone: Boolean = false,
    val camera: Boolean = false,
    val touchCapture: Boolean = false,
    val cameraLensFacing: Int = 1,
    val frameRate: Int = 60,
)

data class RecordingResult(
    val project: RecorderProject,
    val sourceDuration: Double,
)

enum class AppAppearance { LIGHT, DARK, SYSTEM }

/** App-wide preferences that outlive a project. */
data class AppPreferences(
    val recording: RecordingOptions = RecordingOptions(),
    val exportSoundEnabled: Boolean = true,
    val memoText: String = "",
    val appearance: AppAppearance = AppAppearance.SYSTEM,
    val english: Boolean = false,
)

/**
 * Everything the shared UI needs from a platform. Implementations own windows,
 * permissions, media codecs and files; product decisions stay in common code.
 */
interface DogScPlatform {
    val platformName: String
    val supportsSystemAudio: Boolean
    val supportsCamera: Boolean
    val supportsTouchCapture: Boolean
    val supportsExport: Boolean
    val supportsScreenRecording: Boolean

    suspend fun listProjects(): List<ProjectSummary>
    suspend fun loadProject(id: String): LoadedProject?
    suspend fun saveProject(project: RecorderProject): Boolean
    suspend fun deleteProject(id: String): Boolean
    suspend fun loadTrash(): List<ProjectSummary> = emptyList()
    suspend fun restoreProject(id: String): Boolean = false
    suspend fun clearTrash(): Boolean = false
    suspend fun duplicateProject(id: String): Boolean
    suspend fun renameProject(id: String, title: String): Boolean

    suspend fun importVideo(uri: String): LoadedProject?
    suspend fun importSticker(projectId: String, uri: String): String?
    suspend fun importBackground(projectId: String, uri: String, isVideo: Boolean): String?
    suspend fun loadThumbnail(project: RecorderProject, atTime: Double): ImageBitmap?
    suspend fun loadCameraThumbnail(project: RecorderProject, atTime: Double): ImageBitmap?
    suspend fun loadStickerBitmap(project: RecorderProject, relativePath: String): ImageBitmap?
    suspend fun loadBackgroundBitmap(project: RecorderProject): ImageBitmap?
    suspend fun loadWaveform(project: RecorderProject, sourceDuration: Double, buckets: Int = 600): FloatArray?
    suspend fun loadFilmstrip(project: RecorderProject, count: Int = 12): List<ImageBitmap>
    suspend fun loadScenePresets(): List<ScenePreset>
    suspend fun saveScenePresets(presets: List<ScenePreset>)
    suspend fun loadProjectThumbnail(summary: ProjectSummary): ImageBitmap?

    suspend fun probeMedia(project: RecorderProject): MediaProbe?
    suspend fun export(request: ExportRequest, onProgress: (ExportProgress) -> Unit): ExportResult
    fun cancelExport()

    fun openAppSettings()
    fun openAccessibilitySettings()
    fun openUrl(url: String)
    fun hasSeenOnboarding(): Boolean
    fun markOnboardingSeen()

    suspend fun loadPreferences(): AppPreferences
    suspend fun savePreferences(preferences: AppPreferences)
    fun playExportSound()

    /** Editor timeline audio. Platforms without audio preview keep these no-ops. */
    fun startPreviewAudio(project: RecorderProject, sourceDuration: Double, outputTime: Double) {}
    fun seekPreviewAudio(project: RecorderProject, sourceDuration: Double, outputTime: Double) {}
    fun pausePreviewAudio() {}
    fun releasePreviewAudio() {}
    fun releasePreviewFrames() {}

    /** Clamps an export size to what the platform encoder supports (identity by default). */
    fun clampExportDimensions(width: Int, height: Int): Pair<Int, Int> = width to height
}

data class MediaProbe(
    val screenDuration: Double,
    val screenWidth: Int,
    val screenHeight: Int,
    val cameraAspectRatio: Double? = null,
    val hasMicrophoneAudio: Boolean = false,
    val hasSystemAudio: Boolean = false,
    val pointerEventCount: Int = 0,
)
