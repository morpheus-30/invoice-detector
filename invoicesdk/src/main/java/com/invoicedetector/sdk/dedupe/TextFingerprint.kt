package com.invoicedetector.sdk.dedupe

import java.util.Locale

/**
 * Builds a compact, comparable "bag of tokens" from an invoice's OCR text, used for
 * near-duplicate detection that survives cropping and re-photographing.
 *
 * Rationale: a cropped or re-shot photo of the same invoice produces totally
 * different pixels (so image/perceptual hashes differ), but the *text* it contains
 * is largely the same - and a crop is essentially a subset of the original's text.
 * Comparing token sets with an overlap coefficient (see [TextSimilarityMatcher])
 * therefore catches these duplicates where hashing cannot.
 *
 * Tokens kept:
 *  - words of length >= 3 (skips noise like "a", "of"), and
 *  - numeric tokens of length >= 2 (amounts, dates, invoice numbers) - the most
 *    distinctive signal for telling invoices apart.
 *
 * The signature is stored as a single space-joined string of distinct tokens.
 */
internal object TextFingerprint {

    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    fun signature(rawText: String): String =
        tokenize(rawText).joinToString(" ")

    fun tokenSet(signature: String): Set<String> =
        if (signature.isBlank()) emptySet() else signature.split(' ').filter { it.isNotEmpty() }.toSet()

    private fun tokenize(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (raw in text.lowercase(Locale.ROOT).split(SPLIT)) {
            if (raw.isEmpty()) continue
            val hasDigit = raw.any(Char::isDigit)
            if (hasDigit) {
                if (raw.length >= 2) out += raw
            } else if (raw.length >= 3) {
                out += raw
            }
        }
        return out
    }
}
