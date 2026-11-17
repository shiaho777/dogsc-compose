package dev.dogsc.app.ui.tour

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.staticCompositionLocalOf
import dev.dogsc.app.ui.components.PillButton
import dev.dogsc.app.ui.components.PillVariant
import dev.dogsc.app.ui.components.Text
import dev.dogsc.app.ui.theme.DogScColors
import dev.dogsc.app.ui.theme.DogScMotion
import dev.dogsc.app.ui.theme.TrackPalette
import kotlin.math.roundToInt

class TourTargets {
    val bounds = mutableStateMapOf<String, Rect>()

    fun update(id: String, rect: Rect) {
        bounds[id] = rect
    }

    fun clear() = bounds.clear()
}

val LocalTourTargets = staticCompositionLocalOf<TourTargets?> { null }

/**
 * Registers a coach-mark target. Uses boundsInRoot so the overlay (which also resolves in the
 * same root space) draws the spotlight exactly on the target — the previous boundsInWindow /
 * localToWindow mix offset the ring by the status bar and made it look like a debug artifact.
 */
fun Modifier.tourTarget(id: String, targets: TourTargets?): Modifier =
    if (targets == null) this else onGloballyPositioned { targets.update(id, it.boundsInRoot()) }

data class TourStep(
    val target: String,
    val title: String,
    val detail: String,
)

fun tourSteps(kind: String): List<TourStep> = when (kind) {
    "library" -> listOf(
        TourStep("library.record", "从这里开始录制", "选择屏幕、声音和摄像头，开始后 DogSC 会退到后台，悬浮控制条负责暂停、标记和停止。"),
        TourStep("library.import", "也可以导入已有视频", "导入的视频同样可以裁切、变速、加运镜和贴图，预览与导出共用一套场景。"),
        TourStep("library.projects", "项目会自动保存", "最近项目会保留在应用内；点开任意项目即可继续编辑，删除的项目可在设置 → 回收站恢复。"),
    )
    "record" -> listOf(
        TourStep("record.screen", "先确认录制范围与帧率", "Android 会要求确认整个屏幕的捕获权限；30/60 FPS 可按需要选择。"),
        TourStep("record.audio", "按需开启声音和摄像头", "系统声音、麦克风和摄像头分别保存为独立轨道，剪辑时可以单独调整。"),
        TourStep("record.start", "准备好后开始录制", "开始后悬浮控制条会显示在屏幕上，并且不会出现在成片里。"),
    )
    "editor" -> listOf(
        TourStep("editor.inspector", "参数面板", "画面、运镜、光标、摄像头和声音的设置都从底部标签进入。"),
        TourStep("editor.timeline", "在时间线上剪辑", "移动播放头、拖动片段边缘；双指捏合可以缩放时间线。"),
        TourStep("editor.export", "完成后导出", "选择范围、分辨率和帧率导出 MP4，也可以只导出 M4A。"),
    )
    else -> emptyList()
}

/**
 * First-use coach marks: a dimmed scrim with a soft spotlight cut around the target, a thin
 * accent ring, and an explanation card anchored next to the target (not a detached bottom
 * sheet). Shown once per surface; replayable from Settings.
 */
