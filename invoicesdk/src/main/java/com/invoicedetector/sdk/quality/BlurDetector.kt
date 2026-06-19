package com.invoicedetector.sdk.quality

import android.graphics.Bitmap
import com.invoicedetector.sdk.internal.GrayscaleImage
import com.invoicedetector.sdk.model.QualityReport

/**
 * Focus / blur gate using the variance of the Laplacian.
 *
 * The Laplacian is a second-derivative edge operator. A sharp photo has many
 * strong edges, so the Laplacian response has a high variance; a blurry photo
 * has soft edges and low variance. This is the standard, dependency-free way to
 * measure focus (popularized by OpenCV's `cv2.Laplacian().var()`), reimplemented
 * here in pure Kotlin so the SDK pulls in no native libraries - important for old
 * devices and small APKs.
 *
 * The score is computed on a fixed-size grayscale image so the threshold is
 * resolution independent.
 */
class BlurDetector(
    private val maxDim: Int,
    private val threshold: Double
) {

    fun analyze(bitmap: Bitmap): QualityReport {
        val gray = GrayscaleImage.fromBitmap(bitmap, maxDim)
        val focusScore = laplacianVariance(gray)
        val clipping = exposureClippingRatio(gray)
        return QualityReport(
            focusScore = focusScore,
            threshold = threshold,
            isSharp = focusScore >= threshold,
            exposureClippingRatio = clipping
        )
    }

    /** Variance of the 3x3 Laplacian response over the interior pixels. */
    private fun laplacianVariance(img: GrayscaleImage): Double {
        val w = img.width
        val h = img.height
        if (w < 3 || h < 3) return 0.0

        val p = img.pixels
        var sum = 0.0
        var sumSq = 0.0
        var count = 0L

        // Kernel:  0  1  0
        //          1 -4  1
        //          0  1  0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val lap = (p[i - 1] + p[i + 1] + p[i - w] + p[i + w] - 4 * p[i]).toDouble()
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0L) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }

    /** Fraction of pixels that are fully black (<=4) or fully white (>=250). */
    private fun exposureClippingRatio(img: GrayscaleImage): Float {
        var clipped = 0
        for (v in img.pixels) {
            if (v <= 4 || v >= 250) clipped++
        }
        return clipped.toFloat() / img.pixels.size.coerceAtLeast(1)
    }
}
