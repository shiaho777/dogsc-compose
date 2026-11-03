@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.dogsc.app.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dogsc.app.platform.DogScHost
import dev.dogsc.app.ui.DogScAppState
import dev.dogsc.app.ui.EditorSelection
import dev.dogsc.app.ui.InspectorTab
import dev.dogsc.app.ui.components.PillButton
import dev.dogsc.app.ui.components.PillVariant
import dev.dogsc.app.ui.components.SoftIconButton
import dev.dogsc.app.ui.formatTimecode
import dev.dogsc.app.ui.theme.DogScColors
import kotlin.math.roundToInt

@Composable
fun EditorScreen(state: DogScAppState, host: DogScHost) {
    val project = state.project ?: return
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showInspectorSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(DogScColors.Background)) {
        val wide = maxWidth >= 880.dp
        Column(Modifier.fillMaxSize()) {
            EditorTopBar(
                state = state,
                host = host,
                onBack = { state.openLibrary() },
                onRename = { showRenameDialog = true },
                onExport = { state.showExportSheet = true },
                onMore = { showMoreMenu = true },
            )
            Row(Modifier.weight(1f)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    PreviewStage(
                        state = state,
                        modifier = Modifier.weight(1f),
                    )
                    TransportBar(
                        state = state,
                        onInspector = { showInspectorSheet = true },
                        wide = wide,
                    )
                    if (!wide) {
                        InspectorTabStrip(
                            selected = state.inspectorTab,
                            onSelect = {
                                state.inspectorTab = it
                                showInspectorSheet = true
                            },
                        )
                    }
                    AnimatedVisibility(visible = state.isTimelineExpanded) {
                        TimelinePanel(
                            state = state,
                            host = host,
                            modifier = Modifier.fillMaxWidth().height(if (wide) 250.dp else 206.dp),
                        )
                    }
                }
                if (wide) {
                    InspectorPanel(
                        state = state,
                        host = host,
                        modifier = Modifier
                            .width(336.dp)
                            .fillMaxHeight()
                            .padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 10.dp),
                        showTabStrip = true,
                    )
                }
            }
        }

        if (showInspectorSheet) {
            ModalBottomSheet(
                onDismissRequest = { showInspectorSheet = false },
                containerColor = DogScColors.Panel,
                dragHandle = {
                    Box(
                        Modifier
                            .padding(top = 10.dp, bottom = 4.dp)
                            .width(42.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(DogScColors.PrimaryText.copy(alpha = 0.16f)),
                    )
                },
            ) {
                InspectorPanel(
                    state = state,
                    host = host,
                    modifier = Modifier.fillMaxWidth().height(470.dp),
                    showTabStrip = true,
                )
            }
        }
    }

    if (showRenameDialog) {
        RenameProjectDialog(
            initial = project.title,
            onDismiss = { showRenameDialog = false },
            onConfirm = {
                state.renameProject(it)
                showRenameDialog = false
            },
        )
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除这个项目？") },
            text = { Text("项目和其中的录制素材会移到废纸篓，需要时可以恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    state.loadedProject?.summary?.let { state.deleteProject(it) }
                    state.openLibrary()
                }) { Text("删除", color = DogScColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } },
        )
    }
    Box {
        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
            DropdownMenuItem(
                text = { Text("场景预设") },
                leadingIcon = { Icon(Icons.Outlined.Layers, null) },
                onClick = { showMoreMenu = false; state.showScenePresetSheet = true },
            )
            DropdownMenuItem(
                text = { Text("录制标记") },
                leadingIcon = { Icon(Icons.Outlined.BookmarkBorder, null) },
                onClick = { showMoreMenu = false; state.showMarkersSheet = true },
            )
            DropdownMenuItem(
                text = { Text("保存项目") },
                onClick = { showMoreMenu = false; state.saveProject(showConfirmation = true) },
            )
            DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                onClick = { showMoreMenu = false; showRenameDialog = true },
            )
            DropdownMenuItem(
                text = { Text("创建副本") },
                onClick = { showMoreMenu = false; state.loadedProject?.summary?.let { state.duplicateProject(it) } },
            )
            DropdownMenuItem(
                text = { Text("快捷键与手势") },
                onClick = { showMoreMenu = false; state.showShortcutSheet = true },
            )
            DropdownMenuItem(
                text = { Text("删除项目") },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                onClick = { showMoreMenu = false; showDeleteDialog = true },
            )
        }
    }

    if (state.showExportSheet) {
        ExportSheet(state = state, host = host, onDismiss = { state.showExportSheet = false })
    }
    if (state.showShortcutSheet) {
        ShortcutSheet(onDismiss = { state.showShortcutSheet = false })
    }
    if (state.showMarkersSheet) {
        MarkersSheet(state = state, onDismiss = { state.showMarkersSheet = false })
    }
    if (state.showScenePresetSheet) {
        ScenePresetSheet(state = state, onDismiss = { state.showScenePresetSheet = false })
    }
}

