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
 *  2. Fuzzy content match  - extracted fields match within tolerance. This is what
 *     catches a *different photo* of the same invoice, where the image bytes and
 *     perceptual hash differ but the invoice number/total are (almost) the same.
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
            vendor = invoice.vendor
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

        // 2. Fuzzy content match against stored records - tolerant of OCR differences
        //    between two photos of the same invoice. Pick the best-scoring match.
        if (fp.invoiceNumber != null || fp.total != null) {
            var best: DuplicateMatch? = null
            for (candidate in store.contentCandidates()) {
                val score = ContentMatcher.compare(fp, candidate)
                if (score.isDuplicate && (best == null || score.similarity > best.similarity)) {
                    best = DuplicateMatch(
                        existingRecordId = candidate.id,
                        existingInvoiceNumber = candidate.invoiceNumber,
                        matchType = DuplicateMatch.MatchType.CONTENT_FUZZY,
                        similarity = score.similarity
                    )
                }
            }
            best?.let { return it }
        }

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
