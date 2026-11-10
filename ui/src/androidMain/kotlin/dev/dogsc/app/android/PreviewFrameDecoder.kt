package dev.dogsc.app.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import dev.dogsc.app.android.export.VideoFrameSource
import java.io.File
import kotlin.math.max

/**
 * Editor preview frames from persistent MediaCodec decoders. MediaMetadataRetriever re-seeks and
 * decodes a keyframe for every request (50–110 ms on a 1080×2400 recording); the sequential
 * decoder plus a small ring of scaled buffers keeps playback and scrubbing responsive.
 *
 * Each source has its own ring: the screen and camera frames are requested back-to-back, so a
 * shared ring would let the camera overwrite the screen bitmap before Compose draws it.
 */
class PreviewFrameDecoder {
    private class Source(file: File) {
        val decoder = VideoFrameSource(file)
        val buffers = arrayOfNulls<Bitmap>(2)
        var index = 0
        var lastScaledTime = Double.NaN
        var lastScaled: Bitmap? = null
    }

    private val sources = HashMap<String, Source>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dst = Rect()

    @Synchronized
    fun frame(file: File, time: Double, maxWidth: Int = 720): Bitmap? {
        // Keep at most two decoders alive (screen + camera); each holds full-size buffers.
        if (!sources.containsKey(file.absolutePath) && sources.size >= 2) {
            val stale = sources.keys.first()
            sources.remove(stale)?.let { runCatching { it.decoder.close() } }
        }
        val source = sources.getOrPut(file.absolutePath) { Source(file) }
        val full = runCatching { source.decoder.frameAt(time) }.getOrNull() ?: return null
        if (full.width <= 0 || full.height <= 0) return null
        val frameTime = source.decoder.lastDecodedTime
        if (frameTime == source.lastScaledTime) {
            source.lastScaled?.takeIf { !it.isRecycled }?.let { return it }
        }
        val scale = maxWidth.toFloat() / full.width
        val width = max(2, (full.width * scale).toInt())
        val height = max(2, (full.height * scale).toInt())
        val slot = source.index % source.buffers.size
        val target = source.buffers[slot]?.takeIf { it.width == width && it.height == height && !it.isRecycled }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { source.buffers[slot] = it }
        dst.set(0, 0, width, height)
        Canvas(target).drawBitmap(full, null, dst, paint)
        source.index++
        source.lastScaledTime = frameTime
        source.lastScaled = target
        return target
    }

    @Synchronized
    fun lastFrameTime(file: File): Double =
        sources[file.absolutePath]?.decoder?.lastDecodedTime ?: Double.NaN

    @Synchronized
    fun close() {
        sources.values.forEach { runCatching { it.decoder.close() } }
        sources.clear()
    }
}
