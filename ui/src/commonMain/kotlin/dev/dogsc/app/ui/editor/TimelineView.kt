package dev.dogsc.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import dev.dogsc.app.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dogsc.app.platform.DogScHost
import dev.dogsc.app.ui.DogScAppState
import dev.dogsc.app.ui.EditorSelection
import dev.dogsc.app.ui.components.PillButton
import dev.dogsc.app.ui.components.PillVariant
import dev.dogsc.app.ui.components.SoftIconButton
import dev.dogsc.app.ui.formatTimecode
import dev.dogsc.app.ui.formatScale
import dev.dogsc.app.ui.theme.DogScColors
import dev.dogsc.core.editor.TimelineEditor
import dev.dogsc.core.model.CameraLayoutMode
import dev.dogsc.core.model.CameraMotionState
import dev.dogsc.core.model.MosaicEffectStyle
import dev.dogsc.core.model.NormalizedOverlayRect
import dev.dogsc.core.model.NormalizedPoint
import dev.dogsc.core.model.OverlayTiming
import dev.dogsc.core.model.ScreenMotionState
import dev.dogsc.core.model.SourceSequence
import dev.dogsc.core.model.ZoomEasingPreset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val RulerHeight = 24.dp
private val PrimaryHeight = 46.dp
private val ClipHeight = 20.dp
private val TrackGap = 4.dp
private val HeaderWidth = 52.dp

private sealed class TimelineHit {
    data class Ruler(val time: Double) : TimelineHit()
    data class Primary(val segmentId: String, val edge: Boolean, val leading: Boolean) : TimelineHit()
    data class Zoom(val id: String, val edge: Boolean, val leading: Boolean) : TimelineHit()
    data class Screen(val id: String, val edge: Boolean, val leading: Boolean) : TimelineHit()
    data class Camera(val id: String, val edge: Boolean, val leading: Boolean) : TimelineHit()
    data class Mosaic(val id: String, val edge: Boolean, val leading: Boolean) : TimelineHit()
    data class Sticker(val id: String, val edge: Boolean, val leading: Boolean) : TimelineHit()
    data object Empty : TimelineHit()
}

