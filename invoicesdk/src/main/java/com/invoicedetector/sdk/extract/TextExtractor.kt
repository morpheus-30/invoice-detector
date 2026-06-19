package com.invoicedetector.sdk.extract

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Plain OCR output: the full text plus the individual recognized lines. */
data class OcrResult(
    val fullText: String,
    val lines: List<String>
) {
    val charCount: Int get() = fullText.count { !it.isWhitespace() }
}

/**
 * On-device OCR using ML Kit Text Recognition v2 (Latin script). The model is
 * bundled in the APK, so this performs no network calls. The recognizer is reused
 * across invocations and must be [close]d when done.
 */
class TextExtractor : Closeable {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extract(bitmap: Bitmap, rotationDegrees: Int = 0): OcrResult =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, normalizeRotation(rotationDegrees))
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val lines = ArrayList<String>()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            val t = line.text.trim()
                            if (t.isNotEmpty()) lines.add(t)
                        }
                    }
                    cont.resume(OcrResult(visionText.text, lines))
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }

    override fun close() {
        recognizer.close()
    }

    private companion object {
        /** ML Kit only accepts 0/90/180/270. */
        fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360 / 90 * 90
    }
}
