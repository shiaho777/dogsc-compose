package dev.dogsc.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import dev.dogsc.app.android.AndroidRecorderManager
import dev.dogsc.app.android.DogScAndroidApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            DogScAndroidApp(this)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == AndroidRecorderManager.REQUEST_PROJECTION) {
            AndroidRecorderManager.instance?.onProjectionResult(resultCode, data)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        if (requestCode == AndroidRecorderManager.REQUEST_PERMISSIONS) {
            val result = permissions.indices.associate { index ->
                permissions[index] to (grantResults.getOrNull(index) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            }
            AndroidRecorderManager.instance?.onPermissionsResult(result)
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
