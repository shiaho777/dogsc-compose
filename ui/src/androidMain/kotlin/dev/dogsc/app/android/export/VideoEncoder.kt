package dev.dogsc.app.android.export

import android.graphics.Bitmap
import java.io.File

/** Common contract for the CPU and Surface-input H.264 encoders so export can swap paths. */
interface VideoEncoder {
    fun encodeFrame(bitmap: Bitmap, presentationTimeUs: Long)
    fun finish()
    fun release()
}

/**
 * Creates the fastest available encoder: Surface-input (hardware color conversion) when GL is
 * usable, otherwise the CPU YUV path. The fallback is constructed lazily only if Surface setup
 * throws, so devices without a GL context still export correctly.
 */
fun createVideoEncoder(
    width: Int,
    height: Int,
    frameRate: Int,
    bitrate: Int,
    output: File,
): VideoEncoder {
    if (SurfaceVideoEncoder.isSupported()) {
        val surface = runCatching {
            SurfaceVideoEncoder(width, height, frameRate, bitrate, output)
        }.getOrNull()
        if (surface != null) {
            android.util.Log.d("DogSCExport", "encoder=Surface ${width}x${height}@${frameRate}")
            return surface
        }
        android.util.Log.w("DogSCExport", "Surface encoder unavailable, falling back to CPU YUV")
    }
    android.util.Log.d("DogSCExport", "encoder=CPU ${width}x${height}@${frameRate}")
    return CanvasVideoEncoder(width, height, frameRate, bitrate, output)
}