package com.invoicedetector.sdk

/**
 * Tunable thresholds for the detection pipeline.
 *
 * Defaults are chosen to be conservative and to work on low-end hardware. Every
 * value can be overridden via [Builder] (also usable from Java).
 */
data class InvoiceDetectorConfig(
    /**
     * Minimum Laplacian-variance focus score for an image to be considered sharp.
     * The score is computed on a grayscale image normalized to [blurAnalysisMaxDim],
     * so the threshold is resolution-independent. Tuned a little lenient (90) for
     * real phone-camera photos, which have shadows/uneven lighting; lower further if
     * valid photos are rejected on weak cameras.
     */
    val blurThreshold: Double = 90.0,

    /** Longest edge (px) the image is scaled to before blur analysis. Small = fast. */
    val blurAnalysisMaxDim: Int = 600,

    /**
     * Longest edge (px) the image is scaled to before OCR. 1024 keeps memory low
     * enough for old phones while preserving enough detail for text recognition.
     */
    val ocrMaxDim: Int = 1024,

    /** Minimum confidence (0f..1f) for the document to be classified as an invoice. */
    val classificationThreshold: Float = 0.45f,

    /**
     * Maximum Hamming distance (out of 64 bits) between two perceptual hashes for
     * the images to be treated as the same invoice. 0 = identical; ~10 still catches
     * re-photographed / lightly re-encoded copies.
     */
    val perceptualHashHammingThreshold: Int = 10,

    /**
     * Minimum number of OCR text characters before we trust extraction at all.
     * Below this the result is treated as [com.invoicedetector.sdk.model.InvoiceResult.Rejected.Unreadable].
     */
    val minTextLength: Int = 12,

    /**
     * If true, when the upright OCR pass looks weak the pipeline retries the image
     * rotated 90/180/270 and keeps the best result. Handles phone-camera photos
     * taken at any angle and images without EXIF orientation.
     */
    val autoDetectOrientation: Boolean = true,

    /**
     * OCR char count at/above which the upright orientation is accepted without
     * trying other rotations. Keeps the common (already-upright) case to a single
     * OCR pass while still rescuing sideways/upside-down photos.
     */
    val confidentTextLength: Int = 40
) {
    init {
        require(blurThreshold >= 0) { "blurThreshold must be >= 0" }
        require(blurAnalysisMaxDim in 64..4096) { "blurAnalysisMaxDim out of range" }
        require(ocrMaxDim in 256..8192) { "ocrMaxDim out of range" }
        require(classificationThreshold in 0f..1f) { "classificationThreshold must be 0..1" }
        require(perceptualHashHammingThreshold in 0..64) { "hamming threshold must be 0..64" }
        require(confidentTextLength >= minTextLength) { "confidentTextLength must be >= minTextLength" }
    }

    /** Fluent builder, primarily for Java callers. */
    class Builder {
        private var config = InvoiceDetectorConfig()

        fun blurThreshold(v: Double) = apply { config = config.copy(blurThreshold = v) }
        fun blurAnalysisMaxDim(v: Int) = apply { config = config.copy(blurAnalysisMaxDim = v) }
        fun ocrMaxDim(v: Int) = apply { config = config.copy(ocrMaxDim = v) }
        fun classificationThreshold(v: Float) = apply { config = config.copy(classificationThreshold = v) }
        fun perceptualHashHammingThreshold(v: Int) = apply { config = config.copy(perceptualHashHammingThreshold = v) }
        fun minTextLength(v: Int) = apply { config = config.copy(minTextLength = v) }
        fun autoDetectOrientation(v: Boolean) = apply { config = config.copy(autoDetectOrientation = v) }
        fun confidentTextLength(v: Int) = apply { config = config.copy(confidentTextLength = v) }

        fun build(): InvoiceDetectorConfig = config
    }

    companion object {
        /** Sensible defaults tuned for old/low-end Android devices. */
        @JvmStatic
        fun default(): InvoiceDetectorConfig = InvoiceDetectorConfig()
    }
}
