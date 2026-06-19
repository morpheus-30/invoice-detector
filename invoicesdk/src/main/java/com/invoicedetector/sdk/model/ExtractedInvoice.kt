package com.invoicedetector.sdk.model

/**
 * Structured data pulled out of an invoice image.
 *
 * Data extraction is intentionally "best effort" - per the product priorities the
 * critical signals are detection / duplicate / authenticity, while field extraction
 * can be improved later. Every field is therefore nullable and accompanied by the
 * raw OCR text so a host app can re-parse or display the original.
 */
data class ExtractedInvoice(
    /** Raw text returned by the OCR engine, newline separated, top-to-bottom. */
    val rawText: String,
    /** Detected invoice / bill number, if any. */
    val invoiceNumber: String? = null,
    /** Detected invoice date in the original textual form. */
    val dateText: String? = null,
    /** Detected supplier / merchant name (first strong text line, heuristic). */
    val vendor: String? = null,
    /** Parsed monetary subtotal (pre-tax), if found. */
    val subtotal: Double? = null,
    /** Parsed tax / VAT / GST amount, if found. */
    val taxAmount: Double? = null,
    /** Parsed grand total, if found. */
    val total: Double? = null,
    /** Detected currency symbol or ISO code, if found. */
    val currency: String? = null,
    /** Line items detected on the invoice (best effort). */
    val lineItems: List<LineItem> = emptyList()
) {
    data class LineItem(
        val description: String,
        val amount: Double?
    )
}
