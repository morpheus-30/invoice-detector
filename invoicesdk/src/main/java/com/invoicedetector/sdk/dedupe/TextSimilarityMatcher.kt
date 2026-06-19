package com.invoicedetector.sdk.dedupe

/**
 * Decides whether two invoices are the same bill by comparing their OCR token sets
 * (from [TextFingerprint]). Robust to cropping and re-photographing.
 *
 * Uses the **overlap coefficient** = |A ∩ B| / min(|A|, |B|) rather than Jaccard,
 * because a crop is a subset of the original: the smaller set is (almost) fully
 * contained in the larger, so the overlap stays near 1.0.
 *
 * To avoid flagging two *different* invoices from the same vendor (which share lots
 * of boilerplate words), the strongest signal is the overlap of **numeric** tokens
 * (amounts, dates, invoice numbers) - those genuinely differ between invoices.
 */
internal object TextSimilarityMatcher {

    /** Numeric-token overlap above this (with enough numbers) => same invoice. */
    private const val NUMERIC_OVERLAP = 0.60f

    /** All-token overlap above this => same invoice even without the numeric anchor. */
    private const val ALL_OVERLAP_STRONG = 0.82f

    /** Minimum shared tokens to trust any match (guards against tiny/trivial crops). */
    private const val MIN_SHARED = 5

    /** Minimum numeric tokens on each side before the numeric path is allowed. */
    private const val MIN_NUMERIC = 3

    data class Score(val isDuplicate: Boolean, val similarity: Float)

    fun compare(a: Set<String>, b: Set<String>): Score {
        if (a.isEmpty() || b.isEmpty()) return Score(false, 0f)

        val shared = a.count { it in b }
        if (shared < MIN_SHARED) return Score(false, 0f)

        val allOverlap = shared.toFloat() / minOf(a.size, b.size)

        val aNums = a.filter { it.any(Char::isDigit) }
        val bNums = b.filter { it.any(Char::isDigit) }
        val sharedNums = aNums.count { it in bNums }
        val numericOverlap = if (aNums.size >= MIN_NUMERIC && bNums.size >= MIN_NUMERIC) {
            sharedNums.toFloat() / minOf(aNums.size, bNums.size)
        } else {
            null
        }

        val isDuplicate =
            (numericOverlap != null && numericOverlap >= NUMERIC_OVERLAP && allOverlap >= 0.45f) ||
                allOverlap >= ALL_OVERLAP_STRONG

        val similarity = maxOf(allOverlap, numericOverlap ?: 0f)
        return Score(isDuplicate, similarity)
    }
}
