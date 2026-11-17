package dev.dogsc.app.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import dev.dogsc.app.ui.theme.DogScMotion
import dev.dogsc.app.ui.theme.rememberReducedMotion
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import dev.dogsc.app.ui.components.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dogsc.app.platform.DogScHost
import dev.dogsc.app.ui.CanvasPane
import dev.dogsc.app.ui.DogScAppState
import dev.dogsc.app.ui.EditorSelection
import dev.dogsc.app.ui.InspectorTab
import dev.dogsc.app.ui.components.ChoiceTile
import dev.dogsc.app.ui.components.ColorSwatch
import dev.dogsc.app.ui.components.DogScSwitch
import dev.dogsc.app.ui.components.HairlineDivider
import dev.dogsc.app.ui.components.LabeledSlider
import dev.dogsc.app.ui.components.PillButton
import dev.dogsc.app.ui.components.PillVariant
import dev.dogsc.app.ui.components.Scrubber
import dev.dogsc.app.ui.components.SectionLabel
import dev.dogsc.app.ui.components.SegmentedControl
import dev.dogsc.app.ui.components.SoftCard
import dev.dogsc.app.ui.components.SoftIconButton
import dev.dogsc.app.ui.components.ToggleRow
import dev.dogsc.app.ui.formatScale
import dev.dogsc.app.ui.formatTimecode
import dev.dogsc.app.ui.theme.DogScColors
import dev.dogsc.core.editor.TimelineEditor
import dev.dogsc.core.model.BackgroundPatternPreset
import dev.dogsc.core.model.BackgroundSource
import dev.dogsc.core.model.CameraLayoutMode
import dev.dogsc.core.model.CameraMotionState
import dev.dogsc.core.model.CameraShape
import dev.dogsc.core.model.CanvasAspectRatio
import dev.dogsc.core.model.CanvasResolution
import dev.dogsc.core.model.CursorAssetID
import dev.dogsc.core.model.CursorClickEffectStyle
import dev.dogsc.core.model.CursorMotionStyle
import dev.dogsc.core.model.CursorStyle
import dev.dogsc.core.model.DynamicBackgroundPreset
import dev.dogsc.core.model.ElementMotionCurve
import dev.dogsc.core.model.FocusEffect
import dev.dogsc.core.model.FocusEffectShape
import dev.dogsc.core.model.FocusEffectTarget
import dev.dogsc.core.model.HexColor
import dev.dogsc.core.model.MosaicEffectStyle
import dev.dogsc.core.model.MosaicTransitionStyle
import dev.dogsc.core.model.NormalizedCrop
import dev.dogsc.core.model.NormalizedOverlayRect
import dev.dogsc.core.model.NormalizedPoint
import dev.dogsc.core.model.OverlayTiming
import dev.dogsc.core.model.RecorderProject
import dev.dogsc.core.model.ScreenFrameStyle
import dev.dogsc.core.model.ScreenMotionState
import dev.dogsc.core.model.TransitionTiming
import dev.dogsc.core.model.StickerAnimationPreset
import dev.dogsc.core.model.ZoomEasingPreset
import dev.dogsc.core.scene.CursorCatalog
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun InspectorPanel(
    state: DogScAppState,
    host: DogScHost,
    modifier: Modifier = Modifier,
    showTabStrip: Boolean = true,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(DogScColors.Panel)
            .border(1.dp, DogScColors.Hairline, RoundedCornerShape(18.dp)),
    ) {
        if (showTabStrip) {
            InspectorTabs(selected = state.inspectorTab, onSelect = { state.inspectorTab = it })
            HairlineDivider()
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            when (state.inspectorTab) {
                InspectorTab.CANVAS -> CanvasInspector(state, host)
                InspectorTab.MOTION -> MotionInspector(state)
                InspectorTab.CURSOR -> CursorInspector(state)
                InspectorTab.CAMERA -> CameraInspector(state)
                InspectorTab.AUDIO -> AudioInspector(state)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun InspectorTabs(selected: InspectorTab, onSelect: (InspectorTab) -> Unit) {
    val tabs = InspectorTab.entries
    val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)
    val reduced = rememberReducedMotion()
    // Sliding capsule (upstream EditorInspectorTabBar): the highlight glides between tabs.
    val capsuleIndex = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex) {
        if (reduced) capsuleIndex.snapTo(selectedIndex.toFloat())
        else capsuleIndex.animateTo(selectedIndex.toFloat(), tween(DogScMotion.SnappyMs))
    }
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        val density = LocalDensity.current
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val gapPx = with(density) { 3.dp.toPx() }
        val tabWidthPx = (totalWidthPx - gapPx * (tabs.size - 1)) / tabs.size
        val capsuleXPx = capsuleIndex.value * (tabWidthPx + gapPx)
        // Capsule layer.
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(capsuleXPx.toInt(), 0) }
                .width(with(density) { tabWidthPx.toDp() })
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DogScColors.SelectionWash),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            tabs.forEach { tab ->
                val isSelected = tab == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
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
}

@Composable
private fun InspectorSection(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = DogScColors.PrimaryText, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun SubLabel(text: String) {
    Text(
        text,
        color = DogScColors.TertiaryText,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
    )
}

@Composable
private fun CanvasInspector(state: DogScAppState, host: DogScHost) {
    val project = state.project ?: return
    val canvas = project.canvas
    SegmentedControl(
        options = CanvasPane.entries.map { it.title },
        selectedIndex = CanvasPane.entries.indexOf(state.canvasPane),
        onSelect = { state.canvasPane = CanvasPane.entries[it] },
        modifier = Modifier.fillMaxWidth(),
        compact = true,
    )
    Spacer(Modifier.height(14.dp))
    when (state.canvasPane) {
        CanvasPane.BACKGROUND -> {
            InspectorSection("背景素材") {
                BackgroundGrid(
                    selected = canvas.backgroundSource,
                    onSelect = { state.updateCanvas("更换背景图案") { c -> c.copy(backgroundSource = it) } },
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        "选择图片",
                        onClick = { host.pickImage { uri -> if (uri != null) state.setBackground(uri, false) } },
                        icon = Icons.Outlined.Image,
                        compact = true,
                    )
                    PillButton(
                        "选择视频",
                        onClick = { host.pickVideo { uri -> if (uri != null) state.setBackground(uri, true) } },
                        icon = Icons.Outlined.Movie,
                        compact = true,
                    )
                }
                if (canvas.backgroundSource.usesWallpaperMedia) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (canvas.backgroundSource) {
                                is BackgroundSource.ProjectImage -> "项目图片背景"
                                is BackgroundSource.ProjectVideo -> "项目视频背景"
                                is BackgroundSource.SystemImage, is BackgroundSource.SystemVideo -> "系统素材背景"
                                else -> ""
                            },
                            color = DogScColors.SecondaryText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.weight(1f))
                        PillButton("恢复图案", onClick = {
                            state.updateCanvas("恢复图案背景") {
                                it.copy(backgroundSource = BackgroundSource.SafeFallback)
                            }
                        }, variant = PillVariant.Ghost, compact = true)
                    }
                }
                Spacer(Modifier.height(8.dp))
                LabeledSlider(
                    "图案大小",
                    canvas.patternScale.toFloat(),
                    0.4f..6f,
                    { value -> state.updateCanvas("调整图案大小") { it.copy(patternScale = value.toDouble()) } },
                    valueLabel = { "${((it * 100).roundToInt() / 100.0)}×" },
                )
                LabeledSlider(
                    "不透明度",
                    canvas.patternOpacity.toFloat(),
                    0f..1f,
                    { value -> state.updateCanvas("调整图案不透明度") { it.copy(patternOpacity = value.toDouble()) } },
                )
                LabeledSlider(
                    "背景模糊",
                    canvas.backgroundBlur.toFloat(),
                    0f..96f,
                    { value -> state.updateCanvas("调整背景模糊") { it.copy(backgroundBlur = value.toDouble()) } },
                    valueLabel = { "${it.roundToInt()}" },
                )
            }
            InspectorSection("画布") {
                AspectRatioChips(state)
                Spacer(Modifier.height(8.dp))
                SectionLabel("输出分辨率")
                Spacer(Modifier.height(5.dp))
                SegmentedControl(
                    options = CanvasResolution.entries.map { it.displayName },
                    selectedIndex = CanvasResolution.entries.indexOf(project.exportSettings.resolution),
                    onSelect = { index ->
                        state.updateExport("调整输出分辨率") {
                            it.copy(resolution = CanvasResolution.entries[index])
                        }
                    },
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                LabeledSlider(
                    "画面留白",
                    canvas.padding.toFloat(),
                    0f..240f,
                    { value -> state.updateCanvas("调整画面留白") { it.copy(padding = value.toDouble()) } },
                    valueLabel = { "${it.roundToInt()}" },
                )
                LabeledSlider(
                    "画面圆角",
                    canvas.cornerRadius.toFloat(),
                    0f..120f,
                    { value -> state.updateCanvas("调整画面圆角") { it.copy(cornerRadius = value.toDouble()) } },
                    valueLabel = { "${it.roundToInt()}" },
                )
                LabeledSlider(
                    "阴影强度",
                    canvas.shadowStrength.toFloat(),
                    0f..1f,
                    { value -> state.updateCanvas("调整阴影强度") { it.copy(shadowStrength = value.toDouble()) } },
                )
            }
            InspectorSection("描边") {
                LabeledSlider(
                    "外描边",
                    canvas.borderWidth.toFloat(),
                    0f..24f,
                    { value -> state.updateCanvas("调整描边宽度") { it.copy(borderWidth = value.toDouble()) } },
                    valueLabel = { "${it.roundToInt()}" },
                )
                ColorRow(
                    label = "描边颜色",
                    selected = canvas.borderColor,
                    onSelect = { color -> state.updateCanvas("调整描边颜色") { it.copy(borderColor = color) } },
                )
                LabeledSlider(
                    "描边不透明度",
                    canvas.insetOpacity.toFloat(),
                    0f..1f,
                    { value -> state.updateCanvas("调整描边不透明度") { it.copy(insetOpacity = value.toDouble()) } },
                )
            }
        }
        CanvasPane.LAYOUT -> {
            InspectorSection("位置与大小", trailing = {
                Text("可直接在画面中拖动", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodySmall)
            }) {
                GesturePositionPad(
                    state = state,
                    point = canvas.contentPosition,
                    label = "调整画面位置",
                    commit = { point -> state.updateCanvas("调整画面位置") { it.copy(contentPosition = point) } },
                    preview = { point -> state.previewGesture { it.copy(canvas = it.canvas.copy(contentPosition = point)) } },
                )
                Spacer(Modifier.height(8.dp))
                LabeledSlider(
                    "素材缩放",
                    canvas.contentScale.toFloat(),
                    0.2f..3f,
                    { value -> state.updateCanvas("调整素材缩放") { it.copy(contentScale = value.toDouble()) } },
                    valueLabel = { formatScale(it.toDouble()) },
                )
            }
            InspectorSection("裁切") {
                CropEditor(state)
            }
            InspectorSection("基础外观") {
                LabeledSlider(
                    "画面留白",
                    canvas.padding.toFloat(),
                    0f..240f,
                    { value -> state.updateCanvas("调整画面留白") { it.copy(padding = value.toDouble()) } },
                    valueLabel = { "${it.roundToInt()}" },
                )
                LabeledSlider(
                    "画面圆角",
                    canvas.cornerRadius.toFloat(),
                    0f..120f,
                    { value -> state.updateCanvas("调整画面圆角") { it.copy(cornerRadius = value.toDouble()) } },
                    valueLabel = { "${it.roundToInt()}" },
                )
                LabeledSlider(
                    "阴影强度",
                    canvas.shadowStrength.toFloat(),
                    0f..1f,
                    { value -> state.updateCanvas("调整阴影强度") { it.copy(shadowStrength = value.toDouble()) } },
                )
            }
        }
        CanvasPane.FRAME -> {
            InspectorSection("屏幕样机") {
                FramePicker(
                    selected = canvas.screenFrame,
                    onSelect = { style -> state.updateCanvas("更换样机") { it.copy(screenFrame = style) } },
                )
                if (canvas.screenFrame != ScreenFrameStyle.NONE) {
                    Spacer(Modifier.height(10.dp))
                    LabeledSlider(
                        "框体比例",
                        canvas.screenFrameScale.toFloat(),
                        0.6f..1.6f,
                        { value -> state.updateCanvas("调整框体比例") { it.copy(screenFrameScale = value.toDouble()) } },
                        valueLabel = { formatScale(it.toDouble()) },
                    )
                    if (canvas.screenFrame.isWindow || canvas.screenFrame.isBrowser) {
                        LabeledSlider(
                            "顶栏高度",
                            canvas.screenFrameToolbarScale.toFloat(),
                            0.65f..1.6f,
                            { value -> state.updateCanvas("调整顶栏高度") { it.copy(screenFrameToolbarScale = value.toDouble()) } },
                            valueLabel = { formatScale(it.toDouble()) },
                        )
                    }
                    if (canvas.screenFrame.isWindow) {
                        TextFieldRow(
                            label = "窗口标题",
                            value = canvas.screenFrameTitle,
                            placeholder = "可留空",
                            onValueChange = { value -> state.updateCanvas("修改窗口标题") { it.copy(screenFrameTitle = value) } },
                        )
                    }
                    if (canvas.screenFrame.isBrowser) {
                        TextFieldRow(
                            label = "网址或文字",
                            value = canvas.screenFrameBrowserAddress,
                            placeholder = "可留空",
                            onValueChange = { value -> state.updateCanvas("修改浏览器地址") { it.copy(screenFrameBrowserAddress = value) } },
                        )
                    }
                    LabeledSlider(
                        "框体外圆角",
                        (canvas.screenFrameOuterCornerRadius ?: 12.0).toFloat(),
                        0f..80f,
                        { value -> state.updateCanvas("调整框体外圆角") { it.copy(screenFrameOuterCornerRadius = value.toDouble()) } },
                        valueLabel = { "${it.roundToInt()}" },
                    )
                    LabeledSlider(
                        "内容圆角",
                        (canvas.screenFrameContentCornerRadius ?: 8.0).toFloat(),
                        0f..80f,
                        { value -> state.updateCanvas("调整内容圆角") { it.copy(screenFrameContentCornerRadius = value.toDouble()) } },
                        valueLabel = { "${it.roundToInt()}" },
                    )
                    PillButton("恢复当前样机默认外观", onClick = {
                        state.updateCanvas("恢复样机默认外观") {
                            it.copy(screenFrameOuterCornerRadius = null, screenFrameContentCornerRadius = null)
                        }
                    }, variant = PillVariant.Ghost, compact = true)
                }
            }
        }
        CanvasPane.EFFECTS -> {
            EffectsInspector(state)
        }
        CanvasPane.STICKERS -> {
            StickerInspector(state, host)
        }
    }
}

