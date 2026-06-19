package com.invoicedetector.sdk.dedupe

import android.graphics.Bitmap
import com.invoicedetector.sdk.model.DuplicateMatch
import com.invoicedetector.sdk.model.ExtractedInvoice
import com.invoicedetector.sdk.storage.DuplicateStore
import com.invoicedetector.sdk.storage.InvoiceFingerprint

/**
 * Decides whether a freshly-scanned invoice is a duplicate of something already in
 * the [DuplicateStore], using escalating signals (strongest / most meaningful first):
 *
 *  1. Content fingerprint  - exact normalized invoice number + total (fast O(1) path).
 *  2. Field/text scan      - over stored candidates, in one pass:
 *       a. fuzzy field match  - number/total/date/vendor within tolerance, and
 *       b. OCR text overlap   - shared token sets (overlap coefficient). This is what
 *          catches a *cropped* or re-photographed copy: the image bytes and the
 *          perceptual hash differ, but most of the text is the same.
 *  3. Exact image hash     - the identical file was uploaded before.
 *  4. Perceptual hash      - a visually near-identical photo (Hamming distance
 *     within the configured threshold).
 */
class DuplicateChecker(
    private val store: DuplicateStore,
    private val hammingThreshold: Int
) {

    /** Builds the fingerprint for an image + extracted data (also used for storage). */
    fun fingerprint(bitmap: Bitmap, invoice: ExtractedInvoice): InvoiceFingerprint =
        InvoiceFingerprint(
            perceptualHash = PerceptualHasher.dHash(bitmap),
            exactImageHash = PerceptualHasher.exactHash(bitmap),
            contentFingerprint = ContentHasher.fingerprint(invoice),
            invoiceNumber = invoice.invoiceNumber,
            total = invoice.total,
            dateText = invoice.dateText,
            vendor = invoice.vendor,
            tokenSignature = TextFingerprint.signature(invoice.rawText)
        )

    /** Returns the strongest duplicate match for [fp], or null if it looks new. */
    suspend fun findDuplicate(fp: InvoiceFingerprint): DuplicateMatch? {
        // 1. Exact content fingerprint (fast path: OCR read the number+total identically).
        fp.contentFingerprint?.let { cf ->
            store.findByContentFingerprint(cf)?.let { existing ->
                return DuplicateMatch(
                    existingRecordId = existing.id,
                    existingInvoiceNumber = existing.invoiceNumber,
                    matchType = DuplicateMatch.MatchType.CONTENT_FINGERPRINT,
                    similarity = 1f
                )
            }
        }

        // 2. Single scan over stored candidates: fuzzy fields OR OCR text overlap.
        val fpTokens = TextFingerprint.tokenSet(fp.tokenSignature)
        var best: DuplicateMatch? = null
        fun consider(candidate: DuplicateMatch) {
            if (best == null || candidate.similarity > best!!.similarity) best = candidate
        }

        for (candidate in store.contentCandidates()) {
            // a. Fuzzy field match (invoice number / total / date / vendor).
            if (fp.invoiceNumber != null || fp.total != null) {
                val fieldScore = ContentMatcher.compare(fp, candidate)
                if (fieldScore.isDuplicate) {
                    consider(
                        DuplicateMatch(
                            existingRecordId = candidate.id,
                            existingInvoiceNumber = candidate.invoiceNumber,
                            matchType = DuplicateMatch.MatchType.CONTENT_FUZZY,
                            similarity = fieldScore.similarity
                        )
                    )
                }
            }
            // b. OCR text overlap (survives cropping / re-shooting).
            if (fpTokens.isNotEmpty() && candidate.tokenSignature.isNotEmpty()) {
                val textScore = TextSimilarityMatcher.compare(
                    fpTokens,
                    TextFingerprint.tokenSet(candidate.tokenSignature)
                )
                if (textScore.isDuplicate) {
                    consider(
                        DuplicateMatch(
                            existingRecordId = candidate.id,
                            existingInvoiceNumber = candidate.invoiceNumber,
                            matchType = DuplicateMatch.MatchType.TEXT_SIMILARITY,
                            similarity = textScore.similarity
                        )
                    )
                }
            }
        }
        best?.let { return it }

        // 3. Exact image hash.
        store.findByExactImageHash(fp.exactImageHash)?.let { existing ->
            return DuplicateMatch(
                existingRecordId = existing.id,
                existingInvoiceNumber = existing.invoiceNumber,
                matchType = DuplicateMatch.MatchType.EXACT_IMAGE,
                similarity = 1f
            )
        }

        // 4. Perceptual nearest neighbour.
        var bestId = -1L
        var bestDistance = Int.MAX_VALUE
        for (entry in store.allPerceptualHashes()) {
            val d = PerceptualHasher.hammingDistance(fp.perceptualHash, entry.perceptualHash)
            if (d < bestDistance) {
                bestDistance = d
                bestId = entry.id
            }
        }
        if (bestId != -1L && bestDistance <= hammingThreshold) {
            val existing = store.getById(bestId)
            return DuplicateMatch(
                existingRecordId = bestId,
                existingInvoiceNumber = existing?.invoiceNumber,
                matchType = DuplicateMatch.MatchType.PERCEPTUAL_IMAGE,
                similarity = PerceptualHasher.similarity(bestDistance)
            )
        }

        return null
    }

    /** Persists a fingerprint after the invoice has been accepted. */
    suspend fun remember(fp: InvoiceFingerprint): Long = store.insert(fp)
}
