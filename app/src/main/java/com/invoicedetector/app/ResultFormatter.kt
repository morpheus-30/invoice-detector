package com.invoicedetector.app

import com.invoicedetector.sdk.model.ExtractedInvoice
import com.invoicedetector.sdk.model.InvoiceResult
import java.util.Locale

/** Severity used to colour the verdict banner. */
enum class ResultLevel { OK, WARN, ERROR, INFO }

/** Presentation model for a processed result. */
data class FormattedResult(
    val verdict: String,
    val subtitle: String,
    val level: ResultLevel,
    val iconRes: Int,
    /** label -> value rows shown under the verdict (empty when not applicable). */
    val fields: List<Pair<String, String>> = emptyList()
)

/** Turns the SDK's typed result into a friendly verdict + fields for the UI. */
object ResultFormatter {

    fun format(result: InvoiceResult): FormattedResult = when (result) {
        is InvoiceResult.Accepted -> FormattedResult(
            verdict = "Invoice detected",
            subtitle = "Looks like a valid invoice (confidence ${pct(result.classification.score)}).",
            level = ResultLevel.OK,
            iconRes = R.drawable.ic_check,
            fields = invoiceFields(result.invoice)
        )

        is InvoiceResult.Rejected.NotAnInvoice -> FormattedResult(
            verdict = "Not an invoice",
            subtitle = "Discarded \u2014 this doesn't look like an invoice or receipt " +
                "(confidence ${pct(result.classification.score)}).",
            level = ResultLevel.ERROR,
            iconRes = R.drawable.ic_close
        )

        is InvoiceResult.Rejected.Blurry -> FormattedResult(
            verdict = "Too blurry",
            subtitle = "Discarded \u2014 please retake the photo in better focus.",
            level = ResultLevel.WARN,
            iconRes = R.drawable.ic_warning
        )

        is InvoiceResult.Rejected.Unreadable -> FormattedResult(
            verdict = "Couldn't read it",
            subtitle = "No readable text found. Retake the photo in better light.",
            level = ResultLevel.WARN,
            iconRes = R.drawable.ic_warning
        )

        is InvoiceResult.Rejected.Duplicate -> FormattedResult(
            verdict = "Duplicate invoice",
            subtitle = "Already scanned before \u2014 cancelled.",
            level = ResultLevel.WARN,
            iconRes = R.drawable.ic_duplicate,
            fields = buildList {
                add("Matches record" to "#${result.match.existingRecordId}")
                result.match.existingInvoiceNumber?.let { add("Invoice no." to it) }
                add("Match type" to prettyMatch(result.match.matchType.name))
                add("Similarity" to pct(result.match.similarity))
            }
        )

        is InvoiceResult.Rejected.Error -> FormattedResult(
            verdict = "Something went wrong",
            subtitle = result.message,
            level = ResultLevel.ERROR,
            iconRes = R.drawable.ic_warning
        )
    }

    /** Only the fields we actually found, formatted for display. */
    private fun invoiceFields(inv: ExtractedInvoice): List<Pair<String, String>> = buildList {
        inv.vendor?.let { add("Vendor" to it) }
        inv.invoiceNumber?.let { add("Invoice no." to it) }
        inv.dateText?.let { add("Date" to it) }
        inv.subtotal?.let { add("Subtotal" to money(it, inv.currency)) }
        inv.taxAmount?.let { add("Tax / VAT" to money(it, inv.currency)) }
        inv.total?.let { add("Total" to money(it, inv.currency)) }
        if (isEmpty()) add("Note" to "No fields could be extracted yet.")
    }

    private fun money(value: Double, currency: String?): String {
        val amount = String.format(Locale.ROOT, "%.2f", value)
        return if (currency.isNullOrBlank()) amount else "$currency $amount"
    }

    private fun prettyMatch(raw: String): String =
        raw.lowercase(Locale.ROOT).replace('_', ' ')

    private fun pct(v: Float): String = "${(v * 100).toInt()}%"
}
