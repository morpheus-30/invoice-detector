package com.invoicedetector.sdk.storage.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface InvoiceDao {

    @Insert
    suspend fun insert(entity: InvoiceEntity): Long

    @Query("SELECT * FROM invoice_fingerprints WHERE contentFingerprint = :fp LIMIT 1")
    suspend fun findByContentFingerprint(fp: String): InvoiceEntity?

    @Query("SELECT * FROM invoice_fingerprints WHERE exactImageHash = :hash LIMIT 1")
    suspend fun findByExactImageHash(hash: String): InvoiceEntity?

    @Query("SELECT * FROM invoice_fingerprints WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): InvoiceEntity?

    /** Only the columns needed for perceptual Hamming comparison. */
    @Query("SELECT id, perceptualHash FROM invoice_fingerprints")
    suspend fun allPerceptualHashes(): List<PerceptualProjection>

    @Query("DELETE FROM invoice_fingerprints WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM invoice_fingerprints")
    suspend fun clear()
}

internal data class PerceptualProjection(
    val id: Long,
    val perceptualHash: Long
)
