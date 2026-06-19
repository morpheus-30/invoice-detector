package com.invoicedetector.sdk.model

/**
 * Result of the image-quality (blur / focus) gate.
 *
 * @param focusScore Laplacian variance of the grayscale image. Higher = sharper.
 * @param threshold The minimum [focusScore] required to be considered sharp.
 * @param isSharp Whether the image passed the focus gate.
 */
data class QualityReport(
    val focusScore: Double,
    val threshold: Double,
    val isSharp: Boolean,
    /** Fraction of the frame that is over/under exposed (0f..1f), informational. */
    val exposureClippingRatio: Float = 0f
)

/**
 * Result of the "is this actually an invoice?" classifier.
 *
 * @param score 0f..1f confidence that the document is an invoice/bill/receipt.
 * @param threshold Minimum [score] required to be treated as an invoice.
 * @param matchedKeywords Keywords/patterns that contributed to the score (for debugging/UX).
 */
data class ClassificationReport(
    val score: Float,
    val threshold: Float,
    val isInvoice: Boolean,
    val matchedKeywords: List<String> = emptyList()
)

/**
 * Result of the authenticity / "false invoice" checks.
 *
 * @param score 0f..1f confidence that the invoice is genuine (1 = looks genuine).
 * @param isLikelyGenuine Whether [score] cleared the configured threshold.
 * @param flags Specific problems found (arithmetic mismatch, missing fields, ...).
 */
data class AuthenticityReport(
    val score: Float,
    val threshold: Float,
    val isLikelyGenuine: Boolean,
    val flags: List<AuthenticityFlag> = emptyList()
)

/** A specific reason the invoice looks suspicious. */
enum class AuthenticityFlag {
    /** subtotal + tax did not add up to the stated total. */
    ARITHMETIC_MISMATCH,

    /** No invoice/bill number could be located. */
    MISSING_INVOICE_NUMBER,

    /** No total amount could be located. */
    MISSING_TOTAL,

    /** No date could be located. */
    MISSING_DATE,

    /** A date was found but is implausible (far future / far past). */
    IMPLAUSIBLE_DATE,

    /** Very little recognizable text - likely a screenshot/edited image. */
    SPARSE_TEXT,

    /** Image-level tampering signal (recompression / error-level analysis). */
    POSSIBLE_IMAGE_TAMPERING
}

/** What the host app should do next, in response to a result. */
enum class RecommendedAction {
    /** Everything passed - safe to store/submit. */
    ACCEPT,

    /** Ask the user to re-capture (e.g. blurry image). */
    REQUEST_NEW_IMAGE,

    /** Do not process - duplicate of an existing invoice. */
    CANCEL_DUPLICATE,

    /** Block or route for manual review - failed authenticity. */
    REVIEW_MANUALLY,

    /** Not an invoice at all - reject the upload. */
    REJECT
}
