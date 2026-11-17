package dev.dogsc.app.android.export

import android.media.Image
import java.nio.ByteBuffer

object NativeColor {
    private val loaded: Boolean = runCatching {
        System.loadLibrary("dogsc-color")
        true
    }.getOrDefault(false)

    val isAvailable: Boolean get() = loaded

    external fun yuv420ToArgb(
        y: ByteBuffer,
        u: ByteBuffer,
        v: ByteBuffer,
        yRowStride: Int,
        uRowStride: Int,
        vRowStride: Int,
        yPixelStride: Int,
        uPixelStride: Int,
        vPixelStride: Int,
        width: Int,
        height: Int,
        out: IntArray,
    )

    external fun argbToNv12(argb: IntArray, out: ByteArray, width: Int, height: Int)

    external fun argbToI420(argb: IntArray, out: ByteArray, width: Int, height: Int)

    fun convert(image: Image, out: IntArray): Boolean {
        if (!loaded) return false
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        return runCatching {
            yuv420ToArgb(
                y.buffer, u.buffer, v.buffer,
                y.rowStride, u.rowStride, v.rowStride,
                y.pixelStride, u.pixelStride, v.pixelStride,
                image.width, image.height, out,
            )
            true
        }.getOrDefault(false)
    }
}