@Composable
fun FirstUseTourOverlay(kind: String, targets: TourTargets, onDone: () -> Unit) {
    val steps = remember(kind) { tourSteps(kind) }
    if (steps.isEmpty()) return
    var index by remember(kind) { mutableIntStateOf(0) }
    val step = steps[index.coerceIn(0, steps.lastIndex)]
    // Overlay origin in the SAME root space as tourTarget bounds, so the hole lands exactly.
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val rawHole = targets.bounds[step.target]
    val hole = rawHole?.translate(-overlayOrigin.x, -overlayOrigin.y)
    val density = LocalDensity.current
    val scrim = Color.Black.copy(alpha = 0.66f)
    val ring = TrackPalette.Zoom

    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.boundsInRoot().topLeft },
    ) {
        val overlayW = with(density) { maxWidth.toPx() }
        val overlayH = with(density) { maxHeight.toPx() }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val pad = with(density) { 6.dp.toPx() }
                val radius = with(density) { 14.dp.toPx() }
                if (hole == null) {
                    drawRect(scrim)
                } else {
                    val r = Rect(
                        (hole.left - pad).coerceAtLeast(0f),
                        (hole.top - pad).coerceAtLeast(0f),
                        (hole.right + pad).coerceAtMost(size.width),
                        (hole.bottom + pad).coerceAtMost(size.height),
                    )
                    // Even-odd scrim: full screen minus a rounded spotlight hole.
                    val path = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(0f, 0f, size.width, size.height))
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(r, CornerRadius(radius, radius)),
                        )
                    }
                    drawPath(path, scrim)
                    // Thin accent ring + faint outer glow, theme-aware (no white debug box).
                    drawRoundRect(
                        color = ring.copy(alpha = 0.28f),
                        topLeft = Offset(r.left - 3f, r.top - 3f),
                        size = Size(r.width + 6f, r.height + 6f),
                        cornerRadius = CornerRadius(radius + 3f),
                        style = Stroke(width = with(density) { 5.dp.toPx() }),
                    )
                    drawRoundRect(
                        color = ring,
                        topLeft = r.topLeft,
                        size = r.size,
                        cornerRadius = CornerRadius(radius),
                        style = Stroke(width = with(density) { 1.5.dp.toPx() }),
                    )
                }
            }
        }
        // Explanation card anchored near the spotlight: below it when there is room, else above.
        val cardWidthDp = 320
        val cardWidthPx = with(density) { cardWidthDp.dp.toPx() }
        val marginPx = with(density) { 16.dp.toPx() }
        val gapPx = with(density) { 14.dp.toPx() }
        val estCardH = with(density) { 168.dp.toPx() }
        var cardX = 0f
        var cardY = 0f
        if (hole != null) {
            cardX = (hole.center.x - cardWidthPx / 2)
                .coerceIn(marginPx, (overlayW - cardWidthPx - marginPx).coerceAtLeast(marginPx))
            val below = hole.bottom + gapPx
            cardY = if (below + estCardH < overlayH - marginPx) below
            else (hole.top - gapPx - estCardH).coerceAtLeast(marginPx)
        } else {
            cardX = marginPx
            cardY = overlayH - estCardH - marginPx
        }
        AnimatedContent(
            targetState = index,
            transitionSpec = { fadeIn(tween(DogScMotion.SnappyMs)) togetherWith fadeOut(tween(DogScMotion.SnappyMs)) },
            modifier = Modifier.offset { IntOffset(cardX.roundToInt(), cardY.roundToInt()) },
            label = "tour-step",
        ) {
            Column(
                Modifier
                    .widthIn(max = cardWidthDp.dp)
                    .background(DogScColors.Panel, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                Text(
                    step.title,
                    color = DogScColors.PrimaryText,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    step.detail,
                    color = DogScColors.SecondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Step dots.
                    steps.forEachIndexed { i, _ ->
                        Box(
                            Modifier
                                .padding(end = 5.dp)
                                .height(5.dp)
                                .width(if (i == index) 16.dp else 5.dp)
                                .background(
                                    if (i == index) ring else DogScColors.ControlWell,
                                    RoundedCornerShape(3.dp),
                                ),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    PillButton("跳过", onClick = onDone, variant = PillVariant.Ghost, compact = true)
                    Spacer(Modifier.width(8.dp))
                    PillButton(
                        if (index >= steps.lastIndex) "完成" else "下一步",
                        onClick = { if (index >= steps.lastIndex) onDone() else index++ },
                        variant = PillVariant.Primary,
                        compact = true,
                    )
                }
            }
        }
    }
}

