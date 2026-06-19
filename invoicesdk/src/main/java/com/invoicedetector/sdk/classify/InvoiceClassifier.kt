package com.invoicedetector.sdk.classify

import com.invoicedetector.sdk.extract.AmountParser
import com.invoicedetector.sdk.extract.OcrResult
import com.invoicedetector.sdk.model.ClassificationReport
import com.invoicedetector.sdk.model.ExtractedInvoice
import com.invoicedetector.sdk.text.InvoiceLexicon

/**
 * Decides whether an image is an invoice / bill / **receipt**.
 *
 * Unlike a brittle trained image model (which the user found unreliable), this
 * scores transparent, multilingual signals over the OCR text and the already
 * parsed fields. Crucially it does NOT require the literal word "invoice": a
 * photographed shop receipt in French or German still passes because it scores on
 * *structure* — a total, several money amounts, a tax line, a date, currency, etc.
 *
 * Works for European languages (EN/FR/DE/ES/IT/NL/PT/PL/SE/DK/NO/FI) via
 * [InvoiceLexicon]. Deterministic and cheap enough for old phones.
 */
class InvoiceClassifier(private val threshold: Float) {

    fun classify(ocr: OcrResult, parsed: ExtractedInvoice): ClassificationReport {
        val text = InvoiceLexicon.normalize(ocr.fullText)
        val matched = ArrayList<String>()
        var score = 0f

        fun add(weight: Float, label: String) {
            score += weight
            matched.add(label)
        }

        // Document-type words (any language). Receipt counts almost as much as invoice.
        when {
            InvoiceLexicon.INVOICE.matches(text) -> add(0.40f, "invoice-term")
            InvoiceLexicon.RECEIPT.matches(text) -> add(0.34f, "receipt-term")
        }

        // Money structure — the backbone of "is this a bill?".
        if (InvoiceLexicon.TOTAL.matches(text)) add(0.25f, "total")
        if (InvoiceLexicon.AMOUNT_DUE.matches(text)) add(0.22f, "amount-due")
        if (InvoiceLexicon.TAX.matches(text)) add(0.20f, "tax")
        if (InvoiceLexicon.SUBTOTAL.matches(text)) add(0.10f, "subtotal")

        // Count monetary amounts across all lines.
        val amountCount = ocr.lines.sumOf { AmountParser.findAmounts(it).size }
        if (amountCount >= 2) add(0.18f, "multiple-amounts")
        if (amountCount >= 4) add(0.10f, "many-amounts")

        // Currency presence (symbol or detected code).
        val hasCurrency = parsed.currency != null ||
            Regex("[€$£₹]").containsMatchIn(text) ||
            Regex("\\b(eur|usd|gbp|chf|pln|sek|dkk|nok)\\b").containsMatchIn(text)
        if (hasCurrency) add(0.12f, "currency")

        // Line-item table hints.
        if (InvoiceLexicon.QTY.matches(text) && InvoiceLexicon.PRICE.matches(text)) {
            add(0.10f, "qty+price columns")
        }

        // Structured fields the parser already found.
        if (parsed.invoiceNumber != null) add(0.18f, "invoice-number")
        if (parsed.dateText != null) add(0.12f, "date")

        val normalized = score.coerceIn(0f, 1f)
        return ClassificationReport(
            score = normalized,
            threshold = threshold,
            isInvoice = normalized >= threshold,
            matchedKeywords = matched
        )
    }
}
