package dev.dogsc.app.ui.settings

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import dev.dogsc.app.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dogsc.app.platform.DogScHost
import dev.dogsc.app.ui.DogScAppState
import dev.dogsc.app.ui.formatDate
import dev.dogsc.app.ui.formatDurationShort
import dev.dogsc.app.ui.components.HairlineDivider
import dev.dogsc.app.ui.components.PillButton
import dev.dogsc.app.ui.components.PillVariant
import dev.dogsc.app.ui.components.SoftCard
import dev.dogsc.app.ui.components.SegmentedControl
import dev.dogsc.app.ui.components.SoftIconButton
import dev.dogsc.app.ui.components.ToggleRow
import dev.dogsc.app.ui.theme.DogScColors
import dev.dogsc.ui.resources.Res
import dev.dogsc.ui.resources.dogsc_icon
import org.jetbrains.compose.resources.painterResource

@Composable
fun SettingsScreen(state: DogScAppState, host: DogScHost) {
    val platform = host.platform
    var accessibilityEnabled by remember { mutableStateOf(host.isAccessibilityServiceEnabled()) }
    var overlayGranted by remember { mutableStateOf(host.isOverlayPermissionGranted()) }
    var showClearTrashDialog by remember { mutableStateOf(false) }
    dev.dogsc.app.ui.OnAppForeground {
        accessibilityEnabled = host.isAccessibilityServiceEnabled()
        overlayGranted = host.isOverlayPermissionGranted()
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(DogScColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIconButton(Icons.Outlined.ArrowBack, onClick = { state.openLibrary() }, contentDescription = "返回")
            Spacer(Modifier.width(8.dp))
            Text("设置", color = DogScColors.PrimaryText, style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(18.dp))
        SoftCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painterResource(Res.drawable.dogsc_icon),
                    contentDescription = null,
                    modifier = Modifier.size(58.dp).clip(RoundedCornerShape(15.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("DogSC", color = DogScColors.PrimaryText, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "录得清楚，剪得轻松，运镜自然。",
                        color = DogScColors.SecondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Compose 全端版 · 0.1.0",
                        color = DogScColors.TertiaryText,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionTitle("语言")
        SoftCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                SegmentedControl(
                    options = listOf("中文", "English"),
                    selectedIndex = if (state.english) 1 else 0,
                    onSelect = { index -> state.updateEnglish(index == 1) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionTitle("外观")
        SoftCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                SegmentedControl(
                    options = listOf("浅色", "深色", "跟随系统"),
                    selectedIndex = when (state.appearance) {
                        dev.dogsc.app.platform.AppAppearance.LIGHT -> 0
                        dev.dogsc.app.platform.AppAppearance.DARK -> 1
                        dev.dogsc.app.platform.AppAppearance.SYSTEM -> 2
                    },
                    onSelect = { index ->
                        state.updateAppearance(
                            when (index) {
                                0 -> dev.dogsc.app.platform.AppAppearance.LIGHT
                                1 -> dev.dogsc.app.platform.AppAppearance.DARK
                                else -> dev.dogsc.app.platform.AppAppearance.SYSTEM
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionTitle("录制与权限")
        SoftCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(8.dp)) {
                PermissionRow(
                    icon = Icons.Outlined.ScreenShare,
                    title = "屏幕录制",
                    subtitle = "用于捕获屏幕画面；每次开始录制时由系统确认",
                    action = { host.openAppSettings() },
                )
                HairlineDivider(Modifier.padding(horizontal = 8.dp))
                PermissionRow(
                    icon = Icons.Outlined.TouchApp,
                    title = "触控记录",
                    subtitle = if (platform.supportsTouchCapture) {
                        if (accessibilityEnabled) "已启用，可生成光标轨迹与自动运镜"
                        else "需要无障碍服务；不启用仍可正常录屏"
                    } else "当前平台不支持",
                    action = { host.openAccessibilitySettings() },
                )
                HairlineDivider(Modifier.padding(horizontal = 8.dp))
                PermissionRow(
                    icon = Icons.Outlined.PictureInPictureAlt,
                    title = "悬浮控制条",
                    subtitle = if (overlayGranted) {
                        "已启用，录制时可暂停、标记和停止"
                    } else {
                        "需要悬浮窗权限；未授权时只能使用通知控制"
                    },
                    action = { host.openOverlaySettings() },
                )
                HairlineDivider(Modifier.padding(horizontal = 8.dp))
                PermissionRow(
                    icon = Icons.Outlined.Mic,
                    title = "麦克风",
                    subtitle = "用于人声解说；开启麦克风录制时由系统询问",
                    action = { host.openAppSettings() },
                )
                HairlineDivider(Modifier.padding(horizontal = 8.dp))
                PermissionRow(
                    icon = Icons.Outlined.CameraAlt,
                    title = "摄像头",
                    subtitle = "用于画中画人像；开启摄像头录制时由系统询问",
                    action = { host.openAppSettings() },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionTitle("录制默认值")
        SoftCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                ToggleRow(
                    "系统声音",
                    "录制应用播放的声音；部分应用会禁止采集",
                    state.recordingOptions.systemAudio,
                    { value -> state.updateRecordingDefaults { it.copy(systemAudio = value) } },
                    enabled = platform.supportsSystemAudio,
                )
                ToggleRow(
                    "麦克风",
                    "独立录制人声，编辑时可单独调节音量",
                    state.recordingOptions.microphone,
                    { value -> state.updateRecordingDefaults { it.copy(microphone = value) } },
                )
                ToggleRow(
                    "摄像头画中画",
                    "单独录制摄像头，剪辑时调整位置与形状",
                    state.recordingOptions.camera,
                    { value -> state.updateRecordingDefaults { it.copy(camera = value) } },
                    enabled = platform.supportsCamera,
                )
                ToggleRow(
                    "记录触控位置",
                    "用于光标轨迹与自动运镜，需要无障碍服务",
                    state.recordingOptions.touchCapture,
                    { value -> state.updateRecordingDefaults { it.copy(touchCapture = value) } },
                    enabled = platform.supportsTouchCapture,
                )
                Spacer(Modifier.height(6.dp))
                Text("帧率", color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(5.dp))
                SegmentedControl(
                    options = listOf("30 FPS", "60 FPS"),
                    selectedIndex = if (state.recordingOptions.frameRate == 30) 0 else 1,
                    onSelect = { index ->
                        state.updateRecordingDefaults { it.copy(frameRate = if (index == 0) 30 else 60) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionTitle("导出")
        SoftCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                ToggleRow(
                    "完成提示音",
                    "导出完成后播放一声提示音",
                    state.exportSoundEnabled,
                    { value -> state.updateExportSoundEnabled(value) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionTitle("平台能力")
        SoftCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CapabilityRow("屏幕录制", platform.supportsScreenRecording)
                CapabilityRow("系统声音", platform.supportsSystemAudio)
                CapabilityRow("麦克风", true)
                CapabilityRow("摄像头画中画", platform.supportsCamera)
                CapabilityRow("触控轨迹", platform.supportsTouchCapture)
                CapabilityRow("MP4 / M4A 导出", platform.supportsExport)
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionTitle("项目")
        SoftCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FolderOpen, null, tint = DogScColors.SecondaryText)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("项目保存在应用专属目录", color = DogScColors.PrimaryText, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "共 ${state.projects.size} 个项目；删除的项目可在回收站中恢复。",
                            color = DogScColors.TertiaryText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionTitle("回收站")
        SoftCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.trashedProjects.isEmpty()) {
                    Text(
                        "没有已删除的项目",
                        color = DogScColors.TertiaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    if (state.trashedProjects.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        PillButton(
                            "清空回收站",
                            onClick = { showClearTrashDialog = true },
                            variant = PillVariant.Ghost,
                            compact = true,
                        )
                    }
                    state.trashedProjects.forEach { summary ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Restore, null, tint = DogScColors.SecondaryText)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    summary.title,
                                    color = DogScColors.PrimaryText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                                Text(
                                    "${formatDate(summary.updatedAtEpochMs)} · ${formatDurationShort(summary.durationSeconds)}",
                                    color = DogScColors.TertiaryText,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            PillButton(
                                "恢复",
                                onClick = { state.restoreProject(summary) },
                                variant = PillVariant.Ghost,
                                compact = true,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionTitle("关于")
        SoftCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Code, null, tint = DogScColors.SecondaryText)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("基于 DogSC 的行为规范重制", color = DogScColors.PrimaryText, style = MaterialTheme.typography.bodyMedium)
                        Text("上游 MIT License · Copyright (c) 2026 神烦老狗", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodySmall)
                    }
                    PillButton("GitHub", onClick = { host.openUrl("https://github.com/laogou717/dogsc") }, icon = Icons.Outlined.OpenInNew, variant = PillVariant.Ghost, compact = true)
                }
                HairlineDivider()
                PillButton("重看首次引导", onClick = { state.reopenOnboarding() }, variant = PillVariant.Ghost, compact = true)
                Text(
                    "本应用是独立重制版本，保留上游版权、许可证与归属说明。字体为阿里巴巴普惠体 3.0，按其自身许可随附。",
                    color = DogScColors.TertiaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "DogSC Compose · 为 Android 与更多 Compose 平台构建",
            color = DogScColors.TertiaryText,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(30.dp))
    }
    if (showClearTrashDialog) {
        AlertDialog(
            onDismissRequest = { showClearTrashDialog = false },
            containerColor = DogScColors.Panel,
            title = { Text("清空回收站") },
            text = { Text("永久删除回收站中的所有项目？此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearTrashDialog = false
                        state.clearTrash()
                    },
                ) {
                    Text("清空", color = DogScColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearTrashDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = DogScColors.SecondaryText,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(bottom = 7.dp),
    )
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    action: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = DogScColors.SecondaryText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DogScColors.PrimaryText, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodySmall)
        }
        PillButton("管理", onClick = action, variant = PillVariant.Ghost, compact = true)
    }
}

@Composable
private fun CapabilityRow(title: String, supported: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = DogScColors.PrimaryText, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            if (supported) "可用" else "不可用",
            color = if (supported) DogScColors.MintInk else DogScColors.TertiaryText,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
