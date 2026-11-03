package dev.dogsc.app.android

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import dev.dogsc.app.platform.DogScHost
import dev.dogsc.app.platform.DogScPlatform
import dev.dogsc.app.platform.RecorderUiState
import dev.dogsc.app.platform.RecordingOptions
import kotlinx.coroutines.flow.StateFlow

class AndroidDogScHost(
    private val activity: ComponentActivity,
    override val platform: AndroidDogScPlatform,
    private val recorder: AndroidRecorderManager,
) : DogScHost {
    var launchPickVideo: (() -> Unit)? = null
    var launchPickImage: (() -> Unit)? = null
    var launchCreateDocument: ((fileName: String, mimeType: String) -> Unit)? = null

    private var pendingPickVideo: ((String?) -> Unit)? = null
    private var pendingPickImage: ((String?) -> Unit)? = null
    private var pendingCreateDocument: ((String?) -> Unit)? = null

    override val recorderState: StateFlow<RecorderUiState> = recorder.state

    override fun pickVideo(onResult: (String?) -> Unit) {
        pendingPickVideo = onResult
        launchPickVideo?.invoke() ?: onResult(null)
    }

    override fun pickImage(onResult: (String?) -> Unit) {
        pendingPickImage = onResult
        launchPickImage?.invoke() ?: onResult(null)
    }

    override fun createExportFile(
        suggestedName: String,
        audioOnly: Boolean,
        onResult: (String?) -> Unit,
    ) {
        pendingCreateDocument = onResult
        launchCreateDocument?.invoke(
            suggestedName,
            if (audioOnly) "audio/mp4" else "video/mp4",
        ) ?: onResult(null)
    }

    fun handlePickedVideo(uri: String?) {
        val callback = pendingPickVideo
        pendingPickVideo = null
        callback?.invoke(uri)
    }

    fun handlePickedImage(uri: String?) {
        val callback = pendingPickImage
        pendingPickImage = null
        callback?.invoke(uri)
    }

    fun handleCreatedDocument(uri: String?) {
        val callback = pendingCreateDocument
        pendingCreateDocument = null
        callback?.invoke(uri)
    }

    override fun startRecording(options: RecordingOptions) {
        recorder.start(options)
    }

    override fun stopRecording() = recorder.stop()

    override fun pauseRecording() = recorder.pause()

    override fun resumeRecording() = recorder.resume()

    override fun addRecordingMarker() = recorder.addMarker()

    override fun setRecordingMicrophone(enabled: Boolean) = recorder.setMicrophoneEnabled(enabled)

    override fun setRecordingCamera(enabled: Boolean) = recorder.setCameraEnabled(enabled)

    override fun flipRecordingCamera() = recorder.flipCamera()

    override fun discardRecording() = recorder.discard()

    override fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(activity, DogScTouchAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            activity.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val accessibilityEnabled = Settings.Secure.getInt(
            activity.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        return accessibilityEnabled && enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    override fun openAppSettings() = platform.openAppSettings()

    override fun openAccessibilitySettings() = platform.openAccessibilitySettings()

    override fun openUrl(url: String) = platform.openUrl(url)

    override fun shareFile(uri: String) {
        val parsed = Uri.parse(uri)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = activity.contentResolver.getType(parsed) ?: "video/mp4"
            putExtra(Intent.EXTRA_STREAM, parsed)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, "分享成片"))
    }

    override fun keepScreenOn(enabled: Boolean) {
        if (enabled) {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
