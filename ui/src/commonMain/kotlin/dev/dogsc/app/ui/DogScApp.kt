package dev.dogsc.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.dogsc.app.platform.DogScHost
import dev.dogsc.app.ui.editor.EditorScreen
import dev.dogsc.app.ui.library.LibraryScreen
import dev.dogsc.app.ui.onboarding.OnboardingScreen
import dev.dogsc.app.ui.record.RecordSetupScreen
import dev.dogsc.app.ui.record.RecordingScreen
import dev.dogsc.app.ui.settings.SettingsScreen
import dev.dogsc.app.ui.theme.DogScColors
import dev.dogsc.app.ui.theme.DogScTheme

@Composable
fun DogScApp(host: DogScHost) {
    val scope = rememberCoroutineScope()
    val state = remember { DogScAppState(host.platform, scope) }
    val recorder by host.recorderState.collectAsState()
    var handledRecordingResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        state.refreshProjects()
        state.refreshScenePresets()
        state.refreshPreferences()
    }

    LaunchedEffect(recorder.isActive) {
        if (recorder.isActive) {
            state.enterRecording()
        } else {
            state.leaveRecording()
        }
    }
    LaunchedEffect(recorder.error) {
        recorder.error?.let { state.showToast(it, isError = true) }
    }
    LaunchedEffect(recorder.completedProjectId) {
        val id = recorder.completedProjectId ?: return@LaunchedEffect
        if (handledRecordingResult == id) return@LaunchedEffect
        handledRecordingResult = id
        val loaded = host.platform.loadProject(id)
        if (loaded != null) {
            state.openRecordingResult(loaded)
        } else {
            state.showToast("录制已结束，但无法打开项目", isError = true)
            state.refreshProjects()
        }
    }

    PlatformBackHandler(enabled = state.screen !is AppScreen.Library) {
        state.openLibrary()
    }

    OnAppBackground { state.flushPendingSave() }

    DogScTheme {
        Box(Modifier.fillMaxSize().background(DogScColors.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (state.showOnboarding) {
                OnboardingScreen(state = state, host = host)
            } else when (val screen = state.screen) {
                AppScreen.Library -> LibraryScreen(
                    state = state,
                    host = host,
                    onStartRecording = { state.openRecordSetup() },
                )
                AppScreen.RecordSetup -> RecordSetupScreen(state, host)
                AppScreen.Recording -> RecordingScreen(state, host)
                AppScreen.Settings -> SettingsScreen(state, host)
                is AppScreen.Editor -> EditorScreen(state, host)
            }

            AnimatedVisibility(
                visible = state.toast != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp),
            ) {
                state.toast?.let { toast ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (toast.isError) DogScColors.Danger else Color(0xFF23252B))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (toast.isError) {
                            Icon(Icons.Outlined.ErrorOutline, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            toast.text,
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = state.isBusy,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    Modifier.fillMaxSize().background(DogScColors.Scrim),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(DogScColors.Panel)
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = DogScColors.PrimaryText,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(state.busyMessage, color = DogScColors.PrimaryText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