@Composable
private fun AspectRatioChips(state: DogScAppState) {
    val project = state.project ?: return
    val current = project.canvas.aspectRatio
    SectionLabel("比例")
    Spacer(Modifier.height(5.dp))
    val choices = CanvasAspectRatio.entries
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        choices.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { ratio ->
                    ChoiceTile(
                        title = ratio.displayName,
                        selected = ratio == current,
                        onClick = { state.updateCanvas("调整画布比例") { it.copy(aspectRatio = ratio) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    if (current == CanvasAspectRatio.CUSTOM) {
        var width by remember(current) { mutableStateOf(project.canvas.customAspectRatio.width.roundToInt().toString()) }
        var height by remember(current) { mutableStateOf(project.canvas.customAspectRatio.height.roundToInt().toString()) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(width, { width = it }, label = { Text("宽") }, singleLine = true, modifier = Modifier.weight(1f))
            Text(":", color = DogScColors.SecondaryText)
            OutlinedTextField(height, { height = it }, label = { Text("高") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val w = width.toDoubleOrNull()
                val h = height.toDoubleOrNull()
                if (w != null && h != null && w > 0 && h > 0) {
                    state.updateCanvas("调整自定义比例") {
                        it.copy(customAspectRatio = dev.dogsc.core.model.CanvasCustomAspectRatio(w, h))
                    }
                }
            }) { Text("应用") }
        }
    }
}

@Composable
private fun BackgroundGrid(selected: BackgroundSource, onSelect: (BackgroundSource) -> Unit) {
    val patterns = BackgroundPatternPreset.entries
    val dynamics = DynamicBackgroundPreset.entries
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        patterns.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { preset ->
                    BackgroundTile(
                        title = preset.displayName,
                        selected = selected is BackgroundSource.Pattern && selected.preset == preset,
                        onClick = { onSelect(BackgroundSource.Pattern(preset)) },
                        colors = when (preset) {
                            BackgroundPatternPreset.OBSIDIAN_GRID -> listOf(Color(0xFF0D1017), Color(0xFF1A2030))
                            BackgroundPatternPreset.ENGINEERING_WHITE_GRID -> listOf(Color(0xFFF5F4F1), Color(0xFFE2E1DC))
                            BackgroundPatternPreset.MIDNIGHT_DOTS -> listOf(Color(0xFF0A0C12), Color(0xFF161B28))
                            BackgroundPatternPreset.ARCHITECTURAL_DOTS -> listOf(Color(0xFFEFEDE8), Color(0xFFDCD9D2))
                            BackgroundPatternPreset.ISOMETRIC_MESH -> listOf(Color(0xFF10141C), Color(0xFF1D2634))
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        SubLabel("动态背景")
        dynamics.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { preset ->
                    BackgroundTile(
                        title = preset.displayName,
                        selected = selected is BackgroundSource.DynamicFlow && selected.preset == preset,
                        onClick = { onSelect(BackgroundSource.DynamicFlow(preset)) },
                        colors = when (preset) {
                            DynamicBackgroundPreset.CYBER_DRIFT_GRID -> listOf(Color(0xFF1A1206), Color(0xFF3D2A0C))
                            DynamicBackgroundPreset.STARFIELD_DOTS -> listOf(Color(0xFF0A0C14), Color(0xFF1A1E2C))
                            DynamicBackgroundPreset.AURORA_FLUID -> listOf(Color(0xFF0A1B33), Color(0xFF6A9DC4))
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun BackgroundTile(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(colors))
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) DogScColors.PrimaryText else DogScColors.Hairline,
                    RoundedCornerShape(8.dp),
                )
                .clickable(onClick = onClick),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val ink = Color.White.copy(alpha = 0.10f)
                var x = 0f
                while (x < size.width) {
                    drawLine(ink, Offset(x, 0f), Offset(x, size.height), 1f)
                    x += 12f
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(ink, Offset(0f, y), Offset(size.width, y), 1f)
                    y += 12f
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            title,
            color = if (selected) DogScColors.PrimaryText else DogScColors.SecondaryText,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FramePicker(selected: ScreenFrameStyle, onSelect: (ScreenFrameStyle) -> Unit) {
    val styles = ScreenFrameStyle.pickerChoices
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        styles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { style ->
                    val isSelected = style == selected
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (isSelected) DogScColors.SelectionWash else Color.Transparent)
                            .border(
                                if (isSelected) 1.5.dp else 1.dp,
                                if (isSelected) DogScColors.PrimaryText else DogScColors.Hairline,
                                RoundedCornerShape(9.dp),
                            )
                            .clickable { onSelect(style) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FrameThumbnail(style)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            style.displayName,
                            color = DogScColors.PrimaryText,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                        )
                    }
                }
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun FrameThumbnail(style: ScreenFrameStyle) {
    Canvas(Modifier.size(38.dp, 28.dp)) {
        val w = size.width
        val h = size.height
        when (style) {
            ScreenFrameStyle.NONE -> {
                drawRoundRect(Color(0xFFB8BCC5), Offset(w * 0.08f, h * 0.12f), Size(w * 0.84f, h * 0.76f), CornerRadius(3f))
            }
            ScreenFrameStyle.WINDOW_LIGHT, ScreenFrameStyle.WINDOW_DARK -> {
                val dark = style == ScreenFrameStyle.WINDOW_DARK
                drawRoundRect(if (dark) Color(0xFF2B2C31) else Color(0xFFF1F1F3), Offset(w * 0.04f, h * 0.08f), Size(w * 0.92f, h * 0.84f), CornerRadius(4f))
                drawRect(if (dark) Color(0xFF3B3C42) else Color(0xFFE3E3E6), Offset(w * 0.04f, h * 0.08f), Size(w * 0.92f, h * 0.22f))
                drawCircle(Color(0xFFFF5F57), w * 0.035f, Offset(w * 0.13f, h * 0.19f))
                drawCircle(Color(0xFFFEBC2E), w * 0.035f, Offset(w * 0.23f, h * 0.19f))
                drawCircle(Color(0xFF28C840), w * 0.035f, Offset(w * 0.33f, h * 0.19f))
            }
            ScreenFrameStyle.BROWSER_LIGHT, ScreenFrameStyle.BROWSER_DARK -> {
                val dark = style == ScreenFrameStyle.BROWSER_DARK
                drawRoundRect(if (dark) Color(0xFF2B2C31) else Color(0xFFF1F1F3), Offset(w * 0.03f, h * 0.08f), Size(w * 0.94f, h * 0.84f), CornerRadius(4f))
                drawRoundRect(if (dark) Color(0xFF1B1C20) else Color.White, Offset(w * 0.20f, h * 0.14f), Size(w * 0.70f, h * 0.16f), CornerRadius(3f))
            }
            else -> {
                drawRoundRect(Color(0xFF222327), Offset(w * 0.22f, h * 0.02f), Size(w * 0.56f, h * 0.96f), CornerRadius(w * 0.10f))
                drawRoundRect(Color(0xFF5A5E68), Offset(w * 0.26f, h * 0.08f), Size(w * 0.48f, h * 0.82f), CornerRadius(w * 0.06f))
            }
        }
    }
}

@Composable
private fun CropEditor(state: DogScAppState) {
    val crop = state.project?.canvas?.crop ?: return
    Column {
        LabeledSlider(
            "左侧",
            crop.left.toFloat(),
            0f..0.45f,
            { value -> state.updateCanvas("调整裁切") { c ->
                c.copy(crop = NormalizedCrop.fromEdges(value.toDouble(), c.crop.right, c.crop.top, c.crop.bottom))
            } },
            valueLabel = { "${(it * 100).roundToInt()}%" },
        )
        LabeledSlider(
            "右侧",
            crop.right.toFloat(),
            0f..0.45f,
            { value -> state.updateCanvas("调整裁切") { c ->
                c.copy(crop = NormalizedCrop.fromEdges(c.crop.left, value.toDouble(), c.crop.top, c.crop.bottom))
            } },
            valueLabel = { "${(it * 100).roundToInt()}%" },
        )
        LabeledSlider(
            "顶部",
            crop.top.toFloat(),
            0f..0.45f,
            { value -> state.updateCanvas("调整裁切") { c ->
                c.copy(crop = NormalizedCrop.fromEdges(c.crop.left, c.crop.right, value.toDouble(), c.crop.bottom))
            } },
            valueLabel = { "${(it * 100).roundToInt()}%" },
        )
        LabeledSlider(
            "底部",
            crop.bottom.toFloat(),
            0f..0.45f,
            { value -> state.updateCanvas("调整裁切") { c ->
                c.copy(crop = NormalizedCrop.fromEdges(c.crop.left, c.crop.right, c.crop.top, value.toDouble()))
            } },
            valueLabel = { "${(it * 100).roundToInt()}%" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("清除裁切", onClick = {
                state.updateCanvas("清除裁切") { it.copy(crop = NormalizedCrop.Full) }
            }, variant = PillVariant.Ghost, compact = true)
            PillButton("保留完整画面", onClick = {
                state.updateCanvas("恢复完整画面") { it.copy(crop = NormalizedCrop.Full, contentScale = 1.0) }
            }, variant = PillVariant.Ghost, compact = true)
        }
    }
}

@Composable
private fun TextFieldRow(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(74.dp))
        OutlinedTextField(
            value = local,
            onValueChange = { local = it; onValueChange(it) },
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EffectsInspector(state: DogScAppState) {
    val project = state.project ?: return
    InspectorSection("添加效果") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("柔化", onClick = {
                state.addMosaicAt(state.playhead, MosaicEffectStyle.BLUR)
                state.canvasPane = CanvasPane.EFFECTS
            }, icon = Icons.Outlined.CenterFocusStrong, compact = true)
            PillButton("突出", onClick = {
                state.addMosaicAt(state.playhead, MosaicEffectStyle.SPOTLIGHT)
                state.canvasPane = CanvasPane.EFFECTS
            }, icon = Icons.Outlined.CenterFocusStrong, compact = true)
        }
        Spacer(Modifier.height(8.dp))
        SubLabel("效果会从播放头开始，默认持续 3 秒；可在时间线上拖动两端。")
    }
    val clips = project.timeline.mosaicClips.sortedBy { it.timing.startTime }
    if (clips.isEmpty()) {
        Text("当前没有柔化或突出片段", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodySmall)
    } else {
        InspectorSection("片段") {
            clips.forEach { clip ->
                val selected = (state.selection as? EditorSelection.Mosaic)?.clipId == clip.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) DogScColors.SelectionWash else Color.Transparent)
                        .clickable { state.select(EditorSelection.Mosaic(clip.id)) }
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.CenterFocusStrong,
                        null,
                        tint = DogScColors.MosaicInk,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (clip.style == MosaicEffectStyle.SPOTLIGHT) "突出" else "柔化",
                            color = DogScColors.PrimaryText,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            "${formatTimecode(clip.timing.startTime)} · ${formatTimecode(clip.timing.duration)}",
                            color = DogScColors.TertiaryText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    SoftIconButton(Icons.Outlined.DeleteOutline, onClick = {
                        state.updateProject("删除效果") { TimelineEditor.deleteMosaic(it, clip.id) }
                        state.select(EditorSelection.None)
                    }, size = 28.dp, iconSize = 15.dp)
                }
            }
        }
    }
    state.selectedMosaic()?.let { clip ->
        InspectorSection("区域效果") {
            SegmentedControl(
                options = listOf("柔化", "突出"),
                selectedIndex = if (clip.style == MosaicEffectStyle.BLUR) 0 else 1,
                onSelect = { index ->
                    val style = if (index == 0) MosaicEffectStyle.BLUR else MosaicEffectStyle.SPOTLIGHT
                    state.updateProject("切换效果类型") { current ->
                        current.copy(timeline = current.timeline.copy(
                            mosaicClips = current.timeline.mosaicClips.map {
                                if (it.id == clip.id) it.copy(style = style) else it
                            },
                        ))
                    }
                },
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (clip.style == MosaicEffectStyle.BLUR) {
                LabeledSlider(
                    "柔化强度",
                    clip.intensity.toFloat(),
                    0f..1f,
                    { value ->
                        state.updateProject("调整柔化强度") { current ->
                            current.copy(timeline = current.timeline.copy(
                                mosaicClips = current.timeline.mosaicClips.map {
                                    if (it.id == clip.id) it.copy(intensity = value.toDouble()) else it
                                },
                            ))
                        }
                    },
                    valueLabel = { "${(it * 100).roundToInt()}%" },
                )
            } else {
                LabeledSlider(
                    "暗度",
                    clip.spotlightDimming.toFloat(),
                    0f..0.75f,
                    { value ->
                        state.updateProject("调整突出暗度") { current ->
                            current.copy(timeline = current.timeline.copy(
                                mosaicClips = current.timeline.mosaicClips.map {
                                    if (it.id == clip.id) it.copy(spotlightDimming = value.toDouble()) else it
                                },
                            ))
                        }
                    },
                    valueLabel = { "${(it * 100).roundToInt()}%" },
                )
            }
            LabeledSlider(
                "圆角",
                (clip.cornerRadius * 100).toFloat(),
                0f..50f,
                { value ->
                    state.updateProject("调整效果圆角") { current ->
                        current.copy(timeline = current.timeline.copy(
                            mosaicClips = current.timeline.mosaicClips.map {
                                if (it.id == clip.id) it.copy(cornerRadius = value.toDouble() / 100) else it
                            },
                        ))
                    }
                },
                valueLabel = { "${it.roundToInt()}%" },
            )
        }
        InspectorSection("过渡") {
            SegmentedControl(
                options = listOf("无", "线性", "平滑"),
                selectedIndex = when (clip.transitionStyle) {
                    MosaicTransitionStyle.NONE -> 0
                    MosaicTransitionStyle.LINEAR -> 1
                    MosaicTransitionStyle.SMOOTH -> 2
                },
                onSelect = { index ->
                    val style = when (index) {
                        0 -> MosaicTransitionStyle.NONE
                        1 -> MosaicTransitionStyle.LINEAR
                        else -> MosaicTransitionStyle.SMOOTH
                    }
                    state.updateProject("切换效果过渡") { current ->
                        current.copy(timeline = current.timeline.copy(
                            mosaicClips = current.timeline.mosaicClips.map {
                                if (it.id == clip.id) it.copy(transitionStyle = style) else it
                            },
                        ))
                    }
                },
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (clip.transitionStyle != MosaicTransitionStyle.NONE) {
                LabeledSlider(
                    "进入时长",
                    clip.transitionInDuration.toFloat(),
                    0f..2f,
                    { value ->
                        state.updateProject("调整效果进入时长") { current ->
                            current.copy(timeline = current.timeline.copy(
                                mosaicClips = current.timeline.mosaicClips.map {
                                    if (it.id == clip.id) it.copy(transitionInDuration = value.toDouble()) else it
                                },
                            ))
                        }
                    },
                    valueLabel = { "%.2fs".format(it) },
                )
                LabeledSlider(
                    "退出时长",
                    clip.transitionOutDuration.toFloat(),
                    0f..2f,
                    { value ->
                        state.updateProject("调整效果退出时长") { current ->
                            current.copy(timeline = current.timeline.copy(
                                mosaicClips = current.timeline.mosaicClips.map {
                                    if (it.id == clip.id) it.copy(transitionOutDuration = value.toDouble()) else it
                                },
                            ))
                        }
                    },
                    valueLabel = { "%.2fs".format(it) },
                )
            }
        }
        InspectorSection("位置与尺寸") {
            GesturePositionPad(
                state = state,
                point = NormalizedPoint(
                    clip.sourceRect.x + clip.sourceRect.width / 2,
                    clip.sourceRect.y + clip.sourceRect.height / 2,
                ),
                label = "移动效果区域",
                commit = { point -> state.updateProject("移动效果区域") { current -> mosaicMove(current, clip, point) } },
                preview = { point -> state.previewGesture { current -> mosaicMove(current, clip, point) } },
            )
            LabeledSlider(
                "宽度",
                clip.sourceRect.width.toFloat(),
                0.01f..1f,
                { value ->
                    state.updateProject("调整效果区域宽度") { current ->
                        current.copy(timeline = current.timeline.copy(
                            mosaicClips = current.timeline.mosaicClips.map {
                                if (it.id == clip.id) it.copy(
                                    sourceRect = clip.sourceRect.copy(width = value.toDouble()).clamped(),
                                ) else it
                            },
                        ))
                    }
                },
                valueLabel = { "${(it * 100).roundToInt()}%" },
            )
            LabeledSlider(
                "高度",
                clip.sourceRect.height.toFloat(),
                0.01f..1f,
                { value ->
                    state.updateProject("调整效果区域高度") { current ->
                        current.copy(timeline = current.timeline.copy(
                            mosaicClips = current.timeline.mosaicClips.map {
                                if (it.id == clip.id) it.copy(
                                    sourceRect = clip.sourceRect.copy(height = value.toDouble()).clamped(),
                                ) else it
                            },
                        ))
                    }
                },
                valueLabel = { "${(it * 100).roundToInt()}%" },
            )
        }
    }
    FocusEffectInspector(state)
}

@Composable
private fun FocusEffectInspector(state: DogScAppState) {
    val zoom = state.selectedZoom()
    val screen = state.selectedScreenMotion()
    val current = zoom?.focusEffect ?: screen?.focusEffect
    InspectorSection("焦点虚化") {
        if (zoom == null && screen == null) {
            Text("选中一段缩放或屏幕 3D 后，可添加焦点虚化。", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodySmall)
            return@InspectorSection
        }
        ToggleRow(
            title = "启用焦点虚化",
            subtitle = "随动画进入和退出，摄像头与贴图保持清晰",
            checked = current != null,
            onCheckedChange = { enabled ->
                val effect = if (enabled) FocusEffect() else null
                state.updateProject("切换焦点虚化") { project ->
                    if (zoom != null) {
                        project.copy(timeline = project.timeline.copy(
                            zoomClips = project.timeline.zoomClips.map { if (it.id == zoom.id) it.copy(focusEffect = effect) else it },
                        ))
                    } else if (screen != null) {
                        project.copy(timeline = project.timeline.copy(
                            screenMotionClips = project.timeline.screenMotionClips.map { if (it.id == screen.id) it.copy(focusEffect = effect) else it },
                        ))
                    } else project
                }
            },
        )
        if (current != null) {
            SegmentedControl(
                options = listOf("圆形", "线性"),
                selectedIndex = if (current.shape == FocusEffectShape.LINEAR) 1 else 0,
                onSelect = { index ->
                    updateFocus(state, zoom?.id, screen?.id) {
                        if (index == 0) {
                            it.copy(shape = FocusEffectShape.RADIAL)
                        } else {
                            it.copy(
                                shape = FocusEffectShape.LINEAR,
                                target = FocusEffectTarget.FIXED,
                                angleDegrees = it.angleDegrees ?: 0.0,
                                dimming = 0.0,
                            )
                        }
                    }
                },
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (current.shape == FocusEffectShape.LINEAR) {
                LabeledSlider(
                    "方向",
                    (current.angleDegrees ?: 0.0).toFloat(),
                    0f..360f,
                    { value ->
                        updateFocus(state, zoom?.id, screen?.id) {
                            it.copy(angleDegrees = value.toDouble())
                        }
                    },
                    valueLabel = { "${it.roundToInt()}°" },
                )
            } else {
                SegmentedControl(
                    options = listOf("随运镜", "随鼠标", "固定"),
                    selectedIndex = when (current.target) {
                        FocusEffectTarget.ANIMATION -> 0
                        FocusEffectTarget.POINTER -> 1
                        FocusEffectTarget.FIXED -> 2
                    },
                    onSelect = { index ->
                        updateFocus(state, zoom?.id, screen?.id) {
                            it.copy(
                                target = when (index) {
                                    0 -> FocusEffectTarget.ANIMATION
                                    1 -> FocusEffectTarget.POINTER
                                    else -> FocusEffectTarget.FIXED
                                },
                            )
                        }
                    },
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (current.target == FocusEffectTarget.FIXED) {
                    GesturePositionPad(
                        state = state,
                        point = current.center,
                        label = "调整焦点虚化",
                        commit = { point -> updateFocus(state, zoom?.id, screen?.id) { it.copy(center = point) } },
                        preview = { point -> state.previewGesture { focusTransform(it, zoom?.id, screen?.id) { fx -> fx.copy(center = point) } } },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            LabeledSlider("范围", current.size.toFloat(), 0.1f..1f, { value ->
                updateFocus(state, zoom?.id, screen?.id) { it.copy(size = value.toDouble()) }
            })
            LabeledSlider("模糊程度", current.blur.toFloat(), 0f..1f, { value ->
                updateFocus(state, zoom?.id, screen?.id) { it.copy(blur = value.toDouble()) }
            })
            LabeledSlider("羽化范围", current.softness.toFloat(), 0f..1f, { value ->
                updateFocus(state, zoom?.id, screen?.id) { it.copy(softness = value.toDouble()) }
            })
            if (current.shape != FocusEffectShape.LINEAR) {
                LabeledSlider("暗度", current.dimming.toFloat(), 0f..0.75f, { value ->
                    updateFocus(state, zoom?.id, screen?.id) { it.copy(dimming = value.toDouble()) }
                })
            }
        }
    }
}

/** Moves a mosaic/spotlight region so its center lands on [point], clamped to the source. */
private fun mosaicMove(
    current: RecorderProject,
    clip: dev.dogsc.core.model.MosaicClip,
    point: NormalizedPoint,
): RecorderProject = current.copy(timeline = current.timeline.copy(
    mosaicClips = current.timeline.mosaicClips.map {
        if (it.id == clip.id) it.copy(
            sourceRect = clip.sourceRect.copy(
                x = (point.x - clip.sourceRect.width / 2).coerceIn(0.0, 1.0 - clip.sourceRect.width),
                y = (point.y - clip.sourceRect.height / 2).coerceIn(0.0, 1.0 - clip.sourceRect.height),
            ).clamped(),
        ) else it
    },
))

private fun focusTransform(
    project: RecorderProject,
    zoomId: String?,
    screenId: String?,
    transform: (FocusEffect) -> FocusEffect,
): RecorderProject =
    if (zoomId != null) {
        project.copy(timeline = project.timeline.copy(
            zoomClips = project.timeline.zoomClips.map {
                if (it.id == zoomId) it.copy(focusEffect = it.focusEffect?.let(transform)) else it
            },
        ))
    } else if (screenId != null) {
        project.copy(timeline = project.timeline.copy(
            screenMotionClips = project.timeline.screenMotionClips.map {
                if (it.id == screenId) it.copy(focusEffect = it.focusEffect?.let(transform)) else it
            },
        ))
    } else project

private fun updateFocus(
    state: DogScAppState,
    zoomId: String?,
    screenId: String?,
    transform: (FocusEffect) -> FocusEffect,
) {
    state.updateProject("调整焦点虚化") { project -> focusTransform(project, zoomId, screenId, transform) }
}

/** Moves a sticker clip to [point]; shared by the position pad's commit and gesture preview. */
private fun stickerMove(current: RecorderProject, clipId: String, point: NormalizedPoint): RecorderProject =
    current.copy(timeline = current.timeline.copy(
        stickerClips = current.timeline.stickerClips.map {
            if (it.id == clipId) it.copy(position = point) else it
        },
    ))

@Composable
private fun StickerInspector(state: DogScAppState, host: DogScHost) {
    val project = state.project ?: return
    InspectorSection("贴图") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("添加贴图", onClick = {
                host.pickImage { uri -> if (uri != null) state.importSticker(uri) }
            }, icon = Icons.Outlined.Add, compact = true)
            if (state.stickerFrames.isNotEmpty()) {
                Text("${project.timeline.stickerClips.size} 个贴图", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        SubLabel("图片会随项目保存；贴图期间可选择隐藏录屏画面或摄像头。")
    }
    val clips = project.timeline.stickerClips.sortedBy { it.layerIndex }
    if (clips.isEmpty()) {
        Text("还没有贴图", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodySmall)
        return
    }
    InspectorSection("图层") {
        clips.forEachIndexed { index, clip ->
            val selected = (state.selection as? EditorSelection.Sticker)?.clipId == clip.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) DogScColors.SelectionWash else Color.Transparent)
                    .clickable { state.select(EditorSelection.Sticker(clip.id)) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.StickyNote2, null, tint = DogScColors.StickerInk, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text("贴图 · 层 ${index + 1}", color = DogScColors.PrimaryText, style = MaterialTheme.typography.labelMedium)
                    Text(
                        "${formatTimecode(clip.timing.startTime)} · ${formatTimecode(clip.timing.duration)}",
                        color = DogScColors.TertiaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SoftIconButton(Icons.Outlined.DeleteOutline, onClick = {
                    state.updateProject("删除贴图") { TimelineEditor.deleteSticker(it, clip.id) }
                    state.select(EditorSelection.None)
                }, size = 28.dp, iconSize = 15.dp)
            }
        }
    }
    state.selectedSticker()?.let { clip ->
        InspectorSection("布局") {
            GesturePositionPad(
                state = state,
                point = clip.position,
                label = "移动贴图",
                commit = { point -> state.updateProject("移动贴图") { current -> stickerMove(current, clip.id, point) } },
                preview = { point -> state.previewGesture { current -> stickerMove(current, clip.id, point) } },
            )
        }
        if (project.timeline.stickerClips.size > 1) {
            InspectorSection("层级") {
                val ordered = project.timeline.stickerClips.sortedBy { it.layerIndex }
                val rank = ordered.indexOfFirst { it.id == clip.id }.coerceAtLeast(0)
                fun moveTo(target: Int) {
                    val bounded = target.coerceIn(0, ordered.lastIndex)
                    val reordered = ordered.toMutableList().apply {
                        removeAt(rank)
                        add(bounded, clip)
                    }
                    val reindexed = reordered.mapIndexed { index, sticker -> sticker.copy(layerIndex = index) }
                    state.updateProject("调整贴图层级") { current ->
                        current.copy(timeline = current.timeline.copy(stickerClips = reindexed))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PillButton("置底", onClick = { moveTo(0) }, enabled = rank > 0, variant = PillVariant.Ghost, compact = true)
                    PillButton("下移", onClick = { moveTo(rank - 1) }, enabled = rank > 0, variant = PillVariant.Ghost, compact = true)
                    PillButton("上移", onClick = { moveTo(rank + 1) }, enabled = rank < ordered.lastIndex, variant = PillVariant.Ghost, compact = true)
                    PillButton("置顶", onClick = { moveTo(ordered.lastIndex) }, enabled = rank < ordered.lastIndex, variant = PillVariant.Ghost, compact = true)
                }
                Text("当前第 ${rank + 1} 层，共 ${ordered.size} 层", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodySmall)
            }
        }
        InspectorSection("外观") {
            LabeledSlider("宽度", clip.width.toFloat(), 0.02f..1.5f, { value ->
                state.updateProject("调整贴图宽度") { current ->
                    current.copy(timeline = current.timeline.copy(
                        stickerClips = current.timeline.stickerClips.map {
                            if (it.id == clip.id) it.copy(width = value.toDouble()) else it
                        },
                    ))
                }
            }, valueLabel = { "${(it * 100).roundToInt()}%" })
            LabeledSlider("旋转", clip.rotationDegrees.toFloat(), -180f..180f, { value ->
                state.updateProject("旋转贴图") { current ->
                    current.copy(timeline = current.timeline.copy(
                        stickerClips = current.timeline.stickerClips.map {
                            if (it.id == clip.id) it.copy(rotationDegrees = value.toDouble()) else it
                        },
                    ))
                }
            }, valueLabel = { "${it.roundToInt()}°" })
            LabeledSlider("不透明度", clip.opacity.toFloat(), 0f..1f, { value ->
                state.updateProject("调整贴图不透明度") { current ->
                    current.copy(timeline = current.timeline.copy(
                        stickerClips = current.timeline.stickerClips.map {
                            if (it.id == clip.id) it.copy(opacity = value.toDouble()) else it
                        },
                    ))
                }
            })
            LabeledSlider("圆角", clip.cornerRadius.toFloat(), 0f..160f, { value ->
                state.updateProject("调整贴图圆角") { current ->
                    current.copy(timeline = current.timeline.copy(
                        stickerClips = current.timeline.stickerClips.map {
                            if (it.id == clip.id) it.copy(cornerRadius = value.toDouble()) else it
                        },
                    ))
                }
            }, valueLabel = { "${it.roundToInt()}" })
            LabeledSlider("描边", clip.borderWidth.toFloat(), 0f..40f, { value ->
                state.updateProject("调整贴图描边") { current ->
                    current.copy(timeline = current.timeline.copy(
                        stickerClips = current.timeline.stickerClips.map {
                            if (it.id == clip.id) it.copy(borderWidth = value.toDouble()) else it
                        },
                    ))
                }
            }, valueLabel = { "${it.roundToInt()}" })
            if (clip.borderWidth > 0) {
                ColorRow(
                    label = "描边颜色",
                    selected = clip.borderColor,
                    onSelect = { color ->
                        state.updateProject("调整贴图描边") { current ->
                            current.copy(timeline = current.timeline.copy(
                                stickerClips = current.timeline.stickerClips.map {
                                    if (it.id == clip.id) it.copy(borderColor = color) else it
                                },
                            ))
                        }
                    },
                )
            }
            LabeledSlider("阴影", clip.shadowOpacity.toFloat(), 0f..1f, { value ->
                state.updateProject("调整贴图阴影") { current ->
                    current.copy(timeline = current.timeline.copy(
                        stickerClips = current.timeline.stickerClips.map {
                            if (it.id == clip.id) it.copy(shadowOpacity = value.toDouble()) else it
                        },
                    ))
                }
            })
            LabeledSlider("阴影柔化", clip.shadowRadius.toFloat(), 0f..80f, { value ->
                state.updateProject("调整贴图阴影") { current ->
                    current.copy(timeline = current.timeline.copy(
                        stickerClips = current.timeline.stickerClips.map {
                            if (it.id == clip.id) it.copy(shadowRadius = value.toDouble()) else it
                        },
                    ))
                }
            }, valueLabel = { "${it.roundToInt()}" })
        }
        InspectorSection("出入场") {
            Text("动效节奏", color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(5.dp))
            SegmentedControl(
                options = ElementMotionCurve.entries.map { it.displayName },
                selectedIndex = ElementMotionCurve.entries.indexOf(clip.animationCurve),
                onSelect = { index ->
                    val curve = ElementMotionCurve.entries[index]
                    state.updateProject("调整贴图入场") { current ->
                        current.copy(timeline = current.timeline.copy(
                            stickerClips = current.timeline.stickerClips.map {
                                if (it.id == clip.id) it.copy(animationCurve = curve) else it
                            },
                        ))
                    }
                },
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text("入场方式", color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(5.dp))
            AnimationPicker(
                selected = clip.animation,
                onSelect = { preset ->
                    state.updateProject("调整贴图入场") { current ->
                        current.copy(timeline = current.timeline.copy(
                            stickerClips = current.timeline.stickerClips.map {
                                if (it.id == clip.id) it.copy(animation = preset) else it
                            },
                        ))
                    }
                },
            )
            LabeledSlider("入场时长", clip.enterDuration.toFloat(), 0f..2f, { value ->
                state.updateProject("调整贴图入场时长") { current ->
                    current.copy(timeline = current.timeline.copy(
                        stickerClips = current.timeline.stickerClips.map {
                            if (it.id == clip.id) it.copy(enterDuration = value.toDouble()) else it
                        },
                    ))
                }
            }, valueLabel = { "${(it * 100).roundToInt() / 100.0}s" })
            Spacer(Modifier.height(8.dp))
            Text("退场方式", color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(5.dp))
            ToggleRow(
                "退场自动采用反向动作",
                null,
                clip.exitAnimation == null,
                { auto ->
                    state.updateProject("调整贴图出场") { current ->
                        current.copy(timeline = current.timeline.copy(
                            stickerClips = current.timeline.stickerClips.map {
                                if (it.id == clip.id) it.copy(exitAnimation = if (auto) null else clip.animation) else it
                            },
                        ))
                    }
                },
            )
            if (clip.exitAnimation != null) {
                AnimationPicker(
                    selected = clip.exitAnimation!!,
                    onSelect = { preset ->
                        state.updateProject("调整贴图出场") { current ->
                            current.copy(timeline = current.timeline.copy(
                                stickerClips = current.timeline.stickerClips.map {
                                    if (it.id == clip.id) it.copy(exitAnimation = preset) else it
                                },
                            ))
                        }
                    },
                )
            }
            LabeledSlider("出场时长", clip.exitDuration.toFloat(), 0f..2f, { value ->
                state.updateProject("调整贴图出场时长") { current ->
                    current.copy(timeline = current.timeline.copy(
                        stickerClips = current.timeline.stickerClips.map {
                            if (it.id == clip.id) it.copy(exitDuration = value.toDouble()) else it
                        },
                    ))
                }
            }, valueLabel = { "${(it * 100).roundToInt() / 100.0}s" })
        }
        InspectorSection("背景处理") {
            ToggleRow("仅背景", "隐藏录屏画面，仅保留画布背景与贴图", clip.hidesScreen, { value ->
                state.updateProject("切换仅背景") { current ->
                    current.copy(timeline = current.timeline.copy(
                        stickerClips = current.timeline.stickerClips.map {
                            if (it.id == clip.id) it.copy(hidesScreen = value) else it
                        },
                    ))
                }
            })
            ToggleRow("隐藏摄像头", "贴图期间隐藏摄像头", clip.hidesCamera, { value ->
                state.updateProject("切换隐藏摄像头") { current ->
                    current.copy(timeline = current.timeline.copy(
                        stickerClips = current.timeline.stickerClips.map {
                            if (it.id == clip.id) it.copy(hidesCamera = value) else it
                        },
                    ))
                }
            })
            LabeledSlider("录屏虚化", clip.backdropBlur.toFloat(), 0f..80f, { value ->
                state.updateProject("调整贴图背景虚化") { current ->
                    current.copy(timeline = current.timeline.copy(
                        stickerClips = current.timeline.stickerClips.map {
                            if (it.id == clip.id) it.copy(backdropBlur = value.toDouble()) else it
                        },
                    ))
                }
            }, valueLabel = { "${it.roundToInt()}" })
            ToggleRow("同时虚化摄像头", null, clip.backdropBlurIncludesCamera, { value ->
                state.updateProject("切换摄像头背景虚化") { current ->
                    current.copy(timeline = current.timeline.copy(
                        stickerClips = current.timeline.stickerClips.map {
                            if (it.id == clip.id) it.copy(backdropBlurIncludesCamera = value) else it
                        },
                    ))
                }
            })
        }
        InspectorSection("片段时间") {
            LabeledSlider("开始", clip.timing.startTime.toFloat(), 0f..max(state.outputDuration.toFloat() - 0.2f, 0.2f), { value ->
                state.updateProject("调整贴图开始时间") { current ->
                    current.copy(timeline = current.timeline.copy(
                        stickerClips = current.timeline.stickerClips.map {
                            if (it.id == clip.id) it.copy(timing = it.timing.copy(startTime = value.toDouble())) else it
                        },
                    ))
                }
            }, valueLabel = { formatTimecode(it.toDouble()) })
            LabeledSlider("时长", clip.timing.duration.toFloat(), 0.1f..max(state.outputDuration.toFloat(), 0.1f), { value ->
                state.updateProject("调整贴图时长") { current ->
                    current.copy(timeline = current.timeline.copy(
                        stickerClips = current.timeline.stickerClips.map {
                            if (it.id == clip.id) it.copy(timing = it.timing.copy(duration = value.toDouble())) else it
                        },
                    ))
                }
            }, valueLabel = { formatTimecode(it.toDouble()) })
        }
    }
}

@Composable
private fun AnimationPicker(selected: StickerAnimationPreset, onSelect: (StickerAnimationPreset) -> Unit) {
    val presets = listOf(
        StickerAnimationPreset.NONE to "无",
        StickerAnimationPreset.FADE to "淡入",
        StickerAnimationPreset.POP to "弹出",
        StickerAnimationPreset.SLIDE_LEFT to "从左",
        StickerAnimationPreset.SLIDE_RIGHT to "从右",
        StickerAnimationPreset.SLIDE_UP to "从上",
        StickerAnimationPreset.SLIDE_DOWN to "从下",
        StickerAnimationPreset.SLIDE_TOP_LEFT to "左上",
        StickerAnimationPreset.SLIDE_TOP_RIGHT to "右上",
        StickerAnimationPreset.SLIDE_BOTTOM_LEFT to "左下",
        StickerAnimationPreset.SLIDE_BOTTOM_RIGHT to "右下",
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        presets.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (preset, label) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (preset == selected) DogScColors.SelectionWash else Color.Transparent)
                            .border(
                                1.dp,
                                if (preset == selected) DogScColors.PrimaryText.copy(alpha = 0.16f) else DogScColors.Hairline,
                                RoundedCornerShape(7.dp),
                            )
                            .clickable { onSelect(preset) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = DogScColors.PrimaryText, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun MotionInspector(state: DogScAppState) {
    val project = state.project ?: return
    InspectorSection("自动跟随") {
        Text(
            "根据录制的触控点击生成缩放片段。相邻点击会共享一个稳定的取景区域，避免画面频繁跳动。",
            color = DogScColors.SecondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("生成自动运镜", onClick = { state.applyAutoZoomFromTouch() }, icon = Icons.Outlined.RestartAlt, compact = true)
            if (project.timeline.zoomClips.any { it.origin == dev.dogsc.core.model.ZoomKeyframeOrigin.AUTOMATIC }) {
                PillButton("清除自动片段", onClick = {
                    state.updateProject("清除自动运镜") { current ->
                        current.copy(timeline = current.timeline.copy(
                            zoomClips = current.timeline.zoomClips.filter { it.origin == dev.dogsc.core.model.ZoomKeyframeOrigin.MANUAL },
                        ))
                    }
                }, variant = PillVariant.Ghost, compact = true)
            }
        }
        Spacer(Modifier.height(8.dp))
        LabeledSlider(
            "放大比例",
            (project.motion.defaultZoomScale ?: 1.6).toFloat(),
            1f..6f,
            { value -> state.updateMotion("调整默认放大比例") { it.copy(defaultZoomScale = value.toDouble()) } },
            valueLabel = { formatScale(it.toDouble()) },
        )
        LabeledSlider(
            "过渡时长",
            project.motion.defaultZoomTransitionDuration.toFloat(),
            0f..3f,
            { value -> state.updateMotion("调整默认过渡时长") { it.copy(defaultZoomTransitionDuration = value.toDouble()) } },
            valueLabel = { "${(it * 100).roundToInt() / 100.0}s" },
        )
        EasingPicker(
            selected = project.motion.defaultZoomEasing,
            onSelect = { easing -> state.updateMotion("调整默认缓动") { it.copy(defaultZoomEasing = easing) } },
        )
        Spacer(Modifier.height(6.dp))
        PillButton("将倍率应用到全部缩放", onClick = {
            state.applyZoomScaleToAll(project.motion.defaultZoomScale ?: 1.6)
        }, variant = PillVariant.Ghost, compact = true)
    }
    val zoom = state.selectedZoom()
    if (zoom != null) {
        InspectorSection("选中的缩放片段") {
            LabeledSlider("放大比例", zoom.scale.toFloat(), 1f..6f, { value ->
                state.updateProject("调整缩放倍率") { current ->
                    TimelineEditor.updateZoom(current, zoom.id) { it.copy(scale = value.toDouble()) }
                }
            }, valueLabel = { formatScale(it.toDouble()) })
            GesturePositionPad(
                state = state,
                point = zoom.focus,
                label = "调整缩放焦点",
                commit = { point -> state.updateProject("调整缩放焦点") { current -> TimelineEditor.updateZoom(current, zoom.id) { it.copy(focus = point) } } },
                preview = { point -> state.previewGesture { current -> TimelineEditor.updateZoom(current, zoom.id) { it.copy(focus = point) } } },
            )
            LabeledSlider("进入时长", zoom.enterDuration.toFloat(), 0f..3f, { value ->
                state.updateProject("调整缩放进入时长") { current ->
                    TimelineEditor.updateZoom(current, zoom.id) { it.copy(enterDuration = value.toDouble()) }
                }
            }, valueLabel = { "${(it * 100).roundToInt() / 100.0}s" })
            LabeledSlider("退出时长", zoom.exitDuration.toFloat(), 0f..3f, { value ->
                state.updateProject("调整缩放退出时长") { current ->
                    TimelineEditor.updateZoom(current, zoom.id) { it.copy(exitDuration = value.toDouble()) }
                }
            }, valueLabel = { "${(it * 100).roundToInt() / 100.0}s" })
            EasingPicker(
                selected = zoom.easing,
                onSelect = { easing ->
                    state.updateProject("调整缩放缓动") { current ->
                        TimelineEditor.updateZoom(current, zoom.id) { it.copy(easing = easing) }
                    }
                },
                customCurve = zoom.customCurve,
                onCustomCurveChange = { curve ->
                    state.updateProject("调整自定义缓动") { current ->
                        TimelineEditor.updateZoom(current, zoom.id) {
                            it.copy(easing = ZoomEasingPreset.CUSTOM, customCurve = curve)
                        }
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("类型", color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                SegmentedControl(
                    options = listOf("自动", "手动"),
                    selectedIndex = if (zoom.origin == dev.dogsc.core.model.ZoomKeyframeOrigin.AUTOMATIC) 0 else 1,
                    onSelect = { index ->
                        state.updateProject("切换缩放类型") { current ->
                            TimelineEditor.updateZoom(current, zoom.id) {
                                it.copy(origin = if (index == 0) dev.dogsc.core.model.ZoomKeyframeOrigin.AUTOMATIC else dev.dogsc.core.model.ZoomKeyframeOrigin.MANUAL)
                            }
                        }
                    },
                    compact = true,
                )
            }
        }
    } else {
        InspectorSection("手动缩放") {
            Text("在时间线上拖动空白区域创建缩放，或点击下方按钮在播放头添加。", color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            PillButton("在播放头添加缩放", onClick = {
                val start = state.effectStartTime(state.playhead)
                val end = min(start + 1.2, state.outputDuration)
                if (end - start >= 0.2) {
                    state.updateProject("添加缩放") {
                        TimelineEditor.addZoom(
                            it, start, end, it.motion.defaultZoomScale ?: 1.4,
                            NormalizedPoint.Center, it.motion.defaultZoomEasing, duration = state.outputDuration,
                        )
                    }
                }
            }, icon = Icons.Outlined.Add, compact = true)
        }
    }


    InspectorSection("开场动效") {
        val opening = project.openingSequence
        ToggleRow(
            title = "启用开场",
            subtitle = "让屏幕、贴图和摄像头按顺序进入画面",
            checked = opening.isEnabled,
            onCheckedChange = { enabled ->
                state.updateOpening("切换开场动效") { it.copy(isEnabled = enabled) }
            },
        )
        if (opening.isEnabled) {
            SegmentedControl(
                options = dev.dogsc.core.model.OpeningSequencePreset.entries.map { it.displayName },
                selectedIndex = dev.dogsc.core.model.OpeningSequencePreset.entries.indexOf(opening.preset),
                onSelect = { index ->
                    state.updateOpening("更换开场预设") {
                        it.copy(preset = dev.dogsc.core.model.OpeningSequencePreset.entries[index])
                    }
                },
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LabeledSlider(
                "总时长",
                opening.duration.toFloat(),
                0.4f..8f,
                { value -> state.updateOpening("调整开场时长") { it.copy(duration = value.toDouble()) } },
                valueLabel = { "${(it * 10).roundToInt() / 10.0}s" },
            )
            LabeledSlider(
                "元素间隔",
                opening.stagger.toFloat(),
                0f..opening.maximumStagger(opening.includedElements.size).toFloat().coerceAtLeast(0.01f),
                { value -> state.updateOpening("调整开场间隔") { it.copy(stagger = value.toDouble()) } },
                valueLabel = { "${(it * 100).roundToInt() / 100.0}s" },
            )
            SubLabel("参与元素与顺序")
            val all = dev.dogsc.core.model.OpeningSequenceElement.entries
            all.forEachIndexed { index, element ->
                val included = opening.includedElements.contains(element)
                val orderIndex = opening.elementOrder.indexOf(element)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${orderIndex + 1}",
                        color = DogScColors.TertiaryText,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(18.dp),
                    )
                    Text(
                        element.displayName,
                        color = if (included) DogScColors.PrimaryText else DogScColors.TertiaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    SoftIconButton(
                        androidx.compose.material.icons.Icons.Outlined.ArrowUpward,
                        onClick = {
                            if (orderIndex > 0) {
                                val order = opening.elementOrder.toMutableList()
                                val tmp = order[orderIndex - 1]
                                order[orderIndex - 1] = order[orderIndex]
                                order[orderIndex] = tmp
                                state.updateOpening("调整开场顺序") { it.copy(elementOrder = order) }
                            }
                        },
                        size = 24.dp,
                        iconSize = 13.dp,
                        enabled = orderIndex > 0,
                    )
                    SoftIconButton(
                        androidx.compose.material.icons.Icons.Outlined.ArrowDownward,
                        onClick = {
                            if (orderIndex in 0 until opening.elementOrder.lastIndex) {
                                val order = opening.elementOrder.toMutableList()
                                val tmp = order[orderIndex + 1]
                                order[orderIndex + 1] = order[orderIndex]
                                order[orderIndex] = tmp
                                state.updateOpening("调整开场顺序") { it.copy(elementOrder = order) }
                            }
                        },
                        size = 24.dp,
                        iconSize = 13.dp,
                        enabled = orderIndex in 0 until opening.elementOrder.lastIndex,
                    )
                    DogScSwitch(
                        checked = included,
                        onCheckedChange = { value ->
                            val next = if (value) opening.includedElements + element else opening.includedElements - element
                            state.updateOpening("调整开场参与元素") { it.copy(includedElements = next) }
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            SegmentedControl(
                options = dev.dogsc.core.model.ElementMotionCurve.entries.map { it.displayName },
                selectedIndex = dev.dogsc.core.model.ElementMotionCurve.entries.indexOf(opening.motionCurve),
                onSelect = { index ->
                    state.updateOpening("调整开场曲线") {
                        it.copy(motionCurve = dev.dogsc.core.model.ElementMotionCurve.entries[index])
                    }
                },
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    val screen = state.selectedScreenMotion()
    InspectorSection("屏幕 3D") {
        if (screen == null) {
            Text("让画面在空间中倾斜、旋转或透视，可与缩放同时使用。", color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            PillButton("在播放头添加屏幕 3D", onClick = {
                val start = state.effectStartTime(state.playhead)
                val end = min(start + 2.0, state.outputDuration)
                if (end - start >= 0.2) {
                    state.updateProject("添加屏幕 3D") {
                        TimelineEditor.addScreenMotion(
                            it, start, end - start,
                            ScreenMotionState(position = NormalizedPoint.Center, scale = 1.08, rotationX = 7.0, rotationY = -14.0, perspective = 0.7),
                            ZoomEasingPreset.CUBIC, leadIn = 0.7, returnDuration = 0.7,
                        )
                    }
                }
            }, icon = Icons.Outlined.Add, compact = true)
        } else {
            LabeledSlider("大小", screen.target.scale.toFloat(), 0.2f..2f, { value ->
                updateScreen(state, screen.id) { it.copy(target = it.target.copy(scale = value.toDouble())) }
            }, valueLabel = { formatScale(it.toDouble()) })
            LabeledSlider("倾斜 X", screen.target.rotationX.toFloat(), -45f..45f, { value ->
                updateScreen(state, screen.id) { it.copy(target = it.target.copy(rotationX = value.toDouble())) }
            }, valueLabel = { "${it.roundToInt()}°" })
            LabeledSlider("倾斜 Y", screen.target.rotationY.toFloat(), -45f..45f, { value ->
                updateScreen(state, screen.id) { it.copy(target = it.target.copy(rotationY = value.toDouble())) }
            }, valueLabel = { "${it.roundToInt()}°" })
            LabeledSlider("旋转 Z", screen.target.rotationZ.toFloat(), -30f..30f, { value ->
                updateScreen(state, screen.id) { it.copy(target = it.target.copy(rotationZ = value.toDouble())) }
            }, valueLabel = { "${it.roundToInt()}°" })
            LabeledSlider("透视", screen.target.perspective.toFloat(), 0f..2f, { value ->
                updateScreen(state, screen.id) { it.copy(target = it.target.copy(perspective = value.toDouble())) }
            })
            GesturePositionPad(
                state = state,
                point = screen.target.position,
                label = "调整屏幕 3D",
                commit = { point -> updateScreen(state, screen.id) { it.copy(target = it.target.copy(position = point)) } },
                preview = { point -> state.previewGesture { screenTransform(it, screen.id) { c -> c.copy(target = c.target.copy(position = point)) } } },
            )
            TimingSliders(
                timing = screen.timing,
                maxDuration = (state.outputDuration - screen.timing.startTime).coerceAtLeast(0.08),
            ) { timing -> updateScreen(state, screen.id) { it.copy(timing = timing) } }
            PillButton("删除屏幕 3D", onClick = {
                state.updateProject("删除屏幕 3D") { TimelineEditor.deleteScreenMotion(it, screen.id) }
                state.select(EditorSelection.None)
            }, variant = PillVariant.Ghost, compact = true)
        }
    }
}

@Composable
private fun TimingSliders(
    timing: TransitionTiming,
    maxDuration: Double,
    onChange: (TransitionTiming) -> Unit,
) {
    LabeledSlider(
        "过渡时长",
        min(timing.leadInDuration, timing.duration).toFloat(),
        0f..timing.duration.toFloat().coerceAtLeast(0.01f),
        { value ->
            onChange(timing.copy(
                leadInDuration = value.toDouble(),
                preferredLeadInDuration = value.toDouble(),
                leadInProgressOffset = 0.0,
            ))
        },
        valueLabel = { "${(it * 100).roundToInt() / 100.0}s" },
    )
    LabeledSlider(
        "时长",
        timing.duration.toFloat(),
        0.08f..max(maxDuration, 0.08).toFloat(),
        { value -> onChange(timing.copy(duration = value.toDouble())) },
        valueLabel = { "${(it * 100).roundToInt() / 100.0}s" },
    )
    val hold = timing.duration - min(timing.leadInDuration, timing.duration)
    if (hold > 0.01) {
        Text(
            "过渡完成后保持 ${(hold * 100).roundToInt() / 100.0}s",
            color = DogScColors.TertiaryText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun screenTransform(
    current: RecorderProject,
    id: String,
    transform: (dev.dogsc.core.model.ScreenMotionClip) -> dev.dogsc.core.model.ScreenMotionClip,
): RecorderProject = current.copy(timeline = current.timeline.copy(
    screenMotionClips = current.timeline.screenMotionClips.map { if (it.id == id) transform(it) else it },
))

private fun updateScreen(state: DogScAppState, id: String, transform: (dev.dogsc.core.model.ScreenMotionClip) -> dev.dogsc.core.model.ScreenMotionClip) {
    state.updateProject("调整屏幕 3D") { current -> screenTransform(current, id, transform) }
}

@Composable
private fun EasingPicker(
    selected: ZoomEasingPreset,
    onSelect: (ZoomEasingPreset) -> Unit,
    customCurve: dev.dogsc.core.model.ZoomBezierCurve? = null,
    onCustomCurveChange: ((dev.dogsc.core.model.ZoomBezierCurve) -> Unit)? = null,
) {
    val options = buildList {
        add(ZoomEasingPreset.SPRING)
        add(ZoomEasingPreset.CUBIC)
        add(ZoomEasingPreset.QUINTIC)
        add(ZoomEasingPreset.LINEAR)
        if (onCustomCurveChange != null) add(ZoomEasingPreset.CUSTOM)
    }
    SectionLabel("缓动")
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { easing ->
            Box(
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (easing == selected) DogScColors.SelectionWash else Color.Transparent)
                    .border(
                        1.dp,
                        if (easing == selected) DogScColors.PrimaryText.copy(alpha = 0.16f) else DogScColors.Hairline,
                        RoundedCornerShape(7.dp),
                    )
                    .clickable { onSelect(easing) },
                contentAlignment = Alignment.Center,
            ) {
                Text(easing.shortName, color = DogScColors.PrimaryText, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (selected == ZoomEasingPreset.CUSTOM && customCurve != null && onCustomCurveChange != null) {
        Spacer(Modifier.height(8.dp))
        Text("贝塞尔控制点", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodySmall)
        LabeledSlider("P1 X", customCurve.x1.toFloat(), 0f..1f, { value ->
            onCustomCurveChange(customCurve.copy(x1 = value.toDouble()))
        }, valueLabel = { "%.2f".format(it) })
        LabeledSlider("P1 Y", customCurve.y1.toFloat(), 0f..1f, { value ->
            onCustomCurveChange(customCurve.copy(y1 = value.toDouble()))
        }, valueLabel = { "%.2f".format(it) })
        LabeledSlider("P2 X", customCurve.x2.toFloat(), 0f..1f, { value ->
            onCustomCurveChange(customCurve.copy(x2 = value.toDouble()))
        }, valueLabel = { "%.2f".format(it) })
        LabeledSlider("P2 Y", customCurve.y2.toFloat(), 0f..1f, { value ->
            onCustomCurveChange(customCurve.copy(y2 = value.toDouble()))
        }, valueLabel = { "%.2f".format(it) })
    }
}

@Composable
private fun CursorInspector(state: DogScAppState) {
    val project = state.project ?: return
    val cursor = project.cursorStyle
    InspectorSection("光标样式") {
        val choices = CursorCatalog.pickerChoices
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            choices.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { asset ->
                        ChoiceTile(
                            title = CursorCatalog.displayName(asset),
                            selected = cursor.assetID == asset,
                            onClick = {
                                state.updateCursor("更换光标样式") { it.copy(assetID = asset) }
                            },
                            modifier = Modifier.weight(1f),
                            leading = {
                                if (asset == CursorAssetID.TouchDot) {
                                    Box(Modifier.size(18.dp).clip(CircleShape).background(DogScColors.Mint))
                                }
                            },
                        )
                    }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LabeledSlider("大小", cursor.size.toFloat(), 0.25f..4f, { value ->
            state.updateCursor("调整光标大小") { it.copy(size = value.toDouble()) }
        }, valueLabel = { formatScale(it.toDouble()) })
        ToggleRow("静止后隐藏", "光标停止移动后淡出，适合演示型录制", cursor.hideWhenIdle, { value ->
            state.updateCursor("切换静止隐藏") { it.copy(hideWhenIdle = value) }
        })
        if (cursor.hideWhenIdle) {
            LabeledSlider("静止隐藏延迟", cursor.idleDelay.toFloat(), 0.2f..8f, { value ->
                state.updateCursor("调整静止隐藏延迟") { it.copy(idleDelay = value.toDouble()) }
            }, valueLabel = { "${(it * 100).roundToInt() / 100.0}s" })
        }
    }
    InspectorSection("点击反馈") {
        val effects = listOf(
            CursorClickEffectStyle.RIPPLE,
            CursorClickEffectStyle.GLOW,
            CursorClickEffectStyle.PULSE,
            CursorClickEffectStyle.BURST,
            CursorClickEffectStyle.NONE,
        )
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            effects.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { effect ->
                        ChoiceTile(
                            title = effect.displayName,
                            selected = cursor.clickEffectStyle == effect,
                            onClick = { state.updateCursor("更换点击效果") { it.copy(clickEffectStyle = effect) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (cursor.clickEffectStyle != CursorClickEffectStyle.NONE) {
            Spacer(Modifier.height(8.dp))
            ColorRow(
                label = "点击颜色",
                selected = cursor.clickColor ?: HexColor.White,
                onSelect = { color -> state.updateCursor("调整点击颜色") { it.copy(clickColor = color) } },
            )
            LabeledSlider("不透明度", cursor.clickOpacity.toFloat(), 0.05f..1f, { value ->
                state.updateCursor("调整点击不透明度") { it.copy(clickOpacity = value.toDouble()) }
            })
            LabeledSlider("大小", cursor.clickScale.toFloat(), 0.4f..3f, { value ->
                state.updateCursor("调整点击效果大小") { it.copy(clickScale = value.toDouble()) }
            }, valueLabel = { formatScale(it.toDouble()) })
        }
    }
    InspectorSection("运动") {
        SegmentedControl(
            options = CursorMotionStyle.entries.map { it.displayName },
            selectedIndex = CursorMotionStyle.entries.indexOf(cursorMotionSafe(project.motion.cursor)),
            onSelect = { index ->
                state.updateMotion("调整光标运动") { it.copy(cursor = CursorMotionStyle.entries[index]) }
            },
            compact = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        LabeledSlider("摆动强度", cursor.motionTiltStrength.toFloat(), 2f..4f, { value ->
            state.updateCursor("调整光标摆动") { it.copy(motionTiltStrength = value.toDouble()) }
        }, valueLabel = { "${(it * 10).roundToInt() / 10.0}" })
        Spacer(Modifier.height(6.dp))
        Text("高级平滑参数", color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
        LabeledSlider("光标质量", project.motion.cursorSpringMass.toFloat(), 0.5f..8f, { value ->
            state.updateMotion("调整光标弹簧") { it.copy(cursorSpringMass = value.toDouble()) }
        }, valueLabel = { "${(it * 10).roundToInt() / 10.0}" })
        LabeledSlider("光标刚度", project.motion.cursorSpringStiffness.toFloat(), 40f..1200f, { value ->
            state.updateMotion("调整光标弹簧") { it.copy(cursorSpringStiffness = value.toDouble()) }
        }, valueLabel = { it.roundToInt().toString() })
        LabeledSlider("光标阻尼", project.motion.cursorSpringDamping.toFloat(), 8f..180f, { value ->
            state.updateMotion("调整光标弹簧") { it.copy(cursorSpringDamping = value.toDouble()) }
        }, valueLabel = { it.roundToInt().toString() })
    }
    if (state.pointerEvents.isEmpty()) {
        Text("当前项目没有记录触控事件。", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodySmall)
    } else {
        Text("已记录 ${state.pointerEvents.size} 个触控事件。", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodySmall)
    }
}

private fun cursorMotionSafe(value: CursorMotionStyle): CursorMotionStyle = value

@Composable
private fun CameraInspector(state: DogScAppState) {
    val project = state.project ?: return
    val camera = project.camera
    InspectorSection("摄像头") {
        if (project.media?.camera == null) {
            Text("当前项目没有摄像头素材。录制时开启摄像头，或在设置中重新录制。", color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
        } else {
            ToggleRow("显示摄像头", "关闭后预览和导出都不显示画中画", !camera.isHidden, { visible ->
                state.updateCamera("切换摄像头显示") { it.copy(isHidden = !visible) }
            })
            ToggleRow("镜像摄像头", "前置摄像头通常需要镜像", camera.isMirrored, { value ->
                state.updateCamera("切换摄像头镜像") { it.copy(isMirrored = value) }
            })
            val media = project.media?.camera
            val width = media?.pixelWidth
            val height = media?.pixelHeight
            if (width != null && height != null && width > 0 && height > 0) {
                Text("源尺寸 ${width}×${height}", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    InspectorSection("形状与位置") {
        SegmentedControl(
            options = CameraShape.entries.map { it.displayName },
            selectedIndex = CameraShape.entries.indexOf(camera.shape),
            onSelect = { index ->
                state.updateCamera("更换摄像头形状") { it.copy(shape = CameraShape.entries[index]) }
            },
            compact = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        GesturePositionPad(
            state = state,
            point = camera.position,
            label = "调整摄像头位置",
            commit = { point -> state.updateCamera("调整摄像头位置") { it.copy(position = point) } },
            preview = { point -> state.previewGesture { it.copy(camera = it.camera.copy(position = point)) } },
        )
        LabeledSlider("摄像头大小", camera.size.toFloat(), 0.05f..1f, { value ->
            state.updateCamera("调整摄像头大小") { it.copy(size = value.toDouble()) }
        }, valueLabel = { "${(it * 100).roundToInt()}%" })
        LabeledSlider("圆角程度", camera.roundness.toFloat(), 0f..1f, { value ->
            state.updateCamera("调整摄像头圆角") { it.copy(roundness = value.toDouble()) }
        })
        LabeledSlider("描边", camera.borderWidth.toFloat(), 0f..18f, { value ->
            state.updateCamera("调整摄像头描边") { it.copy(borderWidth = value.toDouble()) }
        }, valueLabel = { "${it.roundToInt()}" })
        LabeledSlider("阴影强度", camera.shadowStrength.toFloat(), 0f..1f, { value ->
            state.updateCamera("调整摄像头阴影") { it.copy(shadowStrength = value.toDouble()) }
        })
    }
    InspectorSection("取景") {
        GesturePositionPad(
            state = state,
            point = camera.contentPosition,
            label = "调整摄像头取景",
            commit = { point -> state.updateCamera("调整摄像头取景") { it.copy(contentPosition = point) } },
            preview = { point -> state.previewGesture { it.copy(camera = it.camera.copy(contentPosition = point)) } },
        )
        LabeledSlider("蒙版内素材缩放", camera.contentScale.toFloat(), 1f..4f, { value ->
            state.updateCamera("调整摄像头取景缩放") { it.copy(contentScale = value.toDouble()) }
        }, valueLabel = { formatScale(it.toDouble()) })
        LabeledSlider("缩放联动", camera.scaleDuringZoom.toFloat(), 0.1f..2f, { value ->
            state.updateCamera("调整摄像头缩放联动") { it.copy(scaleDuringZoom = value.toDouble()) }
        }, valueLabel = { formatScale(it.toDouble()) })
        SubLabel("跟随屏幕缩放调整摄像头大小；1 倍保持原大小。")
    }
    if (project.media?.camera != null) {
        CameraSyncAnchorSection(state)
    }
    val clip = state.selectedCameraMotion()
    InspectorSection("摄像运动") {
        if (clip == null) {
            Text("在播放头添加摄像运动，可让摄像头放大到全屏或移动到新的位置。", color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            PillButton("在播放头添加摄像运动", onClick = {
                val start = state.effectStartTime(state.playhead)
                val end = min(start + 2.0, state.outputDuration)
                if (end - start >= 0.2) {
                    state.updateProject("添加摄像运动") {
                        TimelineEditor.addCameraMotion(
                            it, start, end - start,
                            CameraMotionState(layout = CameraLayoutMode.Fullscreen, position = NormalizedPoint.Center, size = 0.5),
                            ZoomEasingPreset.CUBIC, leadIn = 0.7, returnDuration = 0.7,
                        )
                    }
                }
            }, icon = Icons.Outlined.Add, compact = true, enabled = project.media?.camera != null)
        } else {
            ToggleRow(
                "显示摄像头",
                null,
                clip.target.opacity > 0.001,
                { visible ->
                    updateCameraClip(state, clip.id) {
                        it.copy(target = it.target.copy(opacity = if (visible) 1.0 else 0.0))
                    }
                },
            )
            LabeledSlider("不透明度", clip.target.opacity.toFloat(), 0f..1f, { value ->
                updateCameraClip(state, clip.id) { it.copy(target = it.target.copy(opacity = value.toDouble())) }
            }, valueLabel = { "${(it * 100).roundToInt()}%" })
            if (clip.target.opacity > 0.001) {
                SegmentedControl(
                    options = listOf("画中画", "全屏"),
                    selectedIndex = if (clip.target.layout == CameraLayoutMode.Fullscreen) 1 else 0,
                    onSelect = { index ->
                        updateCameraClip(state, clip.id) {
                            it.copy(target = it.target.copy(
                                layout = if (index == 0) CameraLayoutMode.Shape(project.camera.shape) else CameraLayoutMode.Fullscreen,
                            ))
                        }
                    },
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val shape = (clip.target.layout as? CameraLayoutMode.Shape)?.shape
                if (shape != null) {
                    Spacer(Modifier.height(6.dp))
                    SegmentedControl(
                        options = CameraShape.entries.map { it.displayName },
                        selectedIndex = CameraShape.entries.indexOf(shape),
                        onSelect = { index ->
                            updateCameraClip(state, clip.id) {
                                it.copy(target = it.target.copy(layout = CameraLayoutMode.Shape(CameraShape.entries[index])))
                            }
                        },
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (shape != CameraShape.CIRCLE) {
                        LabeledSlider("圆角", clip.target.roundness.toFloat(), 0f..1f, { value ->
                            updateCameraClip(state, clip.id) {
                                it.copy(target = it.target.copy(roundness = value.toDouble()))
                            }
                        }, valueLabel = { "${(it * 100).roundToInt()}%" })
                    }
                }
                Spacer(Modifier.height(6.dp))
                GesturePositionPad(
                    state = state,
                    point = clip.target.position,
                    label = "调整摄像运动",
                    commit = { point -> updateCameraClip(state, clip.id) { it.copy(target = it.target.copy(position = point)) } },
                    preview = { point -> state.previewGesture { cameraClipTransform(it, clip.id) { c -> c.copy(target = c.target.copy(position = point)) } } },
                )
                LabeledSlider("大小", clip.target.size.toFloat(), 0.05f..1f, { value ->
                    updateCameraClip(state, clip.id) { it.copy(target = it.target.copy(size = value.toDouble())) }
                }, valueLabel = { "${(it * 100).roundToInt()}%" })
            }
            TimingSliders(
                timing = clip.timing,
                maxDuration = (state.outputDuration - clip.timing.startTime).coerceAtLeast(0.08),
            ) { timing -> updateCameraClip(state, clip.id) { it.copy(timing = timing) } }
            PillButton("删除摄像运动", onClick = {
                state.updateProject("删除摄像运动") { TimelineEditor.deleteCameraMotion(it, clip.id) }
                state.select(EditorSelection.None)
            }, variant = PillVariant.Ghost, compact = true)
        }
    }
}

private fun cameraClipTransform(
    current: RecorderProject,
    id: String,
    transform: (dev.dogsc.core.model.CameraMotionClip) -> dev.dogsc.core.model.CameraMotionClip,
): RecorderProject = current.copy(timeline = current.timeline.copy(
    cameraMotionClips = current.timeline.cameraMotionClips.map { if (it.id == id) transform(it) else it },
))

private fun updateCameraClip(
    state: DogScAppState,
    id: String,
    transform: (dev.dogsc.core.model.CameraMotionClip) -> dev.dogsc.core.model.CameraMotionClip,
) {
    state.updateProject("调整摄像运动") { current -> cameraClipTransform(current, id, transform) }
}

@Composable
private fun AudioInspector(state: DogScAppState) {
    val project = state.project ?: return
    InspectorSection("声音") {
        ToggleRow(
            "系统声音",
            "录屏中的应用与系统声音",
            !project.audio.isSystemMuted,
            { value -> state.updateProject("切换系统声音") { it.copy(audio = it.audio.copy(isSystemMuted = !value)) } },
            leading = { Icon(Icons.Outlined.GraphicEq, null, tint = DogScColors.SecondaryText) },
        )
        LabeledSlider(
            "系统声音音量",
            project.audio.systemVolume.toFloat(),
            0f..1f,
            { value -> state.updateProject("调整系统声音音量") { it.copy(audio = it.audio.copy(systemVolume = value.toDouble())) } },
            valueLabel = { "${(it * 100).roundToInt()}%" },
        )
        HairlineDivider(Modifier.padding(vertical = 6.dp))
        ToggleRow(
            "麦克风",
            if (project.media?.microphone != null) "独立录制的人声轨道" else "当前项目没有麦克风素材",
            !project.audio.isMicrophoneMuted,
            { value -> state.updateProject("切换麦克风") { it.copy(audio = it.audio.copy(isMicrophoneMuted = !value)) } },
            enabled = project.media?.microphone != null,
            leading = { Icon(Icons.Outlined.Mic, null, tint = DogScColors.SecondaryText) },
        )
        LabeledSlider(
            "麦克风音量",
            project.audio.microphoneVolume.toFloat(),
            0f..1f,
            { value -> state.updateProject("调整麦克风音量") { it.copy(audio = it.audio.copy(microphoneVolume = value.toDouble())) } },
            valueLabel = { "${(it * 100).roundToInt()}%" },
        )
    }
    val primary = state.selection as? EditorSelection.Primary
    if (primary != null) {
        val segment = TimelineEditor.segments(project, state.sourceDuration)
            .firstOrNull { it.id == primary.segmentId }
        InspectorSection("片段速度") {
            if (segment == null) {
                Text("当前片段使用完整录制，先分割或裁切后才能单独变速。", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodySmall)
            } else {
                LabeledSlider(
                    "播放速度",
                    segment.playbackRate.toFloat(),
                    1f..100f,
                    { value -> state.setPrimaryPlaybackRate(segment.id, value.toDouble()) },
                    valueLabel = { "${(it * 10).roundToInt() / 10.0}×" },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 100.0).forEach { rate ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(26.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (kotlin.math.abs(segment.playbackRate - rate) < 0.05) DogScColors.SelectionWash else Color.Transparent)
                                .border(1.dp, DogScColors.Hairline, RoundedCornerShape(7.dp))
                                .clickable { state.setPrimaryPlaybackRate(segment.id, rate) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (rate == rate.toInt().toDouble()) "${rate.toInt()}×" else "${rate}×",
                                color = DogScColors.PrimaryText,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                SubLabel("动画轨道保持在输出时间上；预览与导出共用同一时间映射。")
            }
        }
        InspectorSection("片段声音") {
            val overrides = project.timeline.primarySegmentAudioOverrides[primary.segmentId]
            Text(
                if (overrides == null) "继承全片声音设置" else "已覆盖此段的声音设置",
                color = DogScColors.SecondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                "系统声音",
                "仅作用于这个片段",
                !(overrides?.isSystemMuted ?: project.audio.isSystemMuted),
                { value -> state.updatePrimaryAudio(primary.segmentId) { it.copy(isSystemMuted = !value) } },
            )
            LabeledSlider(
                "系统声音音量",
                (overrides?.systemVolume ?: project.audio.systemVolume).toFloat(),
                0f..1f,
                { value -> state.updatePrimaryAudio(primary.segmentId) { it.copy(systemVolume = value.toDouble()) } },
                valueLabel = { "${(it * 100).roundToInt()}%" },
            )
            ToggleRow(
                "麦克风",
                "仅作用于这个片段",
                !(overrides?.isMicrophoneMuted ?: project.audio.isMicrophoneMuted),
                { value -> state.updatePrimaryAudio(primary.segmentId) { it.copy(isMicrophoneMuted = !value) } },
                enabled = project.media?.microphone != null,
            )
            LabeledSlider(
                "麦克风音量",
                (overrides?.microphoneVolume ?: project.audio.microphoneVolume).toFloat(),
                0f..1f,
                { value -> state.updatePrimaryAudio(primary.segmentId) { it.copy(microphoneVolume = value.toDouble()) } },
                valueLabel = { "${(it * 100).roundToInt()}%" },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("恢复继承", onClick = {
                    state.updateProject("恢复片段声音继承") {
                        TimelineEditor.updatePrimaryAudioOverride(it, primary.segmentId, null)
                    }
                }, variant = PillVariant.Ghost, compact = true, enabled = overrides != null)
            }
        }
    }
    InspectorSection("说明") {
        Text(
            "系统声音与麦克风会分别保留，导出时按全片或分段设置混音。M4A 仅导出声音。",
            color = DogScColors.TertiaryText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ColorRow(label: String, selected: HexColor, onSelect: (HexColor) -> Unit) {
    val palette = listOf(
        HexColor.White,
        HexColor(0x1B1C20),
        HexColor(0xF2AE47),
        HexColor(0x5B8DEF),
        HexColor(0x85D142),
        HexColor(0xE68AB5),
        HexColor(0x9B7BD8),
        HexColor(0x7A7C84),
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(74.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            palette.forEach { color ->
                ColorSwatch(color.toCompose(), selected = color == selected, onClick = { onSelect(color) }, size = 22.dp)
            }
        }
    }
}

/**
 * Routes a position-pad drag through the transient gesture channel: discrete taps commit
 * normally, drags stream previews and settle as one undo entry. [commit] is the same
 * transform the pad already used; [label] names the resulting undo entry.
 */
@Composable
private fun GesturePositionPad(
    state: DogScAppState,
    point: NormalizedPoint,
    label: String,
    commit: (NormalizedPoint) -> Unit,
    preview: (NormalizedPoint) -> Unit,
) {
    PositionPad(
        point = point,
        onChange = commit,
        onPreview = preview,
        onGestureStart = { state.beginGesture() },
        onGestureEnd = { state.endGesture(label) },
    )
}

@Composable
private fun PositionPad(
    point: NormalizedPoint,
    onChange: (NormalizedPoint) -> Unit,
    onPreview: ((NormalizedPoint) -> Unit)? = null,
    onGestureStart: (() -> Unit)? = null,
    onGestureEnd: (() -> Unit)? = null,
) {
    // When a preview channel is supplied, drags stream through it (one undo entry for the
    // whole drag); taps stay discrete commits. Without it, behavior is unchanged.
    val emit = onPreview ?: onChange
    Box(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DogScColors.ControlWell)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onChange(
                        NormalizedPoint(
                            (offset.x / size.width).toDouble().coerceIn(0.0, 1.0),
                            (offset.y / size.height).toDouble().coerceIn(0.0, 1.0),
                        )
                    )
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onGestureStart?.invoke() },
                    onDragEnd = { onGestureEnd?.invoke() },
                    onDragCancel = { onGestureEnd?.invoke() },
                ) { change, _ ->
                    change.consume()
                    emit(
                        NormalizedPoint(
                            (change.position.x / size.width).toDouble().coerceIn(0.0, 1.0),
                            (change.position.y / size.height).toDouble().coerceIn(0.0, 1.0),
                        )
                    )
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            for (i in 1..3) {
                drawLine(DogScColors.SecondaryText.copy(alpha = 0.10f), Offset(w * i / 4, 0f), Offset(w * i / 4, h), 1f)
                drawLine(DogScColors.SecondaryText.copy(alpha = 0.10f), Offset(0f, h * i / 4), Offset(w, h * i / 4), 1f)
            }
            val x = point.x.toFloat() * w
            val y = point.y.toFloat() * h
            drawCircle(Color.White, 11f, Offset(x, y))
            drawCircle(DogScColors.PrimaryText, 8f, Offset(x, y))
            drawCircle(Color.White, 3f, Offset(x, y))
        }
    }
}
