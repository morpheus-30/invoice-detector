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

/**
 * A single recognized line of text plus its position in the image. Geometry lets
 * the parser pair a label (e.g. "Total") with the value printed to its right or on
 * the next row, even when OCR splits them into separate blocks.
 *
 * Coordinates are pixels in the (rotation-corrected) image space. When ML Kit does
 * not report a bounding box, the coordinates are -1 and [hasGeometry] is false.
 */
data class OcrLine(
    val text: String,
    val left: Int = -1,
    val top: Int = -1,
    val right: Int = -1,
    val bottom: Int = -1
) {
    val hasGeometry: Boolean get() = right > left && bottom > top
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/**
 * OCR output: the full text plus the recognized lines (with geometry).
 * [lines] is kept as a convenience for code that only needs the strings.
 */
data class OcrResult(
    val fullText: String,
    val textLines: List<OcrLine>
) {
    val lines: List<String> get() = textLines.map { it.text }
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
                    val lines = ArrayList<OcrLine>()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            val t = line.text.trim()
                            if (t.isEmpty()) continue
                            val box = line.boundingBox
                            lines += if (box != null) {
                                OcrLine(t, box.left, box.top, box.right, box.bottom)
                            } else {
                                OcrLine(t)
                            }
                        }
                    }
                    // Reading order: top-to-bottom, then left-to-right.
                    lines.sortWith(
                        compareBy({ if (it.hasGeometry) it.top else 0 }, { if (it.hasGeometry) it.left else 0 })
                    )
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