@Composable
fun TimelinePanel(
    state: DogScAppState,
    host: DogScHost,
    modifier: Modifier = Modifier,
) {
    val project = state.project ?: return
    val textMeasurer = rememberTextMeasurer()
    val scroll = rememberScrollState()
    var showAddMenu by remember { mutableStateOf(false) }
    var showRestoreMenu by remember { mutableStateOf(false) }

    Column(modifier.background(DogScColors.Panel)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "时间线",
                color = DogScColors.PrimaryText,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${project.timeline.zoomClips.size + project.timeline.screenMotionClips.size + project.timeline.cameraMotionClips.size + project.timeline.mosaicClips.size + project.timeline.stickerClips.size} 个效果片段",
                color = DogScColors.TertiaryText,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.weight(1f))
            SoftIconButton(
                Icons.Outlined.GraphicEq,
                onClick = { state.toggleWaveform() },
                contentDescription = if (state.showWaveform) "隐藏波形" else "显示波形",
                size = 28.dp,
                iconSize = 15.dp,
                selected = state.showWaveform,
            )
            Box {
                SoftIconButton(Icons.Outlined.Add, onClick = { showAddMenu = true }, contentDescription = "添加效果", size = 28.dp, iconSize = 15.dp)
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("缩放动画") },
                        leadingIcon = { Icon(Icons.Outlined.ZoomIn, null) },
                        onClick = {
                            showAddMenu = false
                            addZoomAtPlayhead(state)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("屏幕 3D") },
                        leadingIcon = { Icon(Icons.Outlined.ViewInAr, null) },
                        onClick = {
                            showAddMenu = false
                            addScreenMotionAtPlayhead(state)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("摄像运动") },
                        leadingIcon = { Icon(Icons.Outlined.CameraAlt, null) },
                        enabled = project.media?.camera != null,
                        onClick = {
                            showAddMenu = false
                            addCameraMotionAtPlayhead(state)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("打码 / 突出") },
                        leadingIcon = { Icon(Icons.Outlined.CenterFocusStrong, null) },
                        onClick = {
                            showAddMenu = false
                            addMosaicAtPlayhead(state)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("贴图") },
                        leadingIcon = { Icon(Icons.Outlined.Image, null) },
                        onClick = {
                            showAddMenu = false
                            host.pickImage { uri ->
                                if (uri != null) {
                                    state.importSticker(uri)
                                }
                            }
                        },
                    )
                }
            }
            SoftIconButton(Icons.Outlined.Remove, onClick = {
                state.timelinePixelsPerSecond = (state.timelinePixelsPerSecond / 1.35f).coerceIn(4f, 400f)
            }, contentDescription = "缩小时间线", size = 28.dp, iconSize = 15.dp)
            SoftIconButton(Icons.Outlined.Add, onClick = {
                state.timelinePixelsPerSecond = (state.timelinePixelsPerSecond * 1.35f).coerceIn(4f, 400f)
            }, contentDescription = "放大时间线", size = 28.dp, iconSize = 15.dp)
            SoftIconButton(
                Icons.Outlined.AutoAwesome,
                onClick = {
                    val viewport = 320f
                    state.timelinePixelsPerSecond = (viewport / max(state.outputDuration, 1.0)).toFloat().coerceIn(4f, 400f)
                },
                contentDescription = "适应完整时间线",
                size = 28.dp,
                iconSize = 15.dp,
            )
            Box {
                SoftIconButton(
                    Icons.Outlined.Restore,
                    onClick = { showRestoreMenu = true },
                    contentDescription = "恢复剪辑",
                    size = 28.dp,
                    iconSize = 15.dp,
                )
                DropdownMenu(expanded = showRestoreMenu, onDismissRequest = { showRestoreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("还原播放头附近的剪切缺口") },
                        onClick = {
                            showRestoreMenu = false
                            state.restoreCutAtPlayhead()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("还原全部剪辑") },
                        onClick = {
                            showRestoreMenu = false
                            state.restoreAllCuts()
                        },
                    )
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val viewportWidth = maxWidth - HeaderWidth
            val contentWidth = maxOf(viewportWidth, (state.outputDuration * state.timelinePixelsPerSecond).dp)
            LaunchedEffect(state.outputDuration) {
                if (state.timelinePixelsPerSecond <= 4.1f && state.outputDuration > 0) {
                    state.timelinePixelsPerSecond = (viewportWidth.value / state.outputDuration.toFloat()).coerceIn(4f, 400f)
                }
            }
            Row(Modifier.fillMaxSize()) {
                TrackHeaders(state = state)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(scroll),
                ) {
                    TimelineCanvas(
                        state = state,
                        textMeasurer = textMeasurer,
                        contentWidth = contentWidth,
                        modifier = Modifier
                            .width(contentWidth)
                            .fillMaxHeight(),
                    )
                    // Drawn separately so the 60 Hz playhead does not redraw filmstrip and clips.
                    PlayheadOverlay(
                        state = state,
                        modifier = Modifier
                            .width(contentWidth)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackHeaders(state: DogScAppState) {
    val project = state.project ?: return
    Column(
        modifier = Modifier
            .width(HeaderWidth)
            .fillMaxHeight()
            .background(DogScColors.Panel)
            .padding(top = RulerHeight),
    ) {
        TrackHeader("屏幕", null, PrimaryHeight)
        TrackHeader("缩放", Icons.Outlined.ZoomIn, ClipHeight + TrackGap, visible = "zoom" !in state.hiddenTracks) {
            state.hiddenTracks = state.hiddenTracks.toggle("zoom")
        }
        TrackHeader("屏幕 3D", Icons.Outlined.ViewInAr, ClipHeight + TrackGap, visible = "screen" !in state.hiddenTracks) {
            state.hiddenTracks = state.hiddenTracks.toggle("screen")
        }
        if (project.media?.camera != null) {
            TrackHeader("摄像头", Icons.Outlined.CameraAlt, ClipHeight + TrackGap, visible = "camera" !in state.hiddenTracks) {
                state.hiddenTracks = state.hiddenTracks.toggle("camera")
            }
        }
        TrackHeader("打码", Icons.Outlined.CenterFocusStrong, ClipHeight + TrackGap, visible = "mosaic" !in state.hiddenTracks) {
            state.hiddenTracks = state.hiddenTracks.toggle("mosaic")
        }
        TrackHeader("贴图", Icons.Outlined.StickyNote2, ClipHeight + TrackGap, visible = "sticker" !in state.hiddenTracks) {
            state.hiddenTracks = state.hiddenTracks.toggle("sticker")
        }
    }
}

@Composable
private fun TrackHeader(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    height: androidx.compose.ui.unit.Dp,
    visible: Boolean = true,
    onToggle: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clickable(enabled = onToggle != null) { onToggle?.invoke() }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (visible) DogScColors.SecondaryText else DogScColors.TertiaryText.copy(alpha = 0.45f),
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(3.dp))
        }
        Text(
            label,
            color = if (visible) DogScColors.SecondaryText else DogScColors.TertiaryText.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (contains(value)) this - value else this + value

@Composable
private fun TimelineCanvas(
    state: DogScAppState,
    textMeasurer: TextMeasurer,
    contentWidth: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val project = state.project ?: return
    var dragHit by remember { mutableStateOf<TimelineHit?>(null) }
    // onDragStart reports the position after touch slop, which can sit outside a clip edge;
    // hit-testing uses the original press so edge trims stay reachable.
    var downPosition by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var originalStart by remember { mutableStateOf(0.0) }
    var originalEnd by remember { mutableStateOf(0.0) }
    var lastX by remember { mutableStateOf(0f) }
    var dragStartX by remember { mutableStateOf(0f) }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    var lastDistance = 0f
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val distance = (pressed[0].position - pressed[1].position).getDistance()
                            if (lastDistance > 0f && distance > 0f) {
                                val factor = pinchScaleFactor(lastDistance, distance)
                                state.timelinePixelsPerSecond =
                                    (state.timelinePixelsPerSecond * factor).coerceIn(4f, 400f)
                            }
                            lastDistance = distance
                            event.changes.forEach { it.consume() }
                        } else {
                            lastDistance = 0f
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(project, state.timelinePixelsPerSecond, state.snappingEnabled) {
                val density = this
                detectTapGestures(onPress = { downPosition = it }, onTap = { offset ->
                    val time = (offset.x / size.width * state.outputDuration).coerceIn(0.0, state.outputDuration)
                    val hit = hitTest(state, offset.x, offset.y, size.width, size.height, density)
                    when (hit) {
                        is TimelineHit.Ruler, TimelineHit.Empty -> {
                            state.seekTo(state.nearestSnap(time))
                            state.select(EditorSelection.None)
                        }
                        else -> {
                            state.seekTo(time, updatePreview = false)
                            state.select(hit.toSelection())
                            focusInspector(state, hit)
                        }
                    }
                })
            }
            // Keyed on Unit: every edit changes the project, and re-keying would cancel the drag
            // after the first delta (which is what made clip drags move by a few milliseconds).
            .pointerInput(Unit) {
                val density = this
                detectDragGestures(
                    onDragStart = { offset ->
                        val press = if (downPosition == androidx.compose.ui.geometry.Offset.Zero) offset else downPosition
                        val hit = hitTest(state, press.x, press.y, size.width, size.height, density)
                        dragHit = hit
                        lastX = offset.x
                        dragStartX = offset.x
                        val current = state.project ?: return@detectDragGestures
                        when (hit) {
                            is TimelineHit.Primary -> {
                                val map = TimelineEditor.timelineMap(current, state.sourceDuration)
                                val segment = map.segments.firstOrNull { it.id == hit.segmentId }
                                originalStart = segment?.outputStart ?: 0.0
                                originalEnd = segment?.outputEnd ?: 0.0
                            }
                            is TimelineHit.Zoom -> {
                                val clip = current.timeline.zoomClips.firstOrNull { it.id == hit.id }
                                originalStart = clip?.startTime ?: 0.0
                                originalEnd = clip?.endTime ?: 0.0
                            }
                            is TimelineHit.Screen -> {
                                val clip = current.timeline.screenMotionClips.firstOrNull { it.id == hit.id }
                                originalStart = clip?.timing?.startTime ?: 0.0
                                originalEnd = clip?.timing?.endTime ?: 0.0
                            }
                            is TimelineHit.Camera -> {
                                val clip = current.timeline.cameraMotionClips.firstOrNull { it.id == hit.id }
                                originalStart = clip?.timing?.startTime ?: 0.0
                                originalEnd = clip?.timing?.endTime ?: 0.0
                            }
                            is TimelineHit.Mosaic -> {
                                val clip = current.timeline.mosaicClips.firstOrNull { it.id == hit.id }
                                originalStart = clip?.timing?.startTime ?: 0.0
                                originalEnd = clip?.timing?.endTime ?: 0.0
                            }
                            is TimelineHit.Sticker -> {
                                val clip = current.timeline.stickerClips.firstOrNull { it.id == hit.id }
                                originalStart = clip?.timing?.startTime ?: 0.0
                                originalEnd = clip?.timing?.endTime ?: 0.0
                            }
                            else -> Unit
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val duration = state.outputDuration
                        lastX += dragAmount.x
                        // Clips move with the total pointer travel, not one event's delta.
                        val totalDelta = ((lastX - dragStartX) / size.width * duration)
                        when (val hit = dragHit) {
                            is TimelineHit.Ruler, TimelineHit.Empty -> {
                                state.seekTo((lastX / size.width * duration).coerceIn(0.0, duration))
                            }
                            is TimelineHit.Primary -> {
                                if (hit.edge) {
                                    // Follow the finger exactly: derive the edge's target time from the
                                    // pointer instead of accumulating deltas (which skip the slop).
                                    val current = state.project
                                    if (current != null) {
                                        val map = TimelineEditor.timelineMap(current, state.sourceDuration)
                                        val segment = map.segments.firstOrNull { it.id == hit.segmentId }
                                        if (segment != null) {
                                            val target = (change.position.x / size.width * duration).coerceIn(0.0, duration)
                                            val edgeTime = if (hit.leading) segment.outputStart else segment.outputEnd
                                            state.trimPrimary(hit.segmentId, hit.leading, target - edgeTime)
                                        }
                                    }
                                }
                            }
                            is TimelineHit.Zoom -> {
                                val clip = state.project?.timeline?.zoomClips?.firstOrNull { it.id == hit.id }
                                    ?: return@detectDragGestures
                                val movedStart = when {
                                    !hit.edge -> (originalStart + totalDelta)
                                        .coerceIn(0.0, duration - (originalEnd - originalStart))
                                    hit.leading -> (originalStart + totalDelta).coerceIn(0.0, originalEnd - 0.08)
                                    else -> originalStart
                                }
                                val movedEnd = when {
                                    !hit.edge -> movedStart + (originalEnd - originalStart)
                                    hit.leading -> originalEnd
                                    else -> (originalEnd + totalDelta).coerceIn(originalStart + 0.08, duration)
                                }
                                state.updateProject("移动缩放片段") {
                                    TimelineEditor.updateZoom(it, hit.id) { c ->
                                        c.copy(startTime = movedStart, endTime = movedEnd)
                                    }
                                }
                            }
                            is TimelineHit.Screen -> updateTiming(state, hit.id, totalDelta, originalStart, originalEnd, duration, ScreenTrack)
                            is TimelineHit.Camera -> updateTiming(state, hit.id, totalDelta, originalStart, originalEnd, duration, CameraTrack)
                            is TimelineHit.Mosaic -> updateTiming(state, hit.id, totalDelta, originalStart, originalEnd, duration, MosaicTrack)
                            is TimelineHit.Sticker -> updateTiming(state, hit.id, totalDelta, originalStart, originalEnd, duration, StickerTrack)
                            else -> Unit
                        }
                    },
                    onDragEnd = {
                        val hit = dragHit
                        if (hit is TimelineHit.Primary && !hit.edge) {
                            val duration = state.outputDuration
                            val targetTime = (lastX / size.width * duration).coerceIn(0.0, duration)
                            val current = state.project
                            if (current != null) {
                                val map = TimelineEditor.timelineMap(current, state.sourceDuration)
                                val targetIndex = map.segments.indexOfLast { it.outputStart <= targetTime }
                                    .coerceAtLeast(0)
                                state.movePrimarySegment(hit.segmentId, targetIndex)
                            }
                        }
                        dragHit = null
                        downPosition = androidx.compose.ui.geometry.Offset.Zero
                    },
                    onDragCancel = {
                        dragHit = null
                        downPosition = androidx.compose.ui.geometry.Offset.Zero
                    },
                )
            },
    ) {
        val width = size.width
        val height = size.height
        val duration = max(state.outputDuration, 0.001)
        val pps = width / duration
        val primaryTop = RulerHeight.toPx()
        val primaryBottom = primaryTop + PrimaryHeight.toPx()
        var y = primaryBottom + TrackGap.toPx()
        val zoomTop = y
        val zoomBottom = zoomTop + ClipHeight.toPx()
        y = zoomBottom + TrackGap.toPx()
        val screenTop = y
        val screenBottom = screenTop + ClipHeight.toPx()
        y = screenBottom + TrackGap.toPx()
        val cameraTop = y
        val cameraBottom = cameraTop + ClipHeight.toPx()
        if (project.media?.camera != null) y = cameraBottom + TrackGap.toPx()
        val mosaicTop = y
        val mosaicBottom = mosaicTop + ClipHeight.toPx()
        y = mosaicBottom + TrackGap.toPx()
        val stickerTop = y
        val stickerBottom = stickerTop + ClipHeight.toPx()

        // Ruler.
        drawRect(DogScColors.Panel, Offset.Zero, Size(width, RulerHeight.toPx()))
        val secondStep = when {
            pps < 12 -> 30
            pps < 30 -> 10
            pps < 80 -> 5
            else -> 1
        }
        var second = 0
        while (second <= duration.toInt() + 1) {
            val x = second * pps
            val major = second % secondStep == 0
            drawLine(
                if (major) DogScColors.SecondaryText.copy(alpha = 0.45f) else DogScColors.SecondaryText.copy(alpha = 0.20f),
                Offset(x.toFloat(), if (major) RulerHeight.toPx() * 0.45f else RulerHeight.toPx() * 0.65f),
                Offset(x.toFloat(), RulerHeight.toPx()),
                1f,
            )
            if (major) {
                val layout = textMeasurer.measure(
                    formatTimecode(second.toDouble(), showHundredths = false),
                    style = TextStyle(color = DogScColors.TertiaryText, fontSize = 8.sp),
                    maxLines = 1,
                )
                drawText(layout, topLeft = Offset(x.toFloat() + 3f, 2f))
            }
            second += 1
        }
        // Bottom border.
        drawLine(DogScColors.Hairline, Offset(0f, RulerHeight.toPx()), Offset(width, RulerHeight.toPx()), 1f)

        // Primary track.
        drawRoundRect(
            DogScColors.ControlWell,
            Offset(0f, primaryTop + 3f),
            Size(width, (primaryBottom - primaryTop) - 6f),
            CornerRadius(8f),
        )
        val segments = TimelineEditor.segments(project, state.sourceDuration)
        var outputStart = 0.0
        for ((index, segment) in segments.withIndex()) {
            val segmentStart = outputStart
            val segmentEnd = outputStart + segment.outputDuration
            val left = (segmentStart * pps).toFloat()
            val right = (segmentEnd * pps).toFloat()
            val selected = (state.selection as? EditorSelection.Primary)?.segmentId == segment.id
            val top = primaryTop + 3f
            val bottom = primaryBottom - 3f
            clipRect(left, top, right, bottom) {
                drawRoundRect(
                    DogScColors.ScreenClip,
                    Offset(left, top),
                    Size(max(right - left, 2f), bottom - top),
                    CornerRadius(7f),
                )
                val filmstrip = state.filmstripFrames
                val fallback = state.previewFrame
                if (filmstrip.isNotEmpty() || fallback != null) {
                    var x = left
                    while (x < right) {
                        val tileWidth = (bottom - top) * 16f / 9f
                        val tileTime = segmentStart + ((x - left) / max(right - left, 1f)) * (segmentEnd - segmentStart)
                        val sourceTime = TimelineEditor.timelineMap(project, state.sourceDuration)
                            .sourceTimeAtOutput(tileTime) ?: tileTime
                        val sourceFraction = (sourceTime / max(state.sourceDuration, 0.01)).coerceIn(0.0, 0.999)
                        val image = if (filmstrip.isNotEmpty()) {
                            filmstrip[(sourceFraction * filmstrip.size).toInt().coerceIn(0, filmstrip.lastIndex)]
                        } else {
                            fallback
                        }
                        if (image != null) {
                            drawImage(
                                image = image,
                                dstOffset = androidx.compose.ui.unit.IntOffset(x.toInt(), top.toInt()),
                                dstSize = androidx.compose.ui.unit.IntSize(
                                    max(tileWidth.toInt(), 1),
                                    max((bottom - top).toInt(), 1),
                                ),
                                alpha = 0.9f,
                            )
                        }
                        x += tileWidth
                    }
                }
                if (index > 0) {
                    drawLine(Color.White.copy(alpha = 0.85f), Offset(left, top), Offset(left, bottom), 2f)
                }
            }
            if (selected) {
                drawRoundRect(
                    DogScColors.PrimaryText,
                    Offset(left, top),
                    Size(max(right - left, 2f), bottom - top),
                    CornerRadius(7f),
                    style = Stroke(1.5f),
                )
            }
            outputStart = segmentEnd
        }

        if (state.showWaveform) {
            val wave = state.waveform
            if (wave != null && wave.isNotEmpty()) {
                val trackTop = primaryTop + 3f
                val trackBottom = primaryBottom - 3f
                val centerY = (trackTop + trackBottom) / 2
                val barWidth = max(1.5f, width / wave.size)
                for (i in wave.indices) {
                    val x = (i.toFloat() / wave.size) * width
                    val amplitude = (wave[i].coerceIn(0f, 1f)) * (trackBottom - trackTop) * 0.42f
                    drawLine(
                        DogScColors.MintInk.copy(alpha = 0.72f),
                        Offset(x, centerY - amplitude),
                        Offset(x, centerY + amplitude),
                        barWidth,
                    )
                }
            }
        }

        // Effect tracks.
        if ("zoom" !in state.hiddenTracks) {
            for (clip in project.timeline.zoomClips) {
                val selected = (state.selection as? EditorSelection.Zoom)?.clipId == clip.id
                drawClip(
                    textMeasurer,
                    left = (clip.startTime * pps).toFloat(),
                    right = (clip.endTime * pps).toFloat(),
                    top = zoomTop,
                    bottom = zoomBottom,
                    color = DogScColors.ZoomClip,
                    ink = DogScColors.ZoomInk,
                    label = dev.dogsc.app.ui.Loc.t("缩放 ${formatScale(clip.scale)} · ${if (clip.origin == dev.dogsc.core.model.ZoomKeyframeOrigin.AUTOMATIC) "自动" else "手动"}"),
                    selected = selected,
                )
            }
        }
        if ("screen" !in state.hiddenTracks) {
            for (clip in project.timeline.screenMotionClips) {
                val selected = (state.selection as? EditorSelection.ScreenMotion)?.clipId == clip.id
                drawClip(
                    textMeasurer,
                    left = (clip.timing.startTime * pps).toFloat(),
                    right = (clip.timing.endTime * pps).toFloat(),
                    top = screenTop,
                    bottom = screenBottom,
                    color = DogScColors.ScreenClip,
                    ink = DogScColors.SecondaryText,
                    label = dev.dogsc.app.ui.Loc.t("屏幕 3D"),
                    selected = selected,
                )
            }
        }
        if (project.media?.camera != null && "camera" !in state.hiddenTracks) {
            for (clip in project.timeline.cameraMotionClips) {
                val selected = (state.selection as? EditorSelection.CameraMotion)?.clipId == clip.id
                drawClip(
                    textMeasurer,
                    left = (clip.timing.startTime * pps).toFloat(),
                    right = (clip.timing.endTime * pps).toFloat(),
                    top = cameraTop,
                    bottom = cameraBottom,
                    color = DogScColors.CameraClip,
                    ink = DogScColors.CameraInk,
                    label = dev.dogsc.app.ui.Loc.t("摄像运动"),
                    selected = selected,
                )
            }
        }
        if ("mosaic" !in state.hiddenTracks) {
            for (clip in project.timeline.mosaicClips) {
                val selected = (state.selection as? EditorSelection.Mosaic)?.clipId == clip.id
                drawClip(
                    textMeasurer,
                    left = (clip.timing.startTime * pps).toFloat(),
                    right = (clip.timing.endTime * pps).toFloat(),
                    top = mosaicTop,
                    bottom = mosaicBottom,
                    color = DogScColors.MosaicClip,
                    ink = DogScColors.MosaicInk,
                    label = dev.dogsc.app.ui.Loc.t(if (clip.style == MosaicEffectStyle.SPOTLIGHT) "突出" else "打码"),
                    selected = selected,
                )
            }
        }
        if ("sticker" !in state.hiddenTracks) {
            for (clip in project.timeline.stickerClips) {
                val selected = (state.selection as? EditorSelection.Sticker)?.clipId == clip.id
                drawClip(
                    textMeasurer,
                    left = (clip.timing.startTime * pps).toFloat(),
                    right = (clip.timing.endTime * pps).toFloat(),
                    top = stickerTop,
                    bottom = stickerBottom,
                    color = DogScColors.StickerClip,
                    ink = DogScColors.StickerInk,
                    label = dev.dogsc.app.ui.Loc.t("贴图"),
                    selected = selected,
                )
            }
        }

        // Markers.
        for (marker in project.recordingMarkers) {
            val time = TimelineEditor.timelineMap(project, state.sourceDuration)
                .outputTimeForSource(marker.sourceTime) ?: continue
            val x = (time * pps).toFloat()
            drawLine(DogScColors.Recording, Offset(x, RulerHeight.toPx() * 0.15f), Offset(x, height), 1.2f)
            drawCircle(DogScColors.Recording, 3.2f, Offset(x, RulerHeight.toPx() * 0.25f))
        }

    }
}

@Composable
private fun PlayheadOverlay(state: DogScAppState, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val duration = max(state.outputDuration, 0.001)
        val playheadX = (state.playhead / duration * size.width).toFloat()
        drawLine(DogScColors.Playhead, Offset(playheadX, 0f), Offset(playheadX, size.height), 1.6f)
        val handle = Path().apply {
            moveTo(playheadX - 5f, 0f)
            lineTo(playheadX + 5f, 0f)
            lineTo(playheadX + 5f, 9f)
            lineTo(playheadX, 14f)
            lineTo(playheadX - 5f, 9f)
            close()
        }
        drawPath(handle, DogScColors.Playhead)
    }
}

private enum class TrackKind { Screen, Camera, Mosaic, Sticker }
private val ScreenTrack = TrackKind.Screen
private val CameraTrack = TrackKind.Camera
private val MosaicTrack = TrackKind.Mosaic
private val StickerTrack = TrackKind.Sticker

private fun updateTiming(
    state: DogScAppState,
    id: String,
    delta: Double,
    originalStart: Double,
    originalEnd: Double,
    duration: Double,
    kind: TrackKind,
) {
    val length = originalEnd - originalStart
    val start = (originalStart + delta).coerceIn(0.0, max(duration - length, 0.0))
    val end = start + length
    state.updateProject("移动动画片段") { project ->
        when (kind) {
            TrackKind.Screen -> project.copy(timeline = project.timeline.copy(
                screenMotionClips = project.timeline.screenMotionClips.map {
                    if (it.id == id) it.copy(timing = it.timing.copy(startTime = start, duration = end - start)) else it
                },
            ))
            TrackKind.Camera -> project.copy(timeline = project.timeline.copy(
                cameraMotionClips = project.timeline.cameraMotionClips.map {
                    if (it.id == id) it.copy(timing = it.timing.copy(startTime = start, duration = end - start)) else it
                },
            ))
            TrackKind.Mosaic -> project.copy(timeline = project.timeline.copy(
                mosaicClips = project.timeline.mosaicClips.map {
                    if (it.id == id) it.copy(timing = OverlayTiming(start, end - start)) else it
                },
            ))
            TrackKind.Sticker -> project.copy(timeline = project.timeline.copy(
                stickerClips = project.timeline.stickerClips.map {
                    if (it.id == id) it.copy(timing = OverlayTiming(start, end - start)) else it
                },
            ))
        }
    }
}

private fun TimelineHit.toSelection(): EditorSelection = when (this) {
    is TimelineHit.Primary -> EditorSelection.Primary(segmentId)
    is TimelineHit.Zoom -> EditorSelection.Zoom(id)
    is TimelineHit.Screen -> EditorSelection.ScreenMotion(id)
    is TimelineHit.Camera -> EditorSelection.CameraMotion(id)
    is TimelineHit.Mosaic -> EditorSelection.Mosaic(id)
    is TimelineHit.Sticker -> EditorSelection.Sticker(id)
    else -> EditorSelection.None
}

private fun focusInspector(state: DogScAppState, hit: TimelineHit) {
    when (hit) {
        is TimelineHit.Zoom -> state.inspectorTab = dev.dogsc.app.ui.InspectorTab.MOTION
        is TimelineHit.Screen -> state.inspectorTab = dev.dogsc.app.ui.InspectorTab.MOTION
        is TimelineHit.Camera -> state.inspectorTab = dev.dogsc.app.ui.InspectorTab.CAMERA
        is TimelineHit.Mosaic -> state.inspectorTab = dev.dogsc.app.ui.InspectorTab.CANVAS
        is TimelineHit.Sticker -> state.inspectorTab = dev.dogsc.app.ui.InspectorTab.CANVAS
        else -> Unit
    }
}

private fun hitTest(
    state: DogScAppState,
    x: Float,
    y: Float,
    width: Int,
    height: Int,
    density: androidx.compose.ui.unit.Density,
): TimelineHit {
    val project = state.project ?: return TimelineHit.Empty
    val duration = max(state.outputDuration, 0.001)
    val pps = width / duration
    val time = (x / width * duration).coerceIn(0.0, duration)
    val ruler = with(density) { RulerHeight.toPx() }
    val primary = with(density) { PrimaryHeight.toPx() }
    val clip = with(density) { ClipHeight.toPx() }
    val gap = with(density) { TrackGap.toPx() }
    val edge = with(density) { 8.dp.toPx() }
    if (y < ruler) return TimelineHit.Ruler(time)
    val primaryTop = ruler
    val primaryBottom = primaryTop + primary
    if (y in primaryTop..primaryBottom) {
        var outputStart = 0.0
        for (segment in TimelineEditor.segments(project, state.sourceDuration)) {
            val end = outputStart + segment.outputDuration
            if (time in outputStart..end) {
                val startX = (outputStart * pps).toFloat()
                val endX = (end * pps).toFloat()
                return TimelineHit.Primary(
                    segment.id,
                    edge = abs(x - startX) <= edge || abs(x - endX) <= edge,
                    leading = abs(x - startX) <= edge,
                )
            }
            outputStart = end
        }
        return TimelineHit.Empty
    }
    var top = primaryBottom + gap
    fun trackHit(clips: List<Pair<String, Pair<Double, Double>>>, make: (String, Boolean, Boolean) -> TimelineHit): TimelineHit? {
        val bottom = top + clip
        if (y in top..bottom) {
            for ((id, range) in clips) {
                val startX = (range.first * pps).toFloat()
                val endX = (range.second * pps).toFloat()
                if (time in range.first..range.second) {
                    return make(
                        id,
                        abs(x - startX) <= edge || abs(x - endX) <= edge,
                        abs(x - startX) <= edge,
                    )
                }
            }
            return TimelineHit.Empty
        }
        top = bottom + gap
        return null
    }
    trackHit(project.timeline.zoomClips.map { it.id to (it.startTime to it.endTime) }) { id, e, l -> TimelineHit.Zoom(id, e, l) }?.let { return it }
    trackHit(project.timeline.screenMotionClips.map { it.id to (it.timing.startTime to it.timing.endTime) }) { id, e, l -> TimelineHit.Screen(id, e, l) }?.let { return it }
    if (project.media?.camera != null) {
        trackHit(project.timeline.cameraMotionClips.map { it.id to (it.timing.startTime to it.timing.endTime) }) { id, e, l -> TimelineHit.Camera(id, e, l) }?.let { return it }
    }
    trackHit(project.timeline.mosaicClips.map { it.id to (it.timing.startTime to it.timing.endTime) }) { id, e, l -> TimelineHit.Mosaic(id, e, l) }?.let { return it }
    trackHit(project.timeline.stickerClips.map { it.id to (it.timing.startTime to it.timing.endTime) }) { id, e, l -> TimelineHit.Sticker(id, e, l) }?.let { return it }
    return TimelineHit.Empty
}


private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawClip(
    textMeasurer: TextMeasurer,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    color: Color,
    ink: Color,
    label: String,
    selected: Boolean,
) {
    val width = max(right - left, 3f)
    drawRoundRect(color, Offset(left, top), Size(width, bottom - top), CornerRadius(5f))
    if (width > 34f) {
        val layout = textMeasurer.measure(
            label,
            style = TextStyle(color = ink, fontSize = 8.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = (width - 10).toInt().coerceAtLeast(1)),
        )
        drawText(layout, topLeft = Offset(left + 5f, top + (bottom - top - layout.size.height) / 2))
    }
    if (selected) {
        drawRoundRect(
            DogScColors.PrimaryText,
            Offset(left, top),
            Size(width, bottom - top),
            CornerRadius(5f),
            style = Stroke(1.5f),
        )
    }
}

private fun addZoomAtPlayhead(state: DogScAppState) {
    val project = state.project ?: return
    val start = state.playhead
    val end = min(start + 1.2, state.outputDuration)
    if (end - start < 0.2) return
    state.updateProject("添加缩放") {
        TimelineEditor.addZoom(
            it,
            start,
            end,
            it.motion.defaultZoomScale ?: 1.4,
            NormalizedPoint.Center,
            it.motion.defaultZoomEasing,
            duration = state.outputDuration,
        )
    }
    state.inspectorTab = dev.dogsc.app.ui.InspectorTab.MOTION
}

private fun addScreenMotionAtPlayhead(state: DogScAppState) {
    val start = state.playhead
    val end = min(start + 2.0, state.outputDuration)
    if (end - start < 0.2) return
    state.updateProject("添加屏幕 3D") {
        TimelineEditor.addScreenMotion(
            it,
            start,
            end - start,
            ScreenMotionState(
                position = NormalizedPoint.Center,
                scale = 1.08,
                rotationX = 7.0,
                rotationY = -14.0,
                perspective = 0.7,
            ),
            ZoomEasingPreset.CUBIC,
            leadIn = 0.7,
            returnDuration = 0.7,
        )
    }
    state.inspectorTab = dev.dogsc.app.ui.InspectorTab.MOTION
}

private fun addCameraMotionAtPlayhead(state: DogScAppState) {
    val project = state.project ?: return
    if (project.media?.camera == null) return
    val start = state.playhead
    val end = min(start + 2.0, state.outputDuration)
    if (end - start < 0.2) return
    state.updateProject("添加摄像运动") {
        TimelineEditor.addCameraMotion(
            it,
            start,
            end - start,
            CameraMotionState(
                layout = CameraLayoutMode.Fullscreen,
                position = NormalizedPoint.Center,
                size = 0.5,
                roundness = 0.5,
                opacity = 1.0,
            ),
            ZoomEasingPreset.CUBIC,
            leadIn = 0.7,
            returnDuration = 0.7,
        )
    }
    state.inspectorTab = dev.dogsc.app.ui.InspectorTab.CAMERA
}

private fun addMosaicAtPlayhead(state: DogScAppState) {
    val start = state.playhead
    val end = min(start + 2.0, state.outputDuration)
    if (end - start < 0.2) return
    state.updateProject("添加打码") {
        TimelineEditor.addMosaic(
            it,
            OverlayTiming(start, end - start),
            NormalizedOverlayRect.Centered,
            MosaicEffectStyle.BLUR,
        )
    }
    state.inspectorTab = dev.dogsc.app.ui.InspectorTab.CANVAS
    state.canvasPane = dev.dogsc.app.ui.CanvasPane.EFFECTS
}
