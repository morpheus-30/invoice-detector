package com.invoicedetector.sdk.dedupe

import com.invoicedetector.sdk.model.ExtractedInvoice
import java.security.MessageDigest
import java.util.Locale

/**
 * Builds a content fingerprint from the *meaning* of an invoice rather than its
 * pixels. Two different photos (or a photo and a PDF screenshot) of the same bill
 * will share the same invoice number + total + date, so they hash identically -
 * catching duplicates that perceptual hashing alone would miss.
 *
 * Returns null when there isn't enough structured data to fingerprint reliably,
 * in which case the pipeline falls back to image-based matching only.
 */
object ContentHasher {

    fun fingerprint(invoice: ExtractedInvoice): String? {
        val number = invoice.invoiceNumber?.let { normalizeId(it) }
        val total = invoice.total

        // Require at least an invoice number OR (vendor + total) to fingerprint.
        val parts = mutableListOf<String>()
        when {
            number != null && total != null -> {
                parts += "n=$number"
                parts += "t=${normalizeAmount(total)}"
            }
            number != null -> {
                parts += "n=$number"
                invoice.vendor?.let { parts += "v=${normalizeText(it)}" }
            }
            total != null && invoice.vendor != null -> {
                parts += "t=${normalizeAmount(total)}"
                parts += "v=${normalizeText(invoice.vendor)}"
                invoice.dateText?.let { parts += "d=${normalizeText(it)}" }
            }
            else -> return null
        }
        return sha256Hex(parts.joinToString("|"))
    }

    private fun normalizeId(s: String): String =
        s.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun normalizeText(s: String): String =
        s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun normalizeAmount(v: Double): String =
        String.format(Locale.ROOT, "%.2f", v)

    private fun sha256Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val x = b.toInt() and 0xFF
            sb.append(HEX[x ushr 4]).append(HEX[x and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
