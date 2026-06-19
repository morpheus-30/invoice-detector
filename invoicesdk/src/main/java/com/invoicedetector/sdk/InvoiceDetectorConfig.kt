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
     * so the threshold is resolution-independent. Lower this if too many valid
     * photos are rejected on low-quality cameras.
     */
    val blurThreshold: Double = 110.0,

    /** Longest edge (px) the image is scaled to before blur analysis. Small = fast. */
    val blurAnalysisMaxDim: Int = 600,

    /**
     * Longest edge (px) the image is scaled to before OCR. 1024 keeps memory low
     * enough for old phones while preserving enough detail for text recognition.
     */
    val ocrMaxDim: Int = 1024,

    /** Minimum confidence (0f..1f) for the document to be classified as an invoice. */
    val classificationThreshold: Float = 0.45f,

    /** Minimum authenticity confidence (0f..1f) for an invoice to be accepted. */
    val authenticityThreshold: Float = 0.5f,

    /**
     * Maximum Hamming distance (out of 64 bits) between two perceptual hashes for
     * the images to be treated as the same invoice. 0 = identical; ~10 still catches
     * re-photographed / lightly re-encoded copies.
     */
    val perceptualHashHammingThreshold: Int = 10,

    /**
     * If true, runs an Error-Level-Analysis style recompression check to flag
     * possible image tampering. Slightly heavier; off by default for old phones.
     */
    val enableImageTamperingCheck: Boolean = false,

    /**
     * Minimum number of OCR text characters before we trust extraction at all.
     * Below this the result is treated as [com.invoicedetector.sdk.model.InvoiceResult.Rejected.Unreadable].
     */
    val minTextLength: Int = 12
) {
    init {
        require(blurThreshold >= 0) { "blurThreshold must be >= 0" }
        require(blurAnalysisMaxDim in 64..4096) { "blurAnalysisMaxDim out of range" }
        require(ocrMaxDim in 256..8192) { "ocrMaxDim out of range" }
        require(classificationThreshold in 0f..1f) { "classificationThreshold must be 0..1" }
        require(authenticityThreshold in 0f..1f) { "authenticityThreshold must be 0..1" }
        require(perceptualHashHammingThreshold in 0..64) { "hamming threshold must be 0..64" }
    }

    /** Fluent builder, primarily for Java callers. */
    class Builder {
        private var config = InvoiceDetectorConfig()

        fun blurThreshold(v: Double) = apply { config = config.copy(blurThreshold = v) }
        fun blurAnalysisMaxDim(v: Int) = apply { config = config.copy(blurAnalysisMaxDim = v) }
        fun ocrMaxDim(v: Int) = apply { config = config.copy(ocrMaxDim = v) }
        fun classificationThreshold(v: Float) = apply { config = config.copy(classificationThreshold = v) }
        fun authenticityThreshold(v: Float) = apply { config = config.copy(authenticityThreshold = v) }
        fun perceptualHashHammingThreshold(v: Int) = apply { config = config.copy(perceptualHashHammingThreshold = v) }
        fun enableImageTamperingCheck(v: Boolean) = apply { config = config.copy(enableImageTamperingCheck = v) }
        fun minTextLength(v: Int) = apply { config = config.copy(minTextLength = v) }

        fun build(): InvoiceDetectorConfig = config
    }

    companion object {
        /** Sensible defaults tuned for old/low-end Android devices. */
        @JvmStatic
        fun default(): InvoiceDetectorConfig = InvoiceDetectorConfig()
    }
}
