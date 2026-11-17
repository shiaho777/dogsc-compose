package dev.dogsc.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.dogsc.app.ui.DogScAppState
import dev.dogsc.app.ui.components.PillButton
import dev.dogsc.app.ui.components.PillVariant
import dev.dogsc.app.ui.components.Text
import dev.dogsc.app.ui.theme.DogScColors
import dev.dogsc.app.ui.theme.TrackPalette
import dev.dogsc.core.editor.TimelineEditor
import dev.dogsc.core.model.MediaSyncAnchorCurve

/**
 * Camera sync-anchor curve track (upstream EditorTimelineLayout): visualizes the camera's
 * time-offset curve and lets the user add anchors at the playhead, remove them, and nudge their
 * offset. The curve itself is the same [MediaSyncAnchorCurve] the export plan samples.
 */
@Composable
fun CameraSyncAnchorSection(state: DogScAppState) {
    val project = state.project ?: return
    val camera = project.media?.camera ?: return
    val anchors = camera.syncAnchors.sortedBy { it.sourceTime }
    val duration = (camera.durationSeconds ?: state.sourceDuration).coerceAtLeast(0.001)
    val curve = MediaSyncAnchorCurve(anchors)
    // Offset range for the curve view: symmetric around zero, padded to the authored extremes.
    val maxOffset = (anchors.maxOfOrNull { kotlin.math.abs(it.offset) } ?: 1.0).coerceAtLeast(1.0)

    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("同步锚点曲线", color = DogScColors.PrimaryText, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text("${anchors.size} 个锚点", color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DogScColors.ControlWell)
                .pointerInput(anchors, duration) {
                    detectTapGestures { offset ->
                        // Tap an anchor point to remove it (within touch slack).
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val hit = anchors.firstOrNull { anchor ->
                            val ax = (anchor.sourceTime / duration * w).toFloat()
                            val ay = (h / 2 - (anchor.offset / maxOffset) * (h / 2 - 8f)).toFloat()
                            kotlin.math.abs(offset.x - ax) < 20f && kotlin.math.abs(offset.y - ay) < 20f
                        }
                        if (hit != null) {
                            state.updateProject("删除同步锚点") {
                                TimelineEditor.removeCameraSyncAnchor(it, hit.id)
                            }
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val midY = h / 2
            // Zero line.
            drawLine(DogScColors.Hairline, Offset(0f, midY), Offset(w, midY), 1f)
            // Curve.
            if (anchors.isNotEmpty()) {
                val path = Path()
                val steps = 64
                for (i in 0..steps) {
                    val t = duration * i / steps
                    val off = curve.offsetAtSourceTime(t)
                    val x = (t / duration * w).toFloat()
                    val y = (midY - (off / maxOffset) * (midY - 8f)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, TrackPalette.Camera, style = Stroke(2f))
                // Anchor points.
                for (anchor in anchors) {
                    val ax = (anchor.sourceTime / duration * w).toFloat()
                    val ay = (midY - (anchor.offset / maxOffset) * (midY - 8f)).toFloat()
                    drawCircle(Color.White, 5f, Offset(ax, ay))
                    drawCircle(TrackPalette.Camera, 5f, Offset(ax, ay), style = Stroke(1.5f))
                }
            }
            // Playhead marker.
            val px = (state.playhead / duration * w).toFloat().coerceIn(0f, w)
            drawLine(DogScColors.Playhead, Offset(px, 0f), Offset(px, h), 1.5f)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("在播放头加锚点", onClick = {
                state.updateProject("添加同步锚点") {
                    TimelineEditor.addCameraSyncAnchor(it, state.playhead, 0.0)
                }
            }, icon = Icons.Outlined.Add, compact = true)
        }
        if (anchors.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            anchors.forEach { anchor ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${formatAnchorTime(anchor.sourceTime)} · ${formatOffset(anchor.offset)}",
                        color = DogScColors.SecondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    PillButton("−", onClick = {
                        state.updateProject("调整同步锚点") {
                            TimelineEditor.updateCameraSyncAnchor(it, anchor.id, anchor.offset - 0.1)
                        }
                    }, compact = true, variant = PillVariant.Ghost)
                    PillButton("+", onClick = {
                        state.updateProject("调整同步锚点") {
                            TimelineEditor.updateCameraSyncAnchor(it, anchor.id, anchor.offset + 0.1)
                        }
                    }, compact = true, variant = PillVariant.Ghost)
                    PillButton("删除", onClick = {
                        state.updateProject("删除同步锚点") {
                            TimelineEditor.removeCameraSyncAnchor(it, anchor.id)
                        }
                    }, compact = true, variant = PillVariant.Ghost)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "锚点定义摄像头相对屏幕的时间偏移曲线；点击曲线上的锚点可删除。",
            color = DogScColors.TertiaryText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatAnchorTime(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0)
    val m = (s / 60).toInt()
    val rem = s - m * 60
    return "${m}:${rem.toInt().toString().padStart(2, '0')}"
}

private fun formatOffset(offset: Double): String {
    val sign = if (offset >= 0) "+" else ""
    return "$sign${(offset * 1000).toInt()}ms"
}