package dev.dogsc.app.ui.record

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dogsc.app.platform.DogScHost
import dev.dogsc.app.platform.RecordingOptions
import dev.dogsc.app.ui.DogScAppState
import dev.dogsc.app.ui.components.DogScSwitch
import dev.dogsc.app.ui.components.PillButton
import dev.dogsc.app.ui.components.PillVariant
import dev.dogsc.app.ui.components.SegmentedControl
import dev.dogsc.app.ui.components.SoftCard
import dev.dogsc.app.ui.components.SoftIconButton
import dev.dogsc.app.ui.components.ToggleRow
import dev.dogsc.app.ui.theme.DogScColors

@Composable
fun RecordSetupScreen(state: DogScAppState, host: DogScHost) {
    val platform = host.platform
    var options by remember { mutableStateOf(state.recordingOptions) }
    var accessibilityEnabled by remember { mutableStateOf(host.isAccessibilityServiceEnabled()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DogScColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIconButton(Icons.Outlined.ArrowBack, onClick = { state.openLibrary() }, contentDescription = "返回")
            Spacer(Modifier.width(8.dp))
            Text("录制设置", color = DogScColors.PrimaryText, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            Text(
                platform.platformName,
                color = DogScColors.TertiaryText,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(18.dp))

        SoftCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(DogScColors.ControlWell),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.ScreenShare, null, tint = DogScColors.PrimaryText, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("整块屏幕", color = DogScColors.PrimaryText, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (platform.supportsScreenRecording) "录制当前显示器的全部内容"
                            else "此平台暂未提供屏幕捕获，可从项目库导入视频",
                            color = DogScColors.TertiaryText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("已选", color = DogScColors.MintInk, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("帧率", color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    SegmentedControl(
                        options = listOf("30 FPS", "60 FPS"),
                        selectedIndex = if (options.frameRate == 30) 0 else 1,
                        onSelect = { options = options.copy(frameRate = if (it == 0) 30 else 60) },
                        compact = true,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SoftCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("声音", color = DogScColors.SecondaryText, style = MaterialTheme.typography.labelMedium)
                ToggleRow(
                    title = "系统声音",
                    subtitle = if (platform.supportsSystemAudio) {
                        "录制应用播放的声音；部分应用会禁止被采集"
                    } else {
                        "当前平台不支持采集系统播放声音"
                    },
                    checked = options.systemAudio && platform.supportsSystemAudio,
                    enabled = platform.supportsSystemAudio,
                    onCheckedChange = { options = options.copy(systemAudio = it) },
                    leading = { Icon(Icons.Outlined.GraphicEq, null, tint = DogScColors.SecondaryText) },
                )
                ToggleRow(
                    title = "麦克风",
                    subtitle = "独立录制人声，编辑时可单独调节音量",
                    checked = options.microphone,
                    onCheckedChange = { options = options.copy(microphone = it) },
                    leading = { Icon(Icons.Outlined.Mic, null, tint = DogScColors.SecondaryText) },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        SoftCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("画面与交互", color = DogScColors.SecondaryText, style = MaterialTheme.typography.labelMedium)
                ToggleRow(
                    title = "摄像头画中画",
                    subtitle = "单独录制摄像头，剪辑时调整位置、形状与出场",
                    checked = options.camera,
                    enabled = platform.supportsCamera,
                    onCheckedChange = { options = options.copy(camera = it) },
                    leading = { Icon(Icons.Outlined.CameraAlt, null, tint = DogScColors.SecondaryText) },
                )
                if (options.camera) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 42.dp, top = 2.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("镜头", color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        SegmentedControl(
                            options = listOf("后置", "前置"),
                            selectedIndex = if (options.cameraLensFacing == 1) 1 else 0,
                            onSelect = { options = options.copy(cameraLensFacing = if (it == 0) 0 else 1) },
                            compact = true,
                        )
                    }
                }
                ToggleRow(
                    title = "记录触控位置",
                    subtitle = when {
                        !platform.supportsTouchCapture -> "当前平台没有可用的触控采集"
                        accessibilityEnabled -> "用于生成可编辑的光标轨迹与自动运镜"
                        else -> "需要在系统设置中启用 DogSC 无障碍服务"
                    },
                    checked = options.touchCapture && accessibilityEnabled,
                    enabled = platform.supportsTouchCapture,
                    onCheckedChange = { enabled ->
                        if (enabled && !accessibilityEnabled) {
                            host.openAccessibilitySettings()
                        } else {
                            options = options.copy(touchCapture = enabled)
                        }
                    },
                    leading = { Icon(Icons.Outlined.TouchApp, null, tint = DogScColors.SecondaryText) },
                )
                if (!accessibilityEnabled && platform.supportsTouchCapture) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 42.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "开启后返回，会自动重新检查",
                            color = DogScColors.TertiaryText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.weight(1f))
                        PillButton("前往授权", onClick = {
                            host.openAccessibilitySettings()
                        }, variant = PillVariant.Ghost, compact = true)
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "开始后 DogSC 会退到后台，悬浮控制条可随时暂停、标记或停止。录制内容不会包含控制条本身。",
            color = DogScColors.TertiaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(18.dp))
        PillButton(
            text = "开始录制",
            onClick = {
                accessibilityEnabled = host.isAccessibilityServiceEnabled()
                state.updateRecordingDefaults { options.copy(touchCapture = options.touchCapture && accessibilityEnabled) }
                host.startRecording(state.recordingOptions)
            },
            icon = Icons.Outlined.Videocam,
            variant = PillVariant.Primary,
            enabled = platform.supportsScreenRecording,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun RecordingScreen(state: DogScAppState, host: DogScHost) {
    val recorder by host.recorderState.collectAsState()
    val elapsed = recorder.elapsedSeconds
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DogScColors.Background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(DogScColors.Recording))
            Spacer(Modifier.width(8.dp))
            Text(
                if (recorder.isPaused) "已暂停" else "录制中",
                color = DogScColors.PrimaryText,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            dev.dogsc.app.ui.formatClock(elapsed),
            color = DogScColors.PrimaryText,
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "录制时请保持 DogSC 在后台；悬浮控制条不会出现在成片里。",
            color = DogScColors.TertiaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(28.dp))
        SoftCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp)) {
                ToggleRow(
                    title = "麦克风",
                    checked = recorder.microphoneEnabled,
                    onCheckedChange = { host.setRecordingMicrophone(it) },
                    leading = { Icon(Icons.Outlined.Mic, null, tint = DogScColors.SecondaryText) },
                )
                ToggleRow(
                    title = "摄像头",
                    checked = recorder.cameraEnabled,
                    onCheckedChange = { host.setRecordingCamera(it) },
                    leading = { Icon(Icons.Outlined.CameraAlt, null, tint = DogScColors.SecondaryText) },
                )
                if (recorder.cameraEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 38.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("切换镜头", color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        PillButton("翻转", onClick = { host.flipRecordingCamera() }, variant = PillVariant.Ghost, compact = true)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(
                if (recorder.isPaused) "继续" else "暂停",
                onClick = { if (recorder.isPaused) host.resumeRecording() else host.pauseRecording() },
                variant = PillVariant.Secondary,
                compact = true,
            )
            PillButton(
                "标记 ${recorder.markerCount}",
                onClick = { host.addRecordingMarker() },
                variant = PillVariant.Secondary,
                compact = true,
            )
        }
        Spacer(Modifier.height(20.dp))
        PillButton(
            "停止录制",
            onClick = { host.stopRecording() },
            variant = PillVariant.Danger,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        )
        if (recorder.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(recorder.error!!, color = DogScColors.Danger, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        PillButton("丢弃这次录制", onClick = { host.discardRecording() }, variant = PillVariant.Ghost)
    }
}
