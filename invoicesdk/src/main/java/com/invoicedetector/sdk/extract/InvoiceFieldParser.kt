package com.invoicedetector.sdk.extract

import com.invoicedetector.sdk.model.ExtractedInvoice
import com.invoicedetector.sdk.text.InvoiceLexicon
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Layout-aware, multilingual extraction of structured fields from OCR text, tuned
 * for European invoices (decimal-comma amounts, dd.mm.yyyy dates, € / £ / CHF / PLN
 * etc.).
 *
 * The key precision improvement over a naive line scan is that it uses each line's
 * geometry ([OcrLine]) to pair a label with its value: the amount for "Total" is
 * looked for (1) on the same text line, then (2) on the same visual row to the
 * right, then (3) on the next row down. This recovers values from the common
 * two-column invoice layout where OCR splits the label and the number apart.
 */
class InvoiceFieldParser {

    private val invoiceNumberRegex = Regex(
        """(?:invoice|facture|rechnung|rechnungsnummer|factura|fattura|factuur|fatura|faktura|inv|bill|receipt|re[cç]u|recibo|ricevuta)\s*""" +
            """(?:no\.?|n[o°º]\.?|nr\.?|num\.?|number|núm\.?|#)?\s*[:#\-]?\s*([A-Za-z0-9][A-Za-z0-9/_\-]{2,})""",
        RegexOption.IGNORE_CASE
    )

    private val standaloneNumberRegex = Regex(
        """\b(?:n[o°º]|nr|num|núm)\.?\s*[:#]?\s*([A-Za-z0-9][A-Za-z0-9/_\-]{2,})""",
        RegexOption.IGNORE_CASE
    )

    // Numeric (dd.mm.yyyy / dd/mm/yy / yyyy-mm-dd) plus textual months in several
    // European languages.
    private val dateRegex = Regex(
        """\b(\d{1,4}[\/.\-]\d{1,2}[\/.\-]\d{2,4}|""" +
            """\d{1,2}[.\s]+(?:jan|feb|mar|apr|may|mai|jun|jui|jul|aug|sep|sept|oct|okt|nov|dec|dez|dic|gen|mag|giu|lug|ago|set|ott|ene|abr|mär|maj|kes|hei)[a-zà-ÿ.]*\.?\s+\d{2,4}|""" +
            """(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2},?\s+\d{2,4})\b""",
        RegexOption.IGNORE_CASE
    )

    // Strongest-first so we grab the grand total, not a sub-line.
    private val totalLabelsOrdered = listOf(
        "grand total", "total due", "amount due", "total ttc", "gesamtbetrag",
        "gesamtsumme", "zu zahlen", "importe total", "totale dovuto", "total a pagar",
        "te betalen", "net à payer", "montant dû", "montant du", "total amount",
        "total general", "yhteensä", "att betala", "totaal", "totale", "gesamt",
        "summe", "suma", "summa", "total"
    )

    fun parse(ocr: OcrResult): ExtractedInvoice {
        val lines = ocr.textLines

        val invoiceNumber = lines.firstNotNullOfOrNull { line ->
            (invoiceNumberRegex.find(line.text)?.groupValues?.get(1)
                ?: standaloneNumberRegex.find(line.text)?.groupValues?.get(1))
                ?.takeIf { it.any(Char::isDigit) && !looksLikeAmount(it) }
        }

        val dateText = extractDate(lines)
        val subtotal = amountForLabels(lines, InvoiceLexicon.SUBTOTAL.synonyms)
        val taxAmount = amountForLabels(lines, InvoiceLexicon.TAX.synonyms)
        val total = amountForLabels(lines, totalLabelsOrdered, avoidSubtotal = true)
            ?: bestStandaloneTotal(lines)

        val currency = detectCurrency(lines)
        val vendor = guessVendor(lines)

        return ExtractedInvoice(
            rawText = ocr.fullText,
            invoiceNumber = invoiceNumber,
            dateText = dateText,
            vendor = vendor,
            subtotal = subtotal,
            taxAmount = taxAmount,
            total = total,
            currency = currency,
            lineItems = emptyList()
        )
    }

    // ---- Dates -------------------------------------------------------------

    private fun extractDate(lines: List<OcrLine>): String? {
        // Prefer a date that sits on (or right after) a "date" label.
        for (line in lines) {
            val lower = line.text.lowercase(Locale.ROOT)
            if (InvoiceLexicon.DATE_LABEL.matches(lower)) {
                dateRegex.find(line.text)?.let { return it.value.trim() }
            }
        }
        // Otherwise the first plausible date anywhere.
        return lines.firstNotNullOfOrNull { dateRegex.find(it.text)?.value?.trim() }
    }

    // ---- Amounts -----------------------------------------------------------

