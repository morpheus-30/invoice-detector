package com.invoicedetector.sdk.dedupe

import android.graphics.Bitmap
import com.invoicedetector.sdk.internal.GrayscaleImage
import java.security.MessageDigest

/**
 * Produces image fingerprints for duplicate detection.
 *
 *  - [dHash] computes a 64-bit "difference hash": the image is reduced to a tiny
 *    9x8 grayscale grid, and each bit records whether a pixel is brighter than its
 *    right-hand neighbour. The result is robust to scaling, mild brightness/colour
 *    shifts and JPEG re-encoding, so two photos of the same printed invoice produce
 *    near-identical hashes. Similarity is measured with [hammingDistance].
 *
 *  - [exactHash] is a SHA-256 over the raw scaled pixels - used to catch byte-for-byte
 *    re-uploads of the very same file.
 *
 * Pure Kotlin + java.security only, so no native dependency.
 */
object PerceptualHasher {

    private const val HASH_W = 9   // 9 columns -> 8 horizontal comparisons
    private const val HASH_H = 8   // 8 rows -> 8 * 8 = 64 bits

    /** 64-bit difference hash. */
    fun dHash(bitmap: Bitmap): Long {
        val img = GrayscaleImage.fromBitmapExact(bitmap, HASH_W, HASH_H)
        var hash = 0L
        var bit = 0
        for (y in 0 until HASH_H) {
            val row = y * HASH_W
            for (x in 0 until HASH_W - 1) {
                val left = img.pixels[row + x]
                val right = img.pixels[row + x + 1]
                if (left > right) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    /** SHA-256 over the pixels of a normalized 32x32 grayscale thumbnail. */
    fun exactHash(bitmap: Bitmap): String {
        val img = GrayscaleImage.fromBitmapExact(bitmap, 32, 32)
        val bytes = ByteArray(img.pixels.size)
        for (i in img.pixels.indices) bytes[i] = img.pixels[i].toByte()
        return sha256Hex(bytes)
    }

    /** Number of differing bits between two 64-bit hashes (0..64). */
    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Maps a Hamming distance to a 0f..1f similarity (1 = identical). */
    fun similarity(distance: Int): Float = 1f - (distance.coerceIn(0, 64) / 64f)

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
