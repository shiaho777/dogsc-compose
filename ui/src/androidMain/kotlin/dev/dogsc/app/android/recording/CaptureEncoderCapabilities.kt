package dev.dogsc.app.android.recording

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import kotlin.math.max

/** Largest screen-capture size the selected encoder reports, stepped down in 3/4 increments. */
internal object CaptureEncoderCapabilities {
    fun clamp(mime: String, width: Int, height: Int): Pair<Int, Int> {
        val codecInfo = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, true) }
            }
        }.getOrNull() ?: return width to height
        val caps: MediaCodecInfo.VideoCapabilities = runCatching {
            codecInfo.getCapabilitiesForType(mime).videoCapabilities
        }.getOrNull() ?: return width to height
        var w = width
        var h = height
        while (w >= 320 && h >= 180) {
            if (runCatching { caps.isSizeSupported(w, h) }.getOrDefault(false)) return w to h
            w = ((w * 3) / 4) / 2 * 2
            h = ((h * 3) / 4) / 2 * 2
        }
        return width to height
    }

    fun mimeFor(codec: String): String =
        if (codec == "hevc") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
}
