package com.invoicedetector.sdk.dedupe

import android.graphics.Bitmap
import com.invoicedetector.sdk.model.DuplicateMatch
import com.invoicedetector.sdk.model.ExtractedInvoice
import com.invoicedetector.sdk.storage.DuplicateStore
import com.invoicedetector.sdk.storage.InvoiceFingerprint

/**
 * Decides whether a freshly-scanned invoice is a duplicate of something already in
 * the [DuplicateStore], using three escalating signals:
 *
 *  1. Content fingerprint  - strongest: same invoice number + total -> same bill,
 *     even if the photo is completely different.
 *  2. Exact image hash     - the identical file was uploaded before.
 *  3. Perceptual hash      - a visually near-identical photo (Hamming distance
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
        // 1. Content fingerprint.
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

        // 2. Exact image hash.
        store.findByExactImageHash(fp.exactImageHash)?.let { existing ->
            return DuplicateMatch(
                existingRecordId = existing.id,
                existingInvoiceNumber = existing.invoiceNumber,
                matchType = DuplicateMatch.MatchType.EXACT_IMAGE,
                similarity = 1f
            )
        }

        // 3. Perceptual nearest neighbour.
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
