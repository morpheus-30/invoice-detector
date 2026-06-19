package com.invoicedetector.sdk.authenticity

import com.invoicedetector.sdk.extract.OcrResult
import com.invoicedetector.sdk.model.AuthenticityFlag
import com.invoicedetector.sdk.model.AuthenticityReport
import com.invoicedetector.sdk.model.ExtractedInvoice
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max

/**
 * Detects likely-fake / altered invoices using structural and arithmetic checks.
 *
 * The strongest signal is arithmetic consistency: a genuine invoice's
 * subtotal + tax should equal its total. Tampered totals, copy-paste fakes and
 * "not really an invoice" images tend to fail one or more of these checks.
 *
 * Each failed check lowers a confidence score that starts at 1.0; the pipeline
 * compares the final score against the configured authenticity threshold.
 */
class AuthenticityValidator(private val threshold: Float) {

    fun validate(
        invoice: ExtractedInvoice,
        ocr: OcrResult,
        tamperingSuspected: Boolean
    ): AuthenticityReport {
        val flags = ArrayList<AuthenticityFlag>()
        var score = 1.0f

        if (ocr.charCount < 25) {
            flags += AuthenticityFlag.SPARSE_TEXT
            score -= 0.30f
        }

        if (invoice.invoiceNumber == null) {
            flags += AuthenticityFlag.MISSING_INVOICE_NUMBER
            score -= 0.18f
        }

        if (invoice.total == null) {
            flags += AuthenticityFlag.MISSING_TOTAL
            score -= 0.22f
        }

        if (invoice.dateText == null) {
            flags += AuthenticityFlag.MISSING_DATE
            score -= 0.12f
        } else if (!isPlausibleDate(invoice.dateText)) {
            flags += AuthenticityFlag.IMPLAUSIBLE_DATE
            score -= 0.18f
        }

        // Arithmetic consistency: subtotal + tax == total (within tolerance).
        val sub = invoice.subtotal
        val tax = invoice.taxAmount
        val total = invoice.total
        if (sub != null && total != null) {
            val expected = sub + (tax ?: 0.0)
            val tolerance = max(0.05, total * 0.02)
            if (abs(expected - total) > tolerance) {
                flags += AuthenticityFlag.ARITHMETIC_MISMATCH
                score -= 0.40f
            }
        }

        if (tamperingSuspected) {
            flags += AuthenticityFlag.POSSIBLE_IMAGE_TAMPERING
            score -= 0.30f
        }

        val finalScore = score.coerceIn(0f, 1f)
        return AuthenticityReport(
            score = finalScore,
            threshold = threshold,
            isLikelyGenuine = finalScore >= threshold,
            flags = flags
        )
    }

    /** Accepts years roughly within [2000, currentYear + 1]. */
    private fun isPlausibleDate(dateText: String): Boolean {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val fourDigit = Regex("""\b(19|20)\d{2}\b""").find(dateText)?.value?.toIntOrNull()
        if (fourDigit != null) {
            return fourDigit in 2000..(currentYear + 1)
        }
        // Two-digit year (e.g. 23) -> assume 20xx.
        val twoDigit = Regex("""\b\d{2}\b""").findAll(dateText)
            .map { 2000 + it.value.toInt() }
            .firstOrNull { it in 2000..(currentYear + 1) }
        // If we truly can't parse a year, don't punish here (MISSING_DATE handles absence).
        return twoDigit != null || Regex("""\d""").containsMatchIn(dateText).not()
    }
}
