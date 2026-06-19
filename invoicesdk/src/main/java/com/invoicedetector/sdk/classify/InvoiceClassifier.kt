package com.invoicedetector.sdk.classify

import com.invoicedetector.sdk.extract.OcrResult
import com.invoicedetector.sdk.model.ClassificationReport
import com.invoicedetector.sdk.model.ExtractedInvoice
import java.util.Locale

/**
 * Decides whether an image is actually an invoice / bill / receipt.
 *
 * Rather than a fragile trained image model (which the user found unreliable),
 * this uses a transparent weighted-signal scorer over the OCR text plus the
 * already-parsed fields. It is deterministic, debuggable (it reports which
 * signals fired) and cheap enough for old phones.
 */
class InvoiceClassifier(private val threshold: Float) {

    private data class Signal(val label: String, val weight: Float, val matcher: (String) -> Boolean)

    private val signals = listOf(
        Signal("tax invoice", 0.45f) { it.contains("tax invoice") },
        Signal("invoice", 0.40f) { it.contains("invoice") },
        Signal("receipt", 0.28f) { it.contains("receipt") },
        Signal("bill", 0.22f) { Regex("""\bbill\b""").containsMatchIn(it) },
        Signal("total/amount due", 0.22f) {
            it.contains("amount due") || it.contains("balance due") || it.contains("grand total")
        },
        Signal("total", 0.15f) { it.contains("total") },
        Signal("subtotal", 0.12f) { it.contains("subtotal") || it.contains("sub total") },
        Signal("tax/vat/gst", 0.18f) {
            Regex("""\b(tax|vat|gst|cgst|sgst|igst)\b""").containsMatchIn(it)
        },
        Signal("bill/ship to", 0.18f) {
            it.contains("bill to") || it.contains("ship to") || it.contains("sold to")
        },
        Signal("line-item columns", 0.12f) {
            (it.contains("qty") || it.contains("quantity")) &&
                (it.contains("price") || it.contains("amount") || it.contains("description"))
        }
    )

    fun classify(ocr: OcrResult, parsed: ExtractedInvoice): ClassificationReport {
        val text = ocr.fullText.lowercase(Locale.ROOT)
        val matched = ArrayList<String>()
        var score = 0f

        for (signal in signals) {
            if (signal.matcher(text)) {
                score += signal.weight
                matched.add(signal.label)
            }
        }

        // Structured fields add confidence beyond raw keywords.
        if (parsed.invoiceNumber != null) { score += 0.22f; matched.add("invoice number") }
        if (parsed.total != null) { score += 0.18f; matched.add("total amount") }
        if (parsed.dateText != null) { score += 0.08f; matched.add("date") }

        val normalized = score.coerceIn(0f, 1f)
        return ClassificationReport(
            score = normalized,
            threshold = threshold,
            isInvoice = normalized >= threshold,
            matchedKeywords = matched
        )
    }
}
