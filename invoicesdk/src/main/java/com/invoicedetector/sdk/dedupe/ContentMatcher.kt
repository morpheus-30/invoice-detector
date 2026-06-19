package com.invoicedetector.sdk.dedupe

import com.invoicedetector.sdk.storage.InvoiceFingerprint
import com.invoicedetector.sdk.storage.StoredInvoice
import kotlin.math.abs
import kotlin.math.max

/**
 * Decides whether two invoices are the *same bill* by comparing their extracted
 * fields with tolerance, rather than requiring identical hashes.
 *
 * This is what makes duplicate detection robust to "another photo of the same
 * invoice": a second photo (different angle / lighting / crop) yields a different
 * image hash and usually a different perceptual hash, but the invoice number and
 * total are the same — give or take small OCR errors. We therefore:
 *
 *  - compare invoice numbers by edit-distance (tolerating a character or two), and
 *  - confirm with the total (within a small tolerance) and/or the date,
 *  - or, when no number was read, fall back to total + date + vendor all agreeing.
 *
 * Thresholds are deliberately conservative to avoid flagging two genuinely
 * different invoices as duplicates.
 */
internal object ContentMatcher {

    /** Min number-similarity (0..1) to treat two invoice numbers as the same. */
    private const val NUMBER_STRONG = 0.90f
    private const val NUMBER_GOOD = 0.80f

    /** Invoice numbers shorter than this must match exactly (too short to fuzz safely). */
    private const val MIN_FUZZY_LEN = 5

    /** Result of comparing a candidate to the stored record. */
    data class Score(val isDuplicate: Boolean, val similarity: Float)

    fun compare(candidate: InvoiceFingerprint, stored: StoredInvoice): Score {
        val numScore = numberSimilarity(candidate.invoiceNumber, stored.invoiceNumber)
        val totalsClose = totalsClose(candidate.total, stored.total)
        val datesEqual = datesEqual(candidate.dateText, stored.dateText)
        val vendorClose = vendorSimilar(candidate.vendor, stored.vendor)

        // Path A: invoice numbers match (the strongest signal for "same bill").
        if (numScore != null) {
            // Strong number match: accept unless the totals positively disagree.
            if (numScore >= NUMBER_STRONG && totalsClose != false) {
                return Score(true, combine(numScore, if (totalsClose == true) 1f else 0.8f))
            }
            // Good (not perfect) number match needs the total to confirm.
            if (numScore >= NUMBER_GOOD && totalsClose == true) {
                return Score(true, combine(numScore, 1f))
            }
        }

        // Path B: no usable number, but total + date + vendor all agree.
        if (numScore == null && totalsClose == true && datesEqual && vendorClose) {
            return Score(true, 0.85f)
        }

        return Score(false, 0f)
    }

    /**
     * Returns null when either number is missing/too-short-to-fuzz-without-exact;
     * otherwise the 0..1 similarity. Exact normalized equality always returns 1.
     */
    private fun numberSimilarity(a: String?, b: String?): Float? {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return null
        val na = TextSimilarity.normalizeId(a)
        val nb = TextSimilarity.normalizeId(b)
        if (na.isEmpty() || nb.isEmpty()) return null
        if (na == nb) return 1f
        // Too short to fuzzy-match safely -> only an exact match (handled above) counts.
        if (max(na.length, nb.length) < MIN_FUZZY_LEN) return 0f
        return TextSimilarity.ratio(na, nb)
    }

    /** null = can't tell (a value missing); true/false = close / not close. */
    private fun totalsClose(a: Double?, b: Double?): Boolean? {
        if (a == null || b == null) return null
        val tolerance = max(0.05, 0.01 * max(abs(a), abs(b)))
        return abs(a - b) <= tolerance
    }

    private fun datesEqual(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return TextSimilarity.normalizeText(a) == TextSimilarity.normalizeText(b)
    }

    private fun vendorSimilar(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val na = TextSimilarity.normalizeText(a)
        val nb = TextSimilarity.normalizeText(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        return na == nb || na.contains(nb) || nb.contains(na) || TextSimilarity.ratio(na, nb) >= 0.8f
    }

    private fun combine(a: Float, b: Float): Float = ((a + b) / 2f).coerceIn(0f, 1f)
}
