@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.dogsc.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.dogsc.app.platform.ExportResult
import dev.dogsc.app.ui.DogScAppState
import dev.dogsc.app.ui.components.PillButton
import dev.dogsc.app.ui.components.PillVariant
import dev.dogsc.app.ui.components.SectionLabel
import dev.dogsc.app.ui.components.SegmentedControl
import dev.dogsc.app.ui.components.SoftCard
import dev.dogsc.app.ui.formatFileSize
import dev.dogsc.app.ui.formatTimecode
import dev.dogsc.app.ui.theme.DogScColors
import dev.dogsc.core.model.CanvasResolution
import dev.dogsc.core.model.ExportEstimator
import dev.dogsc.core.model.OutputFrameRate
import kotlin.math.max

@Composable
fun ExportSheet(state: DogScAppState, host: DogScHost, onDismiss: () -> Unit) {
    val project = state.project ?: return
    var audioOnly by remember { mutableStateOf(false) }
    var fileName by remember {
        mutableStateOf(
            project.title.ifBlank { "DogSC 录制" }.replace(Regex("[\\\\/:*?\"<>|]"), "-")
                .take(60) + if (audioOnly) ".m4a" else ".mp4",
        )
    }
    var selectedResolution by remember { mutableStateOf(project.exportSettings.resolution) }
    var selectedFrameRate by remember { mutableStateOf(project.exportSettings.frameRate) }
    var trimStart by remember { mutableStateOf(0f) }
    var trimEnd by remember { mutableStateOf(state.outputDuration.toFloat()) }
    val sourcePixelSize = state.loadedProject?.summary?.let { summary ->
        val w = summary.pixelWidth
        val h = summary.pixelHeight
        if (w != null && h != null && w > 0 && h > 0) dev.dogsc.core.model.CanvasDimensions(w, h) else null
    }
    val estimate = remember(selectedResolution, selectedFrameRate, project, state.outputDuration, sourcePixelSize) {
        ExportEstimator.estimate(
            project,
            state.outputDuration,
            state.sourceAspectRatio,
            resolution = selectedResolution,
            frameRate = selectedFrameRate,
            sourcePixelSize = sourcePixelSize,
        )
    }
    val progress = state.exportProgress
    val lastExport = state.lastExport

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DogScColors.Panel,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("导出作品", color = DogScColors.PrimaryText, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatTimecode(state.outputDuration)} · ${estimate.width}×${estimate.height}",
                color = DogScColors.TertiaryText,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            SegmentedControl(
                options = listOf("视频成片", "仅音频"),
                selectedIndex = if (audioOnly) 1 else 0,
                onSelect = {
                    audioOnly = it == 1
                    fileName = fileName.substringBeforeLast('.') + if (audioOnly) ".m4a" else ".mp4"
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            if (!audioOnly) {
                SectionLabel("分辨率")
                Spacer(Modifier.height(5.dp))
                SegmentedControl(
                    options = CanvasResolution.entries.map { it.displayName },
                    selectedIndex = CanvasResolution.entries.indexOf(selectedResolution),
                    onSelect = { selectedResolution = CanvasResolution.entries[it] },
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
                Spacer(Modifier.height(12.dp))
                SectionLabel("帧率")
                Spacer(Modifier.height(5.dp))
                SegmentedControl(
                    options = OutputFrameRate.entries.map { it.label },
                    selectedIndex = OutputFrameRate.entries.indexOf(selectedFrameRate),
                    onSelect = { selectedFrameRate = OutputFrameRate.entries[it] },
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
                Spacer(Modifier.height(12.dp))
            }
            SectionLabel("文件名")
            Spacer(Modifier.height(5.dp))
            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            SoftCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = DogScColors.Raised) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("格式", color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (audioOnly) "M4A · 剪辑后的混音" else "MP4 · H.264 + AAC",
                            color = DogScColors.PrimaryText,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("预计大小", color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "约 ${formatFileSize(if (audioOnly) (192_000L / 8 * state.outputDuration).toLong() else estimate.estimatedBytes)}",
                            color = DogScColors.PrimaryText,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (progress != null) {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { progress.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = DogScColors.Accent,
                        trackColor = DogScColors.ControlWell,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            progress.message,
                            color = DogScColors.SecondaryText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${(progress.fraction * 100).toInt()}%",
                            color = DogScColors.PrimaryText,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    progress.etaSeconds?.let {
                        Text(
                            "预计剩余约 ${formatTimecode(max(it, 0.0), showHundredths = false)}",
                            color = DogScColors.TertiaryText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    PillButton("取消导出", onClick = { state.cancelExport() }, variant = PillVariant.Ghost, compact = true)
                }
            } else if (lastExport is ExportResult.Success) {
                val result = lastExport as ExportResult.Success
                SoftCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = DogScColors.Mint.copy(alpha = 0.35f)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = DogScColors.MintInk)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("导出完成", color = DogScColors.PrimaryText, style = MaterialTheme.typography.labelMedium)
                            Text(formatFileSize(result.sizeBytes), color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
                        }
                        PillButton("分享", onClick = { host.shareFile(result.outputUri) }, icon = Icons.Outlined.Share, compact = true)
                    }
                }
                Spacer(Modifier.height(10.dp))
                PillButton("重新导出", onClick = { state.clearExportResult() }, variant = PillVariant.Ghost, compact = true)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(
                        "选择保存位置",
                        onClick = {
                            host.createExportFile(fileName, audioOnly) { uri ->
                                if (uri != null) {
                                    state.updateExport("记录导出规格") {
                                        it.copy(
                                            resolution = selectedResolution,
                                            frameRate = selectedFrameRate,
                                        )
                                    }
                                    state.startExport(
                                        uri,
                                        audioOnly,
                                        trimStart.toDouble(),
                                        trimEnd.toDouble(),
                                    )
                                }
                            }
                        },
                        icon = Icons.Outlined.FolderOpen,
                        variant = PillVariant.Primary,
                        enabled = host.platform.supportsExport,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!host.platform.supportsExport) {
                    Spacer(Modifier.height(8.dp))
                    Text("当前平台暂未接入导出编码器。", color = DogScColors.Danger, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
