package com.invoicedetector.sdk.model

/**
 * Describes how a freshly-scanned invoice matched a previously stored one.
 *
 * @param existingRecordId Row id of the previously stored invoice in the local index.
 * @param existingInvoiceNumber Invoice number of the stored match, if it had one.
 * @param matchType Which signal triggered the match.
 * @param similarity 0f..1f similarity for the triggering signal (1 = identical).
 */
data class DuplicateMatch(
    val existingRecordId: Long,
    val existingInvoiceNumber: String?,
    val matchType: MatchType,
    val similarity: Float
) {
    enum class MatchType {
        /** Same normalized invoice number + total -> almost certainly the same bill. */
        CONTENT_FINGERPRINT,

        /**
         * Extracted fields (invoice number / total / date / vendor) matched a stored
         * record within tolerance, even though the photos differ and OCR wasn't
         * character-identical. This is what catches "another photo of the same invoice".
         */
        CONTENT_FUZZY,

        /** Visually near-identical image (perceptual hash within threshold). */
        PERCEPTUAL_IMAGE,

        /**
         * The OCR text overlaps heavily with a stored invoice (overlap coefficient),
         * so it's the same bill even after cropping or re-photographing - when image
         * hashes and parsed fields don't line up.
         */
        TEXT_SIMILARITY,

        /** Byte-identical image (exact hash). */
        EXACT_IMAGE
    }
}
