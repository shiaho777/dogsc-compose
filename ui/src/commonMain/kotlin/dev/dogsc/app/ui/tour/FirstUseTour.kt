package dev.dogsc.app.ui.tour

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.staticCompositionLocalOf
import dev.dogsc.app.ui.components.PillButton
import dev.dogsc.app.ui.components.PillVariant
import dev.dogsc.app.ui.components.SoftCard
import dev.dogsc.app.ui.components.Text
import dev.dogsc.app.ui.theme.DogScColors

class TourTargets {
    val bounds = mutableStateMapOf<String, Rect>()

    fun update(id: String, rect: Rect) {
        bounds[id] = rect
    }

    fun clear() = bounds.clear()
}

val LocalTourTargets = staticCompositionLocalOf<TourTargets?> { null }

fun Modifier.tourTarget(id: String, targets: TourTargets?): Modifier =
    if (targets == null) this else onGloballyPositioned { targets.update(id, it.boundsInWindow()) }

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

@Composable
fun FirstUseTourOverlay(kind: String, targets: TourTargets, onDone: () -> Unit) {
    val steps = remember(kind) { tourSteps(kind) }
    if (steps.isEmpty()) return
    var index by remember(kind) { mutableIntStateOf(0) }
    val step = steps[index.coerceIn(0, steps.lastIndex)]
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val rawHole = targets.bounds[step.target]
    val hole = rawHole?.translate(-overlayOrigin.x, -overlayOrigin.y)
    val density = LocalDensity.current
    val scrim = Color.Black.copy(alpha = 0.62f)

    Box(Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
        overlayOrigin = coordinates.localToWindow(Offset.Zero)
    }) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val pad = with(density) { 8.dp.toPx() }
                if (hole == null) {
                    drawRect(scrim)
                } else {
                    val r = Rect(
                        hole.left - pad,
                        hole.top - pad,
                        hole.right + pad,
                        hole.bottom + pad,
                    )
                    drawRect(scrim, Offset.Zero, Size(size.width, r.top.coerceAtLeast(0f)))
                    drawRect(
                        scrim,
                        Offset(0f, r.bottom.coerceAtMost(size.height)),
                        Size(size.width, (size.height - r.bottom).coerceAtLeast(0f)),
                    )
                    drawRect(
                        scrim,
                        Offset(0f, r.top.coerceAtLeast(0f)),
                        Size(r.left.coerceAtLeast(0f), (r.bottom - r.top).coerceAtLeast(0f)),
                    )
                    drawRect(
                        scrim,
                        Offset(r.right.coerceAtMost(size.width), r.top.coerceAtLeast(0f)),
                        Size((size.width - r.right).coerceAtLeast(0f), (r.bottom - r.top).coerceAtLeast(0f)),
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.9f),
                        topLeft = Offset(r.left.coerceAtLeast(0f), r.top.coerceAtLeast(0f)),
                        size = Size(
                            (r.right - r.left).coerceAtMost(size.width),
                            (r.bottom - r.top).coerceAtMost(size.height),
                        ),
                        cornerRadius = CornerRadius(with(density) { 12.dp.toPx() }),
                        style = Stroke(width = with(density) { 2.dp.toPx() }),
                    )
                }
            }
        }
        SoftCard(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
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
                    Text(
                        "${index + 1} / ${steps.size}",
                        color = DogScColors.TertiaryText,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.width(10.dp))
                    PillButton(
                        "跳过",
                        onClick = onDone,
                        variant = PillVariant.Ghost,
                        compact = true,
                    )
                    Spacer(Modifier.weight(1f))
                    PillButton(
                        if (index >= steps.lastIndex) "完成" else "下一步",
                        onClick = {
                            if (index >= steps.lastIndex) onDone() else index++
                        },
                        variant = PillVariant.Primary,
                        compact = true,
                    )
                }
            }
        }
    }
}
