package com.invoicedetector.app

import com.invoicedetector.sdk.model.ExtractedInvoice
import com.invoicedetector.sdk.model.InvoiceResult

/** Severity used to colour the status line. */
enum class ResultLevel { OK, WARN, ERROR }

/** Presentation model for a processed result. */
data class FormattedResult(
    val status: String,
    val detail: String,
    val level: ResultLevel
)

/** Turns the SDK's typed result into human-readable text for the demo UI. */
object ResultFormatter {

    fun format(result: InvoiceResult): FormattedResult = when (result) {
        is InvoiceResult.Accepted -> FormattedResult(
            status = "ACCEPTED",
            detail = buildString {
                appendLine("Record id: ${result.recordId}")
                appendLine("Focus score: ${"%.0f".format(result.quality.focusScore)} (min ${"%.0f".format(result.quality.threshold)})")
                appendLine("Invoice confidence: ${pct(result.classification.score)}")
                appendLine("Authenticity: ${pct(result.authenticity.score)}")
                appendLine()
                append(invoiceFields(result.invoice))
            },
            level = ResultLevel.OK
        )

        is InvoiceResult.Rejected.Blurry -> FormattedResult(
            status = "BLURRY - RETAKE",
            detail = "Focus score ${"%.0f".format(result.quality.focusScore)} is below the " +
                "minimum ${"%.0f".format(result.quality.threshold)}.\n${result.message}",
            level = ResultLevel.WARN
        )

        is InvoiceResult.Rejected.Unreadable -> FormattedResult(
            status = "UNREADABLE - RETAKE",
            detail = result.message,
            level = ResultLevel.WARN
        )

        is InvoiceResult.Rejected.NotAnInvoice -> FormattedResult(
            status = "NOT AN INVOICE",
            detail = "Confidence ${pct(result.classification.score)} " +
                "(needs ${pct(result.classification.threshold)}).\n" +
                "Matched: ${result.classification.matchedKeywords.joinToString().ifEmpty { "none" }}",
            level = ResultLevel.ERROR
        )

        is InvoiceResult.Rejected.Duplicate -> FormattedResult(
            status = "DUPLICATE - CANCELLED",
            detail = buildString {
                appendLine("Matches stored record #${result.match.existingRecordId}")
                result.match.existingInvoiceNumber?.let { appendLine("Existing invoice no: $it") }
                appendLine("Match type: ${result.match.matchType}")
                appendLine("Similarity: ${pct(result.match.similarity)}")
            },
            level = ResultLevel.WARN
        )

        is InvoiceResult.Rejected.SuspectedFake -> FormattedResult(
            status = "SUSPECTED FAKE - REVIEW",
            detail = buildString {
                appendLine("Authenticity ${pct(result.authenticity.score)} " +
                    "(needs ${pct(result.authenticity.threshold)})")
                appendLine("Flags: ${result.authenticity.flags.joinToString()}")
                appendLine()
                append(invoiceFields(result.invoice))
            },
            level = ResultLevel.ERROR
        )

        is InvoiceResult.Rejected.Error -> FormattedResult(
            status = "ERROR",
            detail = result.message,
            level = ResultLevel.ERROR
        )
    }

    private fun invoiceFields(inv: ExtractedInvoice): String = buildString {
        appendLine("Vendor:   ${inv.vendor ?: "-"}")
        appendLine("Number:   ${inv.invoiceNumber ?: "-"}")
        appendLine("Date:     ${inv.dateText ?: "-"}")
        appendLine("Subtotal: ${inv.subtotal ?: "-"}")
        appendLine("Tax:      ${inv.taxAmount ?: "-"}")
        appendLine("Total:    ${inv.total ?: "-"} ${inv.currency ?: ""}")
    }

    private fun pct(v: Float): String = "${(v * 100).toInt()}%"
}
