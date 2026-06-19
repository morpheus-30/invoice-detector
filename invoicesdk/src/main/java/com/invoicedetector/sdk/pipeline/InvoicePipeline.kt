package com.invoicedetector.sdk.pipeline

import android.content.Context
import android.graphics.Bitmap
import com.invoicedetector.sdk.InvoiceDetectorConfig
import com.invoicedetector.sdk.authenticity.AuthenticityValidator
import com.invoicedetector.sdk.authenticity.TamperingDetector
import com.invoicedetector.sdk.classify.InvoiceClassifier
import com.invoicedetector.sdk.dedupe.DuplicateChecker
import com.invoicedetector.sdk.extract.InvoiceFieldParser
import com.invoicedetector.sdk.extract.TextExtractor
import com.invoicedetector.sdk.model.InvoiceResult
import com.invoicedetector.sdk.quality.BlurDetector
import com.invoicedetector.sdk.storage.DuplicateStore
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates the end-to-end detection flow. Stages run cheapest-first so a weak
 * phone can bail out early:
 *
 *   1. Blur gate         - reject out-of-focus shots, ask the user to retake.
 *   2. OCR               - on-device text recognition (no network).
 *   3. Invoice classify  - is this even an invoice?
 *   4. Duplicate check   - cancel if we've seen this bill before (top priority).
 *   5. Authenticity      - flag likely-fake / altered invoices for review.
 *   6. Persist           - remember accepted invoices so future copies are caught.
 *
 * All work is moved off the caller's thread via [Dispatchers.Default].
 */
internal class InvoicePipeline(
    context: Context,
    private val config: InvoiceDetectorConfig,
    store: DuplicateStore
) : Closeable {

    private val store: DuplicateStore = store
    private val blurDetector = BlurDetector(config.blurAnalysisMaxDim, config.blurThreshold)
    private val textExtractor = TextExtractor()
    private val fieldParser = InvoiceFieldParser()
    private val classifier = InvoiceClassifier(config.classificationThreshold)
    private val authenticityValidator = AuthenticityValidator(config.authenticityThreshold)
    private val duplicateChecker = DuplicateChecker(store, config.perceptualHashHammingThreshold)

    suspend fun run(bitmap: Bitmap): InvoiceResult = withContext(Dispatchers.Default) {
        try {
            // 1. Blur / focus gate.
            val quality = blurDetector.analyze(bitmap)
            if (!quality.isSharp) {
                return@withContext InvoiceResult.Rejected.Blurry(quality)
            }

            // 2. OCR.
            val ocr = textExtractor.extract(bitmap)
            if (ocr.charCount < config.minTextLength) {
                return@withContext InvoiceResult.Rejected.Unreadable()
            }

            // 3. Field parsing + invoice classification.
            val invoice = fieldParser.parse(ocr)
            val classification = classifier.classify(ocr, invoice)
            if (!classification.isInvoice) {
                return@withContext InvoiceResult.Rejected.NotAnInvoice(classification)
            }

            // 4. Duplicate detection (highest-priority rejection).
            val fingerprint = duplicateChecker.fingerprint(bitmap, invoice)
            duplicateChecker.findDuplicate(fingerprint)?.let { match ->
                return@withContext InvoiceResult.Rejected.Duplicate(match)
            }

            // 5. Authenticity / fake-invoice check.
            val tampering = if (config.enableImageTamperingCheck) {
                TamperingDetector.looksTampered(bitmap)
            } else {
                false
            }
            val authenticity = authenticityValidator.validate(invoice, ocr, tampering)
            if (!authenticity.isLikelyGenuine) {
                return@withContext InvoiceResult.Rejected.SuspectedFake(authenticity, invoice)
            }

            // 6. Accept + remember for future duplicate detection.
            val recordId = duplicateChecker.remember(fingerprint)
            InvoiceResult.Accepted(
                recordId = recordId,
                invoice = invoice,
                quality = quality,
                classification = classification,
                authenticity = authenticity
            )
        } catch (t: Throwable) {
            InvoiceResult.Rejected.Error(t)
        }
    }

    suspend fun forget(recordId: Long): Boolean =
        withContext(Dispatchers.Default) { store.delete(recordId) }

    suspend fun clearIndex() = withContext(Dispatchers.Default) { store.clear() }

    override fun close() {
        textExtractor.close()
    }
}
