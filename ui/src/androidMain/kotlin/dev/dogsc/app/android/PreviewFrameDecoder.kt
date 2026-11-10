package dev.dogsc.app.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import dev.dogsc.app.android.export.VideoFrameSource
import java.io.File
import kotlin.math.max

/**
 * Editor preview frames from a persistent MediaCodec decoder. MediaMetadataRetriever re-seeks
 * and decodes a keyframe for every request (50–110 ms on a 1080×2400 recording); the sequential
 * decoder plus a small ring of scaled buffers keeps playback and scrubbing responsive.
 */
class PreviewFrameDecoder {
    private val sources = HashMap<String, VideoFrameSource>()
    private val buffers = arrayOfNulls<Bitmap>(3)
    private var index = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dst = Rect()

    @Synchronized
    fun frame(file: File, time: Double, maxWidth: Int = 720): Bitmap? {
        // Keep at most two decoders alive (screen + camera); each holds full-size buffers.
        if (!sources.containsKey(file.absolutePath) && sources.size >= 2) {
            val stale = sources.keys.first()
            sources.remove(stale)?.let { runCatching { it.close() } }
        }
        val source = sources.getOrPut(file.absolutePath) { VideoFrameSource(file) }
        val full = runCatching { source.frameAt(time) }.getOrNull() ?: return null
        if (full.width <= 0 || full.height <= 0) return null
        val scale = maxWidth.toFloat() / full.width
        val width = max(2, (full.width * scale).toInt())
        val height = max(2, (full.height * scale).toInt())
        val slot = index % buffers.size
        val target = buffers[slot]?.takeIf { it.width == width && it.height == height && !it.isRecycled }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { buffers[slot] = it }
        dst.set(0, 0, width, height)
        Canvas(target).drawBitmap(full, null, dst, paint)
        index++
        return target
    }

    @Synchronized
    fun close() {
        sources.values.forEach { runCatching { it.close() } }
        sources.clear()
        buffers.fill(null)
        index = 0
    }
}
