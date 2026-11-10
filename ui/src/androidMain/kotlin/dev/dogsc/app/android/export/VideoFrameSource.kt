package dev.dogsc.app.android.export

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Sequential MediaCodec decoder for export. It keeps one decoder alive and
 * advances forward frame by frame instead of reopening MediaMetadataRetriever
 * for every output timestamp.
 */
class VideoFrameSource(file: File) {
    private val fallback = FallbackSource(file)
    val durationSeconds: Double
    val width: Int
    val height: Int
    val rotationDegrees: Int
    val frameRate: Double
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var reader: ImageReader? = null
    private var trackIndex = -1
    private var lastTime = -1.0
    private var lastFrame: Bitmap? = null
    private var inputDone = false
    private var outputDone = false
    private var seeked = false
    private val info = MediaCodec.BufferInfo()
    private var argb: IntArray = IntArray(0)
    private var reused: Bitmap? = null

    init {
        val meta = MediaMetadataRetriever()
        var duration = 0.0
        var w = 0
        var h = 0
        var rotation = 0
        try {
            meta.setDataSource(file.absolutePath)
            duration = (meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
            w = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            h = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rotation = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } finally {
            runCatching { meta.release() }
        }
        durationSeconds = duration
        rotationDegrees = rotation
        var detectedFrameRate = 0.0
        var extractorLocal: MediaExtractor? = null
        var codecLocal: MediaCodec? = null
        var readerLocal: ImageReader? = null
        try {
            val ex = MediaExtractor()
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("没有视频轨道")
            val format = ex.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                detectedFrameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
            }
            val fmtW = format.getInteger(MediaFormat.KEY_WIDTH)
            val fmtH = format.getInteger(MediaFormat.KEY_HEIGHT)
            w = max(w, fmtW)
            h = max(h, fmtH)
            ex.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: "video/avc")
            val imageReader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2)
            decoder.configure(format, imageReader.surface, null, 0)
            decoder.start()
            extractor = ex
            codec = decoder
            reader = imageReader
            trackIndex = track
            extractorLocal = ex
            codecLocal = decoder
            readerLocal = imageReader
        } catch (error: Throwable) {
            Log.w("DogSCExport", "MediaCodec decoder unavailable; using retriever", error)
            runCatching { codecLocal?.stop() }
            runCatching { codecLocal?.release() }
            runCatching { readerLocal?.close() }
            runCatching { extractorLocal?.release() }
            extractor = null
            codec = null
            reader = null
        }
        width = max(w, 2)
        height = max(h, 2)
        frameRate = if (detectedFrameRate > 0.5) detectedFrameRate else 30.0
        argb = IntArray(width * height)
        if (rotation != 0) {
            // Rotated sources are rare on Android recordings; the retriever
            // applies orientation for us in that path.
            if (extractor != null) {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { reader?.close() }
                runCatching { extractor?.release() }
            }
            extractor = null
            codec = null
            reader = null
        }
    }

    /** Timestamp of the frame most recently returned by [frameAt], in seconds. */
    val lastDecodedTime: Double get() = lastTime

    fun frameAt(time: Double): Bitmap? {
        val extractor = this.extractor ?: return fallback.frameAt(time)
        val codec = this.codec ?: return fallback.frameAt(time)
        val reader = this.reader ?: return fallback.frameAt(time)
        val targetUs = (time.coerceIn(0.0, max(durationSeconds, 0.0)) * 1_000_000).toLong()
        // A video frame stays on screen for 1/fps seconds. Reuse it instead of decoding the same
        // source frame again when the preview/export clock ticks faster than the source.
        val frameInterval = 1.0 / frameRate.coerceAtLeast(1.0)
        if (seeked && lastFrame != null && time >= lastTime - 0.001 && time <= lastTime + frameInterval * 0.95) {
            return lastFrame
        }
        if (!seeked || time < lastTime - 0.05 || time > lastTime + 2.0) {
            seekTo(max(0L, targetUs - 500_000))
        }
        var guard = 0
        while (guard++ < 240) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(0)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex) ?: continue
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outputIndex = codec.dequeueOutputBuffer(info, 2_000)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (outputDone) return lastFrame ?: fallback.frameAt(time)
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                outputIndex >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, true)
                    val image = runCatching { reader.acquireNextImage() }.getOrNull()
                    if (image != null) {
                        val bitmap = imageToBitmap(image)
                        val imageTime = if (info.presentationTimeUs > 0) {
                            info.presentationTimeUs / 1_000_000.0
                        } else {
                            image.timestamp / 1_000_000_000.0
                        }
                        image.close()
                        lastFrame = bitmap
                        lastTime = imageTime
                        if (imageTime >= time - 0.001 || eos) return bitmap
                    } else if (eos) {
                        return lastFrame ?: fallback.frameAt(time)
                    }
                    if (eos) outputDone = true
                }
            }
        }
        return lastFrame ?: fallback.frameAt(time)
    }

    private fun seekTo(timeUs: Long) {
        extractor?.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec?.flush()
        inputDone = false
        outputDone = false
        lastFrame = null
        lastTime = -1.0
        seeked = true
        reader?.let { r ->
            while (true) {
                val stale = r.acquireLatestImage() ?: break
                stale.close()
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val w = image.width
        val h = image.height
        if (argb.size < w * h) argb = IntArray(w * h)
        val target = reused?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { reused = it }
        if (NativeColor.convert(image, argb)) {
            target.setPixels(argb, 0, w, 0, 0, w, h)
            return target
        }
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        for (j in 0 until h) {
            val yRow = j * yRowStride
            val uvRow = (j / 2)
            for (i in 0 until w) {
                val y = yBuffer.get(yRow + i * yPixelStride).toInt() and 0xFF
                val u = uBuffer.get(uvRow * uRowStride + (i / 2) * uPixelStride).toInt() and 0xFF
                val v = vBuffer.get(uvRow * vRowStride + (i / 2) * vPixelStride).toInt() and 0xFF
                val y1 = max(0, y - 16)
                val u1 = u - 128
                val v1 = v - 128
                val r = (1.164f * y1 + 1.596f * v1).toInt().coerceIn(0, 255)
                val g = (1.164f * y1 - 0.392f * u1 - 0.813f * v1).toInt().coerceIn(0, 255)
                val b = (1.164f * y1 + 2.017f * u1).toInt().coerceIn(0, 255)
                argb[j * w + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        target.setPixels(argb, 0, w, 0, 0, w, h)
        return target
    }

    fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { reader?.close() }
        runCatching { extractor?.release() }
        fallback.close()
    }

    private class FallbackSource(file: File) {
        private val retriever = MediaMetadataRetriever()
        private var lastTime = -1.0
        private var last: Bitmap? = null

        init {
            runCatching { retriever.setDataSource(file.absolutePath) }
        }

        fun frameAt(time: Double): Bitmap? {
            if (last != null && abs(time - lastTime) < 0.004) return last
            val bitmap = runCatching {
                retriever.getFrameAtTime(
                    (time * 1_000_000).toLong(),
                    MediaMetadataRetriever.OPTION_CLOSEST,
                )
            }.getOrNull() ?: return last
            last = bitmap
            lastTime = time
            return bitmap
        }

        fun close() {
            runCatching { retriever.release() }
            last?.recycle()
            last = null
        }
    }
}
