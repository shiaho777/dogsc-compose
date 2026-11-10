package dev.dogsc.app.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import dev.dogsc.app.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dogsc.app.platform.DogScHost
import dev.dogsc.app.platform.ProjectSummary
import dev.dogsc.app.ui.DogScAppState
import dev.dogsc.app.ui.components.PillButton
import dev.dogsc.app.ui.components.PillVariant
import dev.dogsc.app.ui.components.SoftCard
import dev.dogsc.app.ui.components.SoftIconButton
import dev.dogsc.app.ui.formatDate
import dev.dogsc.app.ui.formatDurationShort
import dev.dogsc.app.ui.theme.DogScColors
import dev.dogsc.ui.resources.Res
import dev.dogsc.ui.resources.dogsc_icon
import org.jetbrains.compose.resources.painterResource

@Composable
fun LibraryScreen(
    state: DogScAppState,
    host: DogScHost,
    onStartRecording: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DogScColors.Background)
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(Res.drawable.dogsc_icon),
                contentDescription = "DogSC",
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "DogSC",
                    color = DogScColors.PrimaryText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "录得清楚，剪得轻松，运镜自然",
                    color = DogScColors.TertiaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.weight(1f))
            SoftIconButton(Icons.Outlined.Settings, onClick = { state.openSettings() }, contentDescription = "设置")
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "把屏幕，录成作品。",
            color = DogScColors.PrimaryText,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "录制屏幕、声音和摄像头，再用自动运镜、画面样机和开场动效，\n把一段普通录屏变成可以交付的短片。",
            color = DogScColors.SecondaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                text = "开始录制",
                onClick = onStartRecording,
                icon = Icons.Outlined.Videocam,
                variant = PillVariant.Primary,
            )
            PillButton(
                text = "导入视频",
                onClick = { host.pickVideo { uri -> if (uri != null) state.importVideo(uri) } },
                icon = Icons.Outlined.FileUpload,
            )
        }

        Spacer(Modifier.height(30.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "最近项目",
                color = DogScColors.PrimaryText,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${state.projects.size} 个项目",
                color = DogScColors.TertiaryText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))

        if (state.isLoadingProjects) {
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                Text("正在载入项目…", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodyMedium)
            }
        } else if (state.projects.isEmpty()) {
            EmptyLibrary(
                modifier = Modifier.fillMaxWidth(),
                onStartRecording = onStartRecording,
                onImport = { host.pickVideo { uri -> if (uri != null) state.importVideo(uri) } },
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 250.dp),
                contentPadding = PaddingValues(bottom = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        host = host,
                        onOpen = { state.openProject(project) },
                        onDelete = { state.deleteProject(project) },
                        onDuplicate = { state.duplicateProject(project) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: ProjectSummary,
    host: DogScHost,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val thumbnail by produceState<ImageBitmap?>(null, project.id, project.thumbnailPath) {
        value = host.platform.loadProjectThumbnail(project)
    }
    SoftCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFFE7E8EC), Color(0xFFD7D9DF)),
                        )
                    )
                    .clickable(onClick = onOpen),
            ) {
                val image = thumbnail
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().blur(28.dp),
                        contentScale = ContentScale.Crop,
                        alpha = 0.55f,
                    )
                    Image(
                        bitmap = image,
                        contentDescription = project.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Videocam,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatDurationShort(project.durationSeconds),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 13.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        project.title,
                        color = DogScColors.PrimaryText,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            append(formatDate(project.updatedAtEpochMs))
                            if (project.pixelWidth != null && project.pixelHeight != null) {
                                append(" · ${project.pixelWidth}×${project.pixelHeight}")
                            }
                        },
                        color = DogScColors.TertiaryText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
                Box {
                    SoftIconButton(Icons.Outlined.MoreHoriz, onClick = { menuOpen = true }, contentDescription = "项目操作")
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("打开") },
                            leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) },
                            onClick = { menuOpen = false; onOpen() },
                        )
                        DropdownMenuItem(
                            text = { Text("创建副本") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCut, null) },
                            onClick = { menuOpen = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(
    modifier: Modifier = Modifier,
    onStartRecording: () -> Unit,
    onImport: () -> Unit,
) {
    SoftCard(modifier = modifier, shape = RoundedCornerShape(22.dp)) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonitorIllustration(Modifier.size(132.dp, 108.dp))
            Spacer(Modifier.width(26.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "第一段作品，从这里开始",
                    color = DogScColors.PrimaryText,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "录制整块屏幕，按需打开系统声音、麦克风和摄像头。\n结束后会自动进入编辑器，随时可以继续修改。",
                    color = DogScColors.SecondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(14.dp))
                PillButton("开始录制", onStartRecording, icon = Icons.Outlined.Videocam, variant = PillVariant.Primary, compact = true)
            }
        }
    }
}

