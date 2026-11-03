package dev.dogsc.app.android

import android.Manifest
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.dogsc.app.ui.DogScApp

@Composable
fun DogScAndroidApp(activity: ComponentActivity) {
    val repository = remember { AndroidProjectRepository(activity) }
    val platform = remember { AndroidDogScPlatform(activity, repository) }
    val recorder = remember { AndroidRecorderManager(activity, repository) }
    val host = remember { AndroidDogScHost(activity, platform, recorder) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        host.handlePickedVideo(uri?.toString())
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        host.handlePickedImage(uri?.toString())
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        host.handleCreatedDocument(result.data?.data?.toString())
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        recorder.onPermissionsResult(result)
    }
    val projectionPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        recorder.onProjectionResult(result.resultCode, result.data)
    }

    LaunchedEffect(host) {
        host.launchPickVideo = { pickVideo.launch("video/*") }
        host.launchPickImage = { pickImage.launch("image/*") }
        host.launchCreateDocument = { fileName, mimeType ->
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            createDocument.launch(intent)
        }
        recorder.requestPermissions = { permissions.launch(it) }
        recorder.requestProjection = { projectionPermission.launch(it) }
    }

    DogScApp(host)
}
