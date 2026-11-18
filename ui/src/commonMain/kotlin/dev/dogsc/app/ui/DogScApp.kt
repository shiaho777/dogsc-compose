package dev.dogsc.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import dev.dogsc.app.ui.theme.DogScMotion
import dev.dogsc.app.ui.theme.rememberReducedMotion
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
import dev.dogsc.app.ui.components.Text
import dev.dogsc.app.ui.tour.FirstUseTourOverlay
import dev.dogsc.app.ui.tour.LocalTourTargets
import dev.dogsc.app.ui.tour.TourTargets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.dogsc.app.platform.DogScHost
import dev.dogsc.app.ui.editor.EditorScreen
import dev.dogsc.app.ui.library.LibraryScreen
import dev.dogsc.app.ui.onboarding.OnboardingScreen
import dev.dogsc.app.ui.record.RecordSetupScreen
import dev.dogsc.app.ui.record.RecordingScreen
import dev.dogsc.app.ui.settings.SettingsScreen
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.dogsc.app.ui.theme.DogScColors
import dev.dogsc.app.ui.theme.DogScTheme

@Composable
fun DogScApp(host: DogScHost) {
    val scope = rememberCoroutineScope()
    val state = remember { DogScAppState(host.platform, scope) }
    // Collecting the full recorder state here recomposed the entire app tree on every
    // elapsed-second tick. The root only needs these three signals; RecordingScreen
    // collects the live state (including elapsedSeconds) itself.
    var recorderActive by remember { mutableStateOf(false) }
    var recorderError by remember { mutableStateOf<String?>(null) }
    var recorderCompletedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(host) {
        androidx.compose.runtime.snapshotFlow {
            val value = host.recorderState.value
            Triple(value.isActive, value.error, value.completedProjectId)
        }.collect { (active, error, completed) ->
            recorderActive = active
            recorderError = error
            recorderCompletedId = completed
        }
    }
    var handledRecordingResult by remember { mutableStateOf<String?>(null) }
    val tourTargets = remember { TourTargets() }
    var tourKind by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        state.refreshProjects()
        state.refreshScenePresets()
        state.refreshPreferences()
    }

    LaunchedEffect(recorderActive) {
        host.keepScreenOn(recorderActive)
        if (recorderActive) {
            state.enterRecording()
        } else {
            state.leaveRecording()
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { host.keepScreenOn(false) }
    }
    LaunchedEffect(state.screen, state.showOnboarding) {
        tourTargets.clear()
        if (state.showOnboarding || tourKind != null) return@LaunchedEffect
        val kind = when (val screen = state.screen) {
            AppScreen.Library -> "library"
            AppScreen.RecordSetup -> "record"
            is AppScreen.Editor -> "editor"
            else -> null
        }
        // Auto coach-marks run once per install (global gate); Settings can replay per surface.
        if (kind != null && !host.platform.hasSeenAnyTour() && !host.platform.hasSeenTour(kind)) tourKind = kind
    }

    LaunchedEffect(state.requestedTour) {
        val requested = state.requestedTour ?: return@LaunchedEffect
        tourKind = requested
        state.consumeTourRequest()
    }

    LaunchedEffect(recorderError) {
        recorderError?.let { state.showToast(it, isError = true) }
    }
    LaunchedEffect(recorderCompletedId) {
        val id = recorderCompletedId ?: return@LaunchedEffect
        if (handledRecordingResult == id) return@LaunchedEffect
        handledRecordingResult = id
        val loaded = runCatching { host.platform.loadProject(id) }.getOrNull()
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

    OnAppBackground {
        state.stopPlayback()
        state.flushPendingSave()
    }

    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val darkTheme = when (state.appearance) {
        dev.dogsc.app.platform.AppAppearance.LIGHT -> false
        dev.dogsc.app.platform.AppAppearance.DARK -> true
        dev.dogsc.app.platform.AppAppearance.SYSTEM -> systemDark
    }
    // Shared sliders route drags through the editor session's gesture channel when
    // this sink is present: continuous preview, one undo entry per drag.
    val sliderSink = remember(state) {
        object : dev.dogsc.app.ui.components.SliderGestureSink {
            override fun preview(apply: () -> Unit) = state.previewSliderSample("调整参数", apply)
            override fun end(label: String) = state.endGesture(label)
        }
    }
    DogScTheme(darkTheme = darkTheme) {
        Box(Modifier.fillMaxSize().background(DogScColors.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            CompositionLocalProvider(
                LocalTourTargets provides tourTargets,
                dev.dogsc.app.ui.components.LocalSliderGestureSink provides sliderSink,
            ) {
                if (state.showOnboarding) {
                    OnboardingScreen(state = state, host = host)
                } else {
                    val reduced = rememberReducedMotion()
                    AnimatedContent(
                        targetState = state.screen,
                        transitionSpec = {
                            if (reduced) {
                                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                            } else {
                                // Forward navigation slides in from the right; going back to the
                                // library slides in from the left, so direction reads naturally.
                                val forward = targetState != AppScreen.Library
                                val dir = if (forward) 1 else -1
                                (slideInHorizontally(tween(DogScMotion.FluidMs)) { it / 6 * dir } +
                                    fadeIn(tween(DogScMotion.FluidMs))) togetherWith
                                    (slideOutHorizontally(tween(DogScMotion.FluidMs)) { -it / 6 * dir } +
                                        fadeOut(tween(DogScMotion.FluidMs)))
                            }
                        },
                        label = "screen-transition",
                    ) { screen ->
                        when (screen) {
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
                    }
                }
            }
            tourKind?.let { kind ->
                FirstUseTourOverlay(
                    kind = kind,
                    targets = tourTargets,
                    onDone = {
                        host.platform.markTourSeen(kind)
                        host.platform.markAnyTourSeen()
                        tourKind = null
                    },
                )
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
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            // Errors (save/export failures) were silent for TalkBack users.
                            .semantics {
                                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Assertive
                                contentDescription = toast.text
                            },
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
                    Modifier
                        .fillMaxSize()
                        .background(DogScColors.Scrim)
                        // A visual scrim alone does not consume touches; without this the
                        // library/editor underneath stays clickable during load/import/delete.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
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
