package dev.dogsc.app.platform

import kotlinx.coroutines.flow.StateFlow

data class RecorderUiState(
    val isActive: Boolean = false,
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedSeconds: Double = 0.0,
    val markerCount: Int = 0,
    val microphoneEnabled: Boolean = false,
    val cameraEnabled: Boolean = false,
    val systemAudioEnabled: Boolean = true,
    val cameraLensFacing: Int = 1,
    val error: String? = null,
    val completedProjectId: String? = null,
    val isFinishing: Boolean = false,
)

/**
 * UI intents that require an Activity/Window. Kept separate from media data so
 * the shared product UI never reaches into platform APIs directly.
 */
interface DogScHost {
    val platform: DogScPlatform
    val recorderState: StateFlow<RecorderUiState>

    fun pickVideo(onResult: (String?) -> Unit)
    fun pickImage(onResult: (String?) -> Unit)
    fun createExportFile(suggestedName: String, audioOnly: Boolean, onResult: (String?) -> Unit)
    fun startRecording(options: RecordingOptions)
    fun stopRecording()
    fun pauseRecording()
    fun resumeRecording()
    fun addRecordingMarker()
    fun setRecordingMicrophone(enabled: Boolean)
    fun setRecordingCamera(enabled: Boolean)
    fun discardRecording()
    fun isAccessibilityServiceEnabled(): Boolean
    fun openAppSettings()
    fun openAccessibilitySettings()
    fun openUrl(url: String)
    fun shareFile(uri: String)
    fun keepScreenOn(enabled: Boolean)
}
