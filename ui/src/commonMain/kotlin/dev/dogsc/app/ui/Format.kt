package dev.dogsc.app.ui

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

fun formatTimecode(seconds: Double, showHundredths: Boolean = true): String {
    val safe = if (seconds.isFinite()) maxOf(seconds, 0.0) else 0.0
    val totalHundredths = (safe * 100).roundToInt()
    val hours = totalHundredths / 360_000
    val minutes = (totalHundredths / 6_000) % 60
    val secs = (totalHundredths / 100) % 60
    val hundredths = totalHundredths % 100
    val base = if (hours > 0) {
        "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
    }
    return if (showHundredths) "$base.${hundredths.toString().padStart(2, '0')}" else base
}

fun formatClock(seconds: Double): String {
    val safe = maxOf(seconds, 0.0)
    val totalSeconds = floor(safe).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds / 60) % 60
    val secs = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
    }
}

fun formatDurationShort(seconds: Double): String {
    val safe = maxOf(seconds, 0.0)
    return when {
        safe < 60 -> "${safe.roundToInt()} 秒"
        safe < 3600 -> {
            val minutes = (safe / 60).toInt()
            val secs = (safe % 60).roundToInt()
            if (secs == 0) "$minutes 分钟" else "$minutes 分 $secs 秒"
        }
        else -> {
            val hours = (safe / 3600).toInt()
            val minutes = ((safe % 3600) / 60).roundToInt()
            if (minutes == 0) "$hours 小时" else "$hours 小时 $minutes 分"
        }
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val formatted = if (value >= 100) value.roundToInt().toString()
    else if (value >= 10) ((value * 10).roundToInt() / 10.0).toString()
    else ((value * 100).roundToInt() / 100.0).toString()
    return "$formatted ${units[unit]}"
}

fun formatPercent(value: Double): String = "${(value * 100).roundToInt()}%"

fun formatScale(value: Double): String {
    val rounded = (value * 100).roundToInt() / 100.0
    return if (abs(rounded - rounded.roundToInt()) < 0.001) "${rounded.roundToInt()}×" else "$rounded×"
}

fun formatDate(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val totalSeconds = epochMs / 1000
    val days = totalSeconds / 86_400
    // Days since 1970-01-01 → civil date, avoiding platform date APIs in common code.
    var z = days + 719_468
    val era = if (z >= 0) z / 146_097 else (z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    return "$year-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}
