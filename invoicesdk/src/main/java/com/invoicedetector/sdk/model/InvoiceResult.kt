package com.invoicedetector.sdk.model

/**
 * Outcome of running an image through [com.invoicedetector.sdk.InvoiceDetector].
 *
 * Consumers are expected to branch with an exhaustive `when`, which makes it
 * impossible to forget a rejection case:
 *
 * ```
 * when (val r = detector.process(uri)) {
 *     is InvoiceResult.Accepted        -> store(r.invoice)
 *     is InvoiceResult.Rejected.Blurry -> askUserToRetake()
 *     is InvoiceResult.Rejected.Duplicate -> cancel(r.match)
 *     ...
 * }
 * ```
 */
sealed interface InvoiceResult {

    /** Suggested next step for the host app. */
    val recommendedAction: RecommendedAction

    /** Short human-readable explanation, suitable for surfacing in UI/logs. */
    val message: String

    /** The image passed every gate and was stored in the local index. */
    data class Accepted(
        val recordId: Long,
        val invoice: ExtractedInvoice,
        val quality: QualityReport,
        val classification: ClassificationReport,
        override val message: String = "Invoice accepted."
    ) : InvoiceResult {
        override val recommendedAction: RecommendedAction = RecommendedAction.ACCEPT
    }

    /** Base type for every "not accepted" outcome. */
    sealed interface Rejected : InvoiceResult {

        /** Image too out-of-focus to trust; the user should re-capture. */
        data class Blurry(
            val quality: QualityReport,
            override val message: String =
                "Image is too blurry to read reliably. Please retake the photo."
        ) : Rejected {
            override val recommendedAction = RecommendedAction.REQUEST_NEW_IMAGE
        }

        /** The OCR engine could not find usable text (blank/garbled capture). */
        data class Unreadable(
            override val message: String =
                "No readable text was found. Please retake the photo in better light."
        ) : Rejected {
            override val recommendedAction = RecommendedAction.REQUEST_NEW_IMAGE
        }

        /** The document does not look like an invoice/bill/receipt. */
        data class NotAnInvoice(
            val classification: ClassificationReport,
            override val message: String =
                "This document does not appear to be an invoice."
        ) : Rejected {
            override val recommendedAction = RecommendedAction.REJECT
        }

        /** A matching invoice already exists in the local index - cancel it. */
        data class Duplicate(
            val match: DuplicateMatch,
            override val message: String =
                "Duplicate invoice detected; submission cancelled."
        ) : Rejected {
            override val recommendedAction = RecommendedAction.CANCEL_DUPLICATE
        }

        /** Something went wrong while processing (I/O, decode, OCR failure). */
        data class Error(
            val cause: Throwable,
            override val message: String = cause.message ?: "Processing failed."
        ) : Rejected {
            override val recommendedAction = RecommendedAction.REQUEST_NEW_IMAGE
        }
    }
}