@Composable
private fun ScenePresetSheet(state: DogScAppState, onDismiss: () -> Unit) {
    var showNameDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DogScColors.Panel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("场景预设", color = DogScColors.PrimaryText, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.weight(1f))
                PillButton("保存当前场景", onClick = { showNameDialog = true }, icon = Icons.Outlined.Add, compact = true)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "保存背景、布局、样机、光标、开场与运镜默认值；不会改变当前项目的素材和时间线。",
                color = DogScColors.TertiaryText,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            if (state.scenePresets.isEmpty()) {
                Text("还没有场景预设", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodyMedium)
            } else {
                state.scenePresets.forEach { preset ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(DogScColors.ControlWell),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.Layers, null, tint = DogScColors.SecondaryText, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(preset.name, color = DogScColors.PrimaryText, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (preset.id == state.activeScenePresetId) "当前场景" else "点击应用",
                                color = if (preset.id == state.activeScenePresetId) DogScColors.MintInk else DogScColors.TertiaryText,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        PillButton("应用", onClick = {
                            state.applyScenePreset(preset)
                            onDismiss()
                        }, variant = PillVariant.Ghost, compact = true)
                        SoftIconButton(Icons.Outlined.DeleteOutline, onClick = { state.deleteScenePreset(preset) }, size = 30.dp, iconSize = 15.dp)
                    }
                }
            }
        }
    }
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("保存场景预设") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    singleLine = true,
                    label = { Text("预设名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        state.saveScenePreset(name.trim())
                        showNameDialog = false
                        name = ""
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showNameDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun MarkersSheet(state: DogScAppState, onDismiss: () -> Unit) {
    val project = state.project ?: return
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DogScColors.Panel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("录制标记", color = DogScColors.PrimaryText, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.weight(1f))
                PillButton("在播放头添加", onClick = {
                    state.addMarkerAtPlayhead()
                }, icon = Icons.Outlined.Add, compact = true)
            }
            Spacer(Modifier.height(10.dp))
            Text("标记只保存在项目里，不会录进画面。", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            if (project.recordingMarkers.isEmpty()) {
                Text("还没有标记", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodyMedium)
            } else {
                project.recordingMarkers.sortedBy { it.sourceTime }.forEach { marker ->
                    val map = dev.dogsc.core.editor.TimelineEditor.timelineMap(project, state.sourceDuration)
                    val outputTime = map.outputTimeForSource(marker.sourceTime)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(DogScColors.Recording.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${marker.number}", color = DogScColors.Recording, style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("标记 ${marker.number}", color = DogScColors.PrimaryText, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (outputTime != null) formatTimecode(outputTime) else "素材已剪掉",
                                color = DogScColors.TertiaryText,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        PillButton("跳转", onClick = {
                            state.seekToMarker(marker.id)
                            onDismiss()
                        }, variant = PillVariant.Ghost, compact = true, enabled = outputTime != null)
                        SoftIconButton(Icons.Outlined.DeleteOutline, onClick = { state.deleteMarker(marker.id) }, size = 30.dp, iconSize = 15.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorTopBar(
    state: DogScAppState,
    host: DogScHost,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onMore: () -> Unit,
) {
    val project = state.project ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(DogScColors.Panel.copy(alpha = 0.92f))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SoftIconButton(Icons.Outlined.ArrowBack, onClick = onBack, contentDescription = "返回")
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f).clickable(onClick = onRename)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    project.title,
                    color = DogScColors.PrimaryText,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (state.isDirty) DogScColors.Amber else DogScColors.Success),
                )
            }
            Text(
                "点击重命名 · ${if (state.isDirty) "未保存" else "已保存"} · ${state.outputDuration.let(::formatTimecode)}",
                color = DogScColors.TertiaryText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SoftIconButton(
            Icons.Outlined.Undo,
            onClick = { state.undo() },
            contentDescription = "撤销",
            enabled = state.canUndo,
        )
        SoftIconButton(
            Icons.Outlined.Redo,
            onClick = { state.redo() },
            contentDescription = "重做",
            enabled = state.canRedo,
        )
        SoftIconButton(Icons.Outlined.MoreHoriz, onClick = onMore, contentDescription = "更多")
        Spacer(Modifier.width(4.dp))
        PillButton(
            text = "导出",
            onClick = onExport,
            icon = Icons.Outlined.Download,
            variant = PillVariant.Primary,
            compact = true,
        )
    }
}

@Composable
private fun PreviewStage(state: DogScAppState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFFF7F7F8), DogScColors.Background),
                    radius = 1400f,
                )
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val maxPreviewWidth = if (maxWidth < 600.dp) maxWidth else maxWidth * 0.92f
            val aspect = state.canvasAspectRatio.toFloat()
            val byWidth = maxPreviewWidth
            val byHeight = maxHeight * 0.96f
            val width = if (byWidth / aspect <= byHeight) byWidth else byHeight * aspect
            Box(
                modifier = Modifier
                    .width(width)
                    .shadow(
                        elevation = 22.dp,
                        shape = RoundedCornerShape(6.dp),
                        ambientColor = Color(0x2210141C),
                        spotColor = Color(0x2210141C),
                    )
                    .clip(RoundedCornerShape(6.dp)),
            ) {
                ScenePreview(state = state)
            }
            if (state.isPreviewLoading && state.previewFrame == null) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("正在准备首帧…", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.48f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.project?.canvas?.aspectRatio?.displayName ?: "适应",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    state.project?.let {
                        val d = it.canvas.pixelDimensions(
                            it.exportSettings.resolution,
                            state.sourceAspectRatio,
                            dev.dogsc.core.model.CanvasDimensions(
                                state.loadedProject?.summary?.pixelWidth ?: 1920,
                                state.loadedProject?.summary?.pixelHeight ?: 1080,
                            ),
                        )
                        "${d.width}×${d.height}"
                    } ?: "",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun TransportBar(
    state: DogScAppState,
    onInspector: () -> Unit,
    wide: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(DogScColors.Panel)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SoftIconButton(
            Icons.Outlined.SkipPrevious,
            onClick = { state.stepFrames(-5) },
            contentDescription = "后退 5 帧",
            size = 30.dp,
            iconSize = 16.dp,
        )
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(DogScColors.Accent)
                .clickable { state.togglePlayback() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = if (state.isPlaying) "暂停" else "播放",
                tint = DogScColors.OnAccent,
                modifier = Modifier.size(18.dp),
            )
        }
        SoftIconButton(
            Icons.Outlined.SkipNext,
            onClick = { state.stepFrames(5) },
            contentDescription = "前进 5 帧",
            size = 30.dp,
            iconSize = 16.dp,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "${formatTimecode(state.playhead)} / ${formatTimecode(state.outputDuration)}",
            color = DogScColors.PrimaryText,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.weight(1f))
        SoftIconButton(
            Icons.Outlined.ContentCut,
            onClick = { state.splitAtPlayhead() },
            contentDescription = "在播放头分割",
            size = 32.dp,
            iconSize = 16.dp,
        )
        SoftIconButton(
            if (state.isTimelineExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
            onClick = { state.isTimelineExpanded = !state.isTimelineExpanded },
            contentDescription = "展开或收起时间线",
            size = 32.dp,
            iconSize = 17.dp,
        )
        if (!wide) {
            SoftIconButton(
                Icons.Outlined.Fullscreen,
                onClick = onInspector,
                contentDescription = "打开参数面板",
                size = 32.dp,
                iconSize = 17.dp,
            )
        }
    }
}

@Composable
private fun InspectorTabStrip(selected: InspectorTab, onSelect: (InspectorTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(DogScColors.Panel)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InspectorTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) DogScColors.SelectionWash else Color.Transparent)
                    .clickable { onSelect(tab) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.title,
                    color = if (isSelected) DogScColors.PrimaryText else DogScColors.SecondaryText,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun RenameProjectDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("项目名称") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(200) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim().ifBlank { initial }) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ShortcutSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DogScColors.Panel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
            Text("快捷键与手势", color = DogScColors.PrimaryText, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            listOf(
                "空格" to "播放 / 暂停",
                "← / →" to "向前 / 向后移动 1 帧",
                "⇧ + ← / →" to "移动 5 帧",
                "S" to "在播放头分割",
                "Delete" to "删除选中项",
                "双指捏合时间线" to "缩放时间线",
                "拖动播放头" to "定位画面",
                "点击片段" to "选中并打开对应参数",
            ).forEach { (key, action) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                    Text(key, color = DogScColors.SecondaryText, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(150.dp))
                    Text(action, color = DogScColors.PrimaryText, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
