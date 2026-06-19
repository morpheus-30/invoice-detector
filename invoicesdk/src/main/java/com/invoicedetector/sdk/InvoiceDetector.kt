package com.invoicedetector.sdk

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.invoicedetector.sdk.internal.BitmapLoader
import com.invoicedetector.sdk.model.InvoiceResult
import com.invoicedetector.sdk.pipeline.InvoicePipeline
import com.invoicedetector.sdk.storage.DuplicateStore
import com.invoicedetector.sdk.storage.RoomDuplicateStore
import java.io.Closeable

/**
 * Entry point of the Invoice Detector SDK.
 *
 * Fully on-device: there are no network calls. Create one instance (it is cheap to
 * keep around for the lifetime of a screen/app) and call [process]. Remember to
 * [close] it to release the OCR engine.
 *
 * ```
 * val detector = InvoiceDetector.create(context)
 * val result = detector.process(imageUri)   // suspend
 * detector.close()
 * ```
 *
 * @see InvoiceDetectorConfig for tuning thresholds.
 */
class InvoiceDetector private constructor(
    private val appContext: Context,
    private val config: InvoiceDetectorConfig,
    private val pipeline: InvoicePipeline
) : Closeable {

    /**
     * Runs the full detection pipeline on the image located at [uri].
     * Safe to call from any coroutine; heavy work is dispatched to a worker thread.
     */
    suspend fun process(uri: Uri): InvoiceResult {
        val bitmap = try {
            BitmapLoader.fromUri(appContext, uri, config.ocrMaxDim)
        } catch (t: Throwable) {
            return InvoiceResult.Rejected.Error(t, "Could not read the selected image.")
        }
        return process(bitmap)
    }

    /**
     * Runs the full detection pipeline on an already-decoded [bitmap].
     * The caller retains ownership of [bitmap].
     */
    suspend fun process(bitmap: Bitmap): InvoiceResult = pipeline.run(bitmap)

    /**
     * Removes a previously accepted invoice from the local duplicate index, e.g.
     * after the user deletes it. Returns true if a row was removed.
     */
    suspend fun forget(recordId: Long): Boolean = pipeline.forget(recordId)

    /** Wipes the entire local duplicate index. */
    suspend fun clearIndex() = pipeline.clearIndex()

    /** Releases native/OCR resources. The instance must not be used afterwards. */
    override fun close() {
        pipeline.close()
    }

    companion object {
        /**
         * Creates a detector backed by the on-device Room duplicate index.
         *
         * @param context any Context; the application context is retained.
         * @param config tuning thresholds (see [InvoiceDetectorConfig]).
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            context: Context,
            config: InvoiceDetectorConfig = InvoiceDetectorConfig.default()
        ): InvoiceDetector {
            val appContext = context.applicationContext
            val store: DuplicateStore = RoomDuplicateStore.create(appContext)
            return create(appContext, config, store)
        }

        /**
         * Creates a detector with a caller-supplied [DuplicateStore]. Use this to
         * back the duplicate index with a shared/home-server store instead of the
         * default on-device database, while still keeping all detection on-device.
         */
        @JvmStatic
        fun create(
            context: Context,
            config: InvoiceDetectorConfig,
            duplicateStore: DuplicateStore
        ): InvoiceDetector {
            val appContext = context.applicationContext
            val pipeline = InvoicePipeline(appContext, config, duplicateStore)
            return InvoiceDetector(appContext, config, pipeline)
        }
    }
}
