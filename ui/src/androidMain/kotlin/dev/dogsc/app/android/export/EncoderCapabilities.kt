package dev.dogsc.app.android.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/** Largest AVC encoder size this device reports, starting from the requested dimensions. */
object EncoderCapabilities {
    fun clamp(width: Int, height: Int): Pair<Int, Int> {
        val codecInfo = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
            }
        }.getOrNull() ?: return width to height
        val caps: MediaCodecInfo.VideoCapabilities = runCatching {
            codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
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
}
