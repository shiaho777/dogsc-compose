package dev.dogsc.app.ui.onboarding

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import dev.dogsc.app.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.dogsc.app.platform.DogScHost
import dev.dogsc.app.ui.DogScAppState
import dev.dogsc.app.ui.components.PillButton
import dev.dogsc.app.ui.components.PillVariant
import dev.dogsc.app.ui.components.SoftCard
import dev.dogsc.app.ui.theme.DogScColors
import dev.dogsc.ui.resources.Res
import dev.dogsc.ui.resources.dogsc_icon
import org.jetbrains.compose.resources.painterResource

@Composable
fun OnboardingScreen(state: DogScAppState, host: DogScHost) {
    var page by remember { mutableIntStateOf(0) }
    val pages = 3
    Column(
        Modifier
            .fillMaxSize()
            .background(DogScColors.Background)
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(Res.drawable.dogsc_icon),
                contentDescription = "DogSC",
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(9.dp))
            Text("DogSC", color = DogScColors.PrimaryText, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            PillButton("跳过", onClick = { state.finishOnboarding() }, variant = PillVariant.Ghost, compact = true)
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                0 -> WelcomePage()
                1 -> PermissionPage(state, host)
                else -> WorkflowPage()
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(pages) { index ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (index == page) 18.dp else 6.dp, 6.dp)
                        .clip(CircleShape)
                        .background(if (index == page) DogScColors.PrimaryText else DogScColors.PrimaryText.copy(alpha = 0.16f)),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (page > 0) {
                PillButton("上一步", onClick = { page-- }, variant = PillVariant.Ghost, modifier = Modifier.weight(1f))
            }
            PillButton(
                if (page == pages - 1) "开始使用" else "下一步",
                onClick = {
                    if (page == pages - 1) state.finishOnboarding() else page++
                },
                variant = PillVariant.Primary,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WelcomePage() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(26.dp))
        Text(
            "把屏幕，录成作品。",
            color = DogScColors.PrimaryText,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "录得清楚，剪得轻松，运镜自然。\n这是 DogSC 的 Android 原生重制版。",
            color = DogScColors.SecondaryText,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(26.dp))
        FeatureRow(Icons.Outlined.ScreenShare, "录制", "屏幕、系统声音、麦克风和摄像头，分别保留为可编辑轨道。")
        FeatureRow(Icons.Outlined.ContentCut, "剪辑", "分割、裁切、变速、自动运镜和屏幕 3D，预览与导出共用一套场景。")
        FeatureRow(Icons.Outlined.Download, "导出", "MP4 或 M4A，1080p / 1440p / 4K，30 / 60 FPS。")
    }
}

@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    SoftCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(DogScColors.ControlWell),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = DogScColors.PrimaryText, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = DogScColors.PrimaryText, style = MaterialTheme.typography.titleSmall)
                Text(body, color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PermissionPage(state: DogScAppState, host: DogScHost) {
    // Returning from system settings must refresh the grant, or the card keeps saying
    // "前往授权" after the user already enabled the service.
    var accessibilityEnabled by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(host.isAccessibilityServiceEnabled())
    }
    dev.dogsc.app.ui.OnAppForeground {
        accessibilityEnabled = host.isAccessibilityServiceEnabled()
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "先完成两项授权",
            color = DogScColors.PrimaryText,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "屏幕录制用于捕获画面；触控记录用于生成光标轨迹和自动运镜。摄像头和麦克风会在启用时再询问。",
            color = DogScColors.SecondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(18.dp))
        PermissionCard(
            icon = Icons.Outlined.ScreenShare,
            title = "屏幕录制",
            body = "每次开始录制时由 Android 系统确认；不会后台静默采集。",
            action = "打开系统设置",
            onClick = { host.openAppSettings() },
        )
        Spacer(Modifier.height(10.dp))
        PermissionCard(
            icon = Icons.Outlined.TouchApp,
            title = "触控记录",
            body = if (accessibilityEnabled) {
                "已启用。只会记录点击与滚动位置，不读取屏幕文字。"
            } else {
                "可选。启用后，点击和滚动会变成可编辑的光标轨迹。"
            },
            action = if (accessibilityEnabled) "已启用" else "前往授权",
            onClick = { host.openAccessibilitySettings() },
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Mic, null, tint = DogScColors.TertiaryText, modifier = Modifier.size(15.dp))
            Text("麦克风", color = DogScColors.TertiaryText, style = MaterialTheme.typography.labelSmall)
            Icon(Icons.Outlined.CameraAlt, null, tint = DogScColors.TertiaryText, modifier = Modifier.size(15.dp))
            Text("摄像头", color = DogScColors.TertiaryText, style = MaterialTheme.typography.labelSmall)
            Text("按需授权", color = DogScColors.TertiaryText, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PermissionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    action: String,
    onClick: () -> Unit,
) {
    SoftCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(DogScColors.ControlWell),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = DogScColors.PrimaryText, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = DogScColors.PrimaryText, style = MaterialTheme.typography.titleSmall)
                Text(body, color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            PillButton(action, onClick = onClick, variant = PillVariant.Ghost, compact = true)
        }
    }
}

@Composable
private fun WorkflowPage() {
    Column(Modifier.fillMaxSize()) {
        Text("录制、剪辑、导出", color = DogScColors.PrimaryText, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("完整闭环，随时可以回到项目继续修改。", color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        StepRow("01", "录制", "选择声音与摄像头，开始后 DogSC 退到后台，悬浮条控制暂停、标记和停止。")
        StepRow("02", "剪辑", "在时间线上移动播放头，拖动片段边缘，添加缩放、屏幕 3D、打码和贴图。")
        StepRow("03", "导出", "选择区间、分辨率与帧率，导出 MP4；也可以只导出 M4A 音频。")
        Spacer(Modifier.height(12.dp))
        SoftCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = DogScColors.Mint.copy(alpha = 0.35f)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Videocam, null, tint = DogScColors.MintInk)
                Spacer(Modifier.width(10.dp))
                Text(
                    "第一次录制前，Android 会再次确认屏幕捕获范围。请选择“整个屏幕”。",
                    color = DogScColors.MintInk,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StepRow(number: String, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(number, color = DogScColors.TertiaryText, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DogScColors.PrimaryText, style = MaterialTheme.typography.titleSmall)
            Text(body, color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
        }
    }
}
