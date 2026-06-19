package com.invoicedetector.sdk.dedupe

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Small, dependency-free string-similarity helpers used for fuzzy duplicate matching. */
internal object TextSimilarity {

    /** Uppercase + keep only letters/digits (so "INV-001" and "inv 001" compare equal). */
    fun normalizeId(s: String): String =
        s.uppercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    /** Lowercase + keep only letters/digits. */
    fun normalizeText(s: String): String =
        s.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    /** Levenshtein edit distance (iterative, O(n) memory). */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    /** 0f..1f similarity from edit distance (1 = identical). */
    fun ratio(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1f
        return 1f - levenshtein(a, b).toFloat() / maxLen
    }
}
