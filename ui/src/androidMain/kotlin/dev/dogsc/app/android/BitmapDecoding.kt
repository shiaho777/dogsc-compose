package dev.dogsc.app.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.max

/** Decode a user image without risking an OOM from a 50 MP camera original. */
internal fun decodeBitmapCapped(file: File, maxDimension: Int): Bitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimension) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
}
