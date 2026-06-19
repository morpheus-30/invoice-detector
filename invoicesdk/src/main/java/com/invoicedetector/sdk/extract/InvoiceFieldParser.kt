package com.invoicedetector.sdk.extract

import com.invoicedetector.sdk.model.ExtractedInvoice
import com.invoicedetector.sdk.text.InvoiceLexicon
import java.util.Locale

/**
 * Best-effort extraction of structured fields from OCR text, across European
 * languages. Resilient rather than perfect: the fields it finds (invoice number,
 * total, date) are enough to drive duplicate detection and authenticity checks,
 * and extraction quality can be improved later per the product priorities.
 */
class InvoiceFieldParser {

    // Document-number labels in several languages, followed by the actual number.
    private val invoiceNumberRegex = Regex(
        """(?:invoice|facture|rechnung|factura|fattura|factuur|fatura|faktura|inv|bill|receipt|re[cç]u|recibo|ricevuta)\s*""" +
            """(?:no\.?|n[o°º]\.?|nr\.?|num\.?|number|núm\.?|#)?\s*[:#\-]?\s*([A-Za-z0-9][A-Za-z0-9/_\-]{2,})""",
        RegexOption.IGNORE_CASE
    )

    // Standalone number labels like "N° 12345", "Nr. 2024-17".
    private val standaloneNumberRegex = Regex(
        """\b(?:n[o°º]|nr|num|núm)\.?\s*[:#]?\s*([A-Za-z0-9][A-Za-z0-9/_\-]{2,})""",
        RegexOption.IGNORE_CASE
    )

    // Numeric dates: dd/mm/yyyy, dd.mm.yyyy, dd-mm-yy, yyyy-mm-dd (common in EU),
    // plus textual months in several languages.
    private val dateRegex = Regex(
        """\b(\d{1,4}[\/.\-]\d{1,2}[\/.\-]\d{2,4}|""" +
            """\d{1,2}[.\s]+(?:jan|feb|mar|apr|may|mai|jun|jul|aug|sep|sept|oct|okt|nov|dec|dez|dic|gen|mag|giu|lug|ago|set|ott|ene|abr|ago)[a-z.]*\.?\s+\d{2,4}|""" +
            """(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2},?\s+\d{2,4})\b""",
        RegexOption.IGNORE_CASE
    )

    // Ordered strongest-first so we grab the grand total, not a sub-line.
    private val totalLabelsOrdered = listOf(
        "grand total", "total due", "amount due", "total ttc", "gesamtbetrag",
        "gesamtsumme", "zu zahlen", "importe total", "totale dovuto", "total a pagar",
        "te betalen", "net à payer", "montant dû", "montant du", "total amount",
        "total general", "yhteensä", "att betala", "totaal", "totale", "gesamt",
        "summe", "suma", "summa", "total"
    )

    fun parse(ocr: OcrResult): ExtractedInvoice {
        val lines = ocr.lines

        val invoiceNumber = lines.firstNotNullOfOrNull { line ->
            (invoiceNumberRegex.find(line)?.groupValues?.get(1)
                ?: standaloneNumberRegex.find(line)?.groupValues?.get(1))
                ?.takeIf { it.any(Char::isDigit) }
        }

        val dateText = lines.firstNotNullOfOrNull { line ->
            dateRegex.find(line)?.value?.trim()
        }

        val subtotal = findLabeledAmount(lines, InvoiceLexicon.SUBTOTAL.synonyms)
        val taxAmount = findLabeledAmount(lines, InvoiceLexicon.TAX.synonyms)
        val total = findLabeledAmount(lines, totalLabelsOrdered, avoidSubtotal = true)
            ?: lines.flatMap { AmountParser.findAmounts(it) }.maxOrNull()

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
     * Finds the amount on the first line whose label matches one of [labels]
     * (tried in order). When [avoidSubtotal] is set, lines that look like a
     * subtotal are skipped so we don't mistake it for the grand total.
     */
    private fun findLabeledAmount(
        lines: List<String>,
        labels: List<String>,
        avoidSubtotal: Boolean = false
    ): Double? {
        for (label in labels) {
            for (line in lines) {
                val lower = line.lowercase(Locale.ROOT)
                if (!lower.contains(label)) continue
                if (avoidSubtotal && InvoiceLexicon.SUBTOTAL.matches(lower)) continue
                AmountParser.lastAmount(line)?.let { return it }
            }
        }
        return null
    }

    /** Heuristic: the first reasonably long line near the top is usually the vendor. */
    private fun guessVendor(lines: List<String>): String? =
        lines.take(5)
            .firstOrNull { it.length in 3..40 && it.any(Char::isLetter) && !dateRegex.containsMatchIn(it) }
            ?.trim()
}
