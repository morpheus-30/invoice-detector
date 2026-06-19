package com.invoicedetector.sdk.authenticity

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Lightweight Error-Level-Analysis (ELA) style tampering signal.
 *
 * Idea: re-save the image as JPEG and compare it to the original. Untouched
 * regions of an already-compressed photo barely change on re-save, while pixels
 * that were edited/pasted compress very differently and "light up". We compare the
 * brightest region's error against the typical error - a large ratio suggests a
 * localized edit (e.g. a tampered total).
 *
 * This is a heuristic signal only (it can be fooled and can false-positive on
 * screenshots), so it is OFF by default and, when on, merely contributes to the
 * authenticity score rather than hard-failing on its own.
 */
internal object TamperingDetector {

    private const val RECOMPRESS_QUALITY = 90
    private const val GRID = 16            // analyze a 16x16 grid of cells
    private const val SUSPICIOUS_RATIO = 4.0

    /** @return true if a localized recompression anomaly is detected. */
    fun looksTampered(bitmap: Bitmap): Boolean {
        val resaved = recompress(bitmap) ?: return false
        try {
            val w = bitmap.width
            val h = bitmap.height
            if (w < GRID || h < GRID) return false

            val cellW = w / GRID
            val cellH = h / GRID
            val cellErrors = DoubleArray(GRID * GRID)

            for (gy in 0 until GRID) {
                for (gx in 0 until GRID) {
                    var sum = 0L
                    var count = 0
                    val startX = gx * cellW
                    val startY = gy * cellH
                    // Sparse sampling keeps this cheap on weak CPUs.
                    var y = startY
                    while (y < startY + cellH) {
                        var x = startX
                        while (x < startX + cellW) {
                            sum += pixelDiff(bitmap.getPixel(x, y), resaved.getPixel(x, y))
                            count++
                            x += 2
                        }
                        y += 2
                    }
                    cellErrors[gy * GRID + gx] = if (count > 0) sum.toDouble() / count else 0.0
                }
            }

            val mean = cellErrors.average()
            val max = cellErrors.max()
            if (mean <= 0.5) return false  // essentially flat / synthetic image
            return (max / mean) >= SUSPICIOUS_RATIO
        } finally {
            resaved.recycle()
        }
    }

    private fun pixelDiff(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return dr + dg + db
    }

    private fun recompress(bitmap: Bitmap): Bitmap? {
        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, RECOMPRESS_QUALITY, baos)
            val bytes = baos.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        }
    }
}
