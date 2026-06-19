package com.invoicedetector.sdk.extract

import com.invoicedetector.sdk.model.ExtractedInvoice
import java.util.Locale

/**
 * Best-effort extraction of structured fields from OCR text.
 *
 * This is intentionally heuristic and resilient rather than perfect: per the
 * product priorities, exact field extraction can be improved later, while the
 * fields it does find (invoice number, total, date) are good enough to drive
 * duplicate detection and authenticity checks today.
 */
class InvoiceFieldParser {

    private val invoiceNumberRegex = Regex(
        """(?:invoice|inv|bill|receipt)\s*(?:no\.?|number|#|num)?\s*[:#-]?\s*([A-Za-z0-9][A-Za-z0-9/_-]{2,})""",
        RegexOption.IGNORE_CASE
    )

    // dd/mm/yyyy, dd-mm-yy, yyyy/mm/dd, 1 Jan 2024, January 1, 2024, etc.
    private val dateRegex = Regex(
        """\b(\d{1,4}[\/.\-]\d{1,2}[\/.\-]\d{2,4}|\d{1,2}\s+[A-Za-z]{3,9}\.?\s+\d{2,4}|[A-Za-z]{3,9}\.?\s+\d{1,2},?\s+\d{2,4})\b"""
    )

    private val totalKeywords = listOf(
        "grand total", "total due", "amount due", "balance due", "total amount", "net payable", "total"
    )
    private val subtotalKeywords = listOf("sub total", "subtotal", "sub-total")
    private val taxKeywords = listOf("tax", "vat", "gst", "cgst", "sgst", "igst", "sales tax")

    fun parse(ocr: OcrResult): ExtractedInvoice {
        val lines = ocr.lines

        val invoiceNumber = lines.firstNotNullOfOrNull { line ->
            invoiceNumberRegex.find(line)?.groupValues?.get(1)?.takeIf { it.any(Char::isDigit) }
        }

        val dateText = lines.firstNotNullOfOrNull { line ->
            dateRegex.find(line)?.value?.trim()
        }

        val subtotal = findLabeledAmount(lines, subtotalKeywords)
        val taxAmount = findLabeledAmount(lines, taxKeywords)
        // Prefer the strongest total label; fall back to the largest amount overall.
        val total = findLabeledAmount(lines, totalKeywords, preferStrongest = true)
            ?: ocr.lines.flatMap { AmountParser.findAmounts(it) }.maxOrNull()

        val currency = lines.firstNotNullOfOrNull { AmountParser.detectCurrency(it) }
        val vendor = guessVendor(lines)

        return ExtractedInvoice(
            rawText = ocr.fullText,
            invoiceNumber = invoiceNumber,
            dateText = dateText,
            vendor = vendor,
            subtotal = subtotal,
            taxAmount = taxAmount,
            total = total,
            currency = currency
        )
    }

    /**
     * Finds the amount on the line whose label best matches [keywords].
     * When [preferStrongest] is set, earlier keywords (e.g. "grand total") win over
     * later ones (e.g. "total") to avoid grabbing a subtotal.
     */
    private fun findLabeledAmount(
        lines: List<String>,
        keywords: List<String>,
        preferStrongest: Boolean = false
    ): Double? {
        val order = if (preferStrongest) keywords else keywords
        for (kw in order) {
            for (line in lines) {
                val lower = line.lowercase(Locale.ROOT)
                if (lower.contains(kw)) {
                    // Avoid matching "subtotal" when scanning for "total".
                    if (kw == "total" && (lower.contains("subtotal") || lower.contains("sub total"))) {
                        continue
                    }
                    AmountParser.lastAmount(line)?.let { return it }
                }
            }
        }
        return null
    }

    /** Heuristic: the first reasonably long line near the top is usually the vendor. */
    private fun guessVendor(lines: List<String>): String? {
        return lines.take(5)
            .firstOrNull { it.length in 3..40 && it.any(Char::isLetter) && !dateRegex.containsMatchIn(it) }
            ?.trim()
    }
}
