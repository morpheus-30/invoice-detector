package com.invoicedetector.sdk.internal

import android.graphics.Bitmap
import kotlin.math.max

/**
 * A compact grayscale view of a bitmap, used by the blur detector and the
 * perceptual hasher. Pixels are luminance values in 0..255, row-major.
 *
 * Kept deliberately allocation-light: a single IntArray, no per-pixel objects,
 * so it is cheap even on old phones.
 */
internal class GrayscaleImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray
) {
    companion object {
        /**
         * Builds a grayscale image scaled so its longest edge is [maxDim]
         * (never up-scaling beyond the source).
         */
        fun fromBitmap(bitmap: Bitmap, maxDim: Int): GrayscaleImage {
            val longest = max(bitmap.width, bitmap.height)
            val ratio = if (longest > maxDim) maxDim.toFloat() / longest else 1f
            val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
            val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
            return fromBitmapExact(bitmap, w, h)
        }

        /** Builds a grayscale image at an exact [w] x [h] (used for fixed-size hashing). */
        fun fromBitmapExact(bitmap: Bitmap, w: Int, h: Int): GrayscaleImage {
            val scaled = if (bitmap.width == w && bitmap.height == h) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            }
            val argb = IntArray(w * h)
            scaled.getPixels(argb, 0, w, 0, 0, w, h)
            if (scaled != bitmap) scaled.recycle()

            val gray = IntArray(w * h)
            for (i in argb.indices) {
                val c = argb[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // Rec. 601 luma; integer math keeps it fast on weak CPUs.
                gray[i] = (r * 77 + g * 150 + b * 29) shr 8
            }
            return GrayscaleImage(w, h, gray)
        }
    }
}