    /**
     * Finds the amount associated with the first matching label (labels tried in
     * priority order), using same-line -> same-row -> next-row resolution.
     */
    private fun amountForLabels(
        lines: List<OcrLine>,
        labels: List<String>,
        avoidSubtotal: Boolean = false
    ): Double? {
        for (label in labels) {
            for ((index, line) in lines.withIndex()) {
                val lower = line.text.lowercase(Locale.ROOT)
                if (!lower.contains(label)) continue
                if (avoidSubtotal && InvoiceLexicon.SUBTOTAL.matches(lower)) continue

                // 1. Amount printed on the same OCR line.
                AmountParser.lastAmount(line.text)?.let { return it }
                // 2. Amount on the same visual row, to the right of the label.
                sameRowAmount(line, lines)?.let { return it }
                // 3. Amount on the next reading line.
                nextLineAmount(index, lines)?.let { return it }
            }
        }
        return null
    }

    /** Right-most amount sharing a row with [label] (typical two-column layout). */
    private fun sameRowAmount(label: OcrLine, lines: List<OcrLine>): Double? {
        if (!label.hasGeometry) return null
        var best: Double? = null
        var bestLeft = Int.MIN_VALUE
        for (other in lines) {
            if (other === label || !other.hasGeometry) continue
            if (!sameRow(label, other)) continue
            if (other.left < label.left) continue // value is normally to the right
            val amount = AmountParser.lastAmount(other.text) ?: continue
            if (other.left > bestLeft) {
                bestLeft = other.left
                best = amount
            }
        }
        return best
    }

    /** Amount on the immediately following reading line, if it is essentially just a number. */
    private fun nextLineAmount(index: Int, lines: List<OcrLine>): Double? {
        for (i in (index + 1)..minOf(index + 2, lines.lastIndex)) {
            val candidate = lines[i].text
            val amounts = AmountParser.findAmounts(candidate)
            if (amounts.isNotEmpty() && isMostlyAmount(candidate)) {
                return amounts.last()
            }
        }
        return null
    }

    /**
     * When no labelled total is found, fall back to the largest amount that appears
     * alongside a currency marker (avoids grabbing a phone number / ID), or the
     * largest amount overall as a last resort.
     */
    private fun bestStandaloneTotal(lines: List<OcrLine>): Double? {
        val withCurrency = lines
            .filter { AmountParser.detectCurrency(it.text) != null }
            .flatMap { AmountParser.findAmounts(it.text) }
        if (withCurrency.isNotEmpty()) return withCurrency.max()
        return lines.flatMap { AmountParser.findAmounts(it.text) }.maxOrNull()
    }

    // ---- Currency ----------------------------------------------------------

    private fun detectCurrency(lines: List<OcrLine>): String? =
        lines.firstNotNullOfOrNull { AmountParser.detectCurrency(it.text) }

    // ---- Vendor ------------------------------------------------------------

    /**
     * The merchant name is usually the largest text near the top. With geometry we
     * pick the tallest qualifying line in the top portion of the page; without it we
     * fall back to the first reasonable line.
     */
    private fun guessVendor(lines: List<OcrLine>): String? {
        val candidates = lines.filter { isVendorCandidate(it.text) }
        if (candidates.isEmpty()) return null

        val withGeometry = candidates.filter { it.hasGeometry }
        if (withGeometry.isNotEmpty()) {
            val maxBottom = lines.filter { it.hasGeometry }.maxOf { it.bottom }.coerceAtLeast(1)
            val topBand = withGeometry.filter { it.top <= maxBottom * 0.35 }
                .ifEmpty { withGeometry }
            return topBand.maxByOrNull { it.height }?.text?.trim()
        }
        return candidates.first().text.trim()
    }

    private fun isVendorCandidate(text: String): Boolean {
        val t = text.trim()
        if (t.length !in 2..40) return false
        if (!t.any(Char::isLetter)) return false
        if (dateRegex.containsMatchIn(t)) return false
        if (t.contains('@') || t.contains("http", ignoreCase = true) || t.contains("www.")) return false
        val digits = t.count(Char::isDigit)
        if (digits > t.length / 2) return false // mostly numeric -> not a name
        val lower = t.lowercase(Locale.ROOT)
        if (InvoiceLexicon.INVOICE.matches(lower) || InvoiceLexicon.RECEIPT.matches(lower)) return false
        return true
    }

    // ---- Small helpers -----------------------------------------------------

    private fun sameRow(a: OcrLine, b: OcrLine): Boolean {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        return overlap > 0 && overlap >= 0.4 * min(a.height, b.height)
    }

    /** True if the line is dominated by a number (so it is a value, not prose). */
    private fun isMostlyAmount(text: String): Boolean {
        val digits = text.count(Char::isDigit)
        val letters = text.count(Char::isLetter)
        return digits >= 1 && digits >= letters
    }

    private fun looksLikeAmount(token: String): Boolean =
        AmountParser.parse(token) != null && token.none { it.isLetter() }
}