@Composable
private fun MonitorIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val body = RoundedCornerShape(16.dp)
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(w * 0.08f, h * 0.10f),
            size = Size(w * 0.84f, h * 0.64f),
            cornerRadius = CornerRadius(w * 0.055f),
        )
        drawRoundRect(
            color = Color(0xFFDFE2E8),
            topLeft = Offset(w * 0.08f, h * 0.10f),
            size = Size(w * 0.84f, h * 0.64f),
            cornerRadius = CornerRadius(w * 0.055f),
            style = Stroke(width = 1.2f),
        )
        // Screen content: a soft horizon and a tiny cursor.
        val screenLeft = w * 0.14f
        val screenTop = h * 0.17f
        val screenW = w * 0.72f
        val screenH = h * 0.50f
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFF9CB8D8), Color(0xFFE8C9A4), Color(0xFF7E9B75)),
                startY = screenTop,
                endY = screenTop + screenH,
            ),
            topLeft = Offset(screenLeft, screenTop),
            size = Size(screenW, screenH),
            cornerRadius = CornerRadius(w * 0.025f),
        )
        drawCircle(
            color = Color(0xFFF8E3B8),
            radius = w * 0.035f,
            center = Offset(screenLeft + screenW * 0.72f, screenTop + screenH * 0.28f),
        )
        val cursor = Path().apply {
            moveTo(screenLeft + screenW * 0.58f, screenTop + screenH * 0.56f)
            lineTo(screenLeft + screenW * 0.58f, screenTop + screenH * 0.82f)
            lineTo(screenLeft + screenW * 0.64f, screenTop + screenH * 0.75f)
            lineTo(screenLeft + screenW * 0.70f, screenTop + screenH * 0.88f)
            lineTo(screenLeft + screenW * 0.735f, screenTop + screenH * 0.855f)
            lineTo(screenLeft + screenW * 0.675f, screenTop + screenH * 0.72f)
            lineTo(screenLeft + screenW * 0.735f, screenTop + screenH * 0.70f)
            close()
        }
        drawPath(cursor, Color(0xFF202227))
        // Stand.
        drawRoundRect(
            color = Color(0xFFB8BCC5),
            topLeft = Offset(w * 0.44f, h * 0.74f),
            size = Size(w * 0.12f, h * 0.11f),
            cornerRadius = CornerRadius(3f),
        )
        drawRoundRect(
            color = Color(0xFFC9CCD3),
            topLeft = Offset(w * 0.30f, h * 0.84f),
            size = Size(w * 0.40f, h * 0.045f),
            cornerRadius = CornerRadius(w * 0.02f),
        )
        // A small mint recording badge.
        drawCircle(color = DogScColors.Mint, radius = w * 0.075f, center = Offset(w * 0.83f, h * 0.20f))
        drawCircle(color = DogScColors.MintInk, radius = w * 0.026f, center = Offset(w * 0.83f, h * 0.20f))
    }
}
