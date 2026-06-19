package com.invoicedetector.sdk.storage.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room row for a stored invoice fingerprint.
 *
 * Indices on the hash/fingerprint columns make the exact-match and content-match
 * lookups fast even with thousands of stored invoices.
 */
@Entity(
    tableName = "invoice_fingerprints",
    indices = [
        Index(value = ["contentFingerprint"]),
        Index(value = ["exactImageHash"])
    ]
)
internal data class InvoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val perceptualHash: Long,
    val exactImageHash: String,
    val contentFingerprint: String?,
    val invoiceNumber: String?,
    val total: Double?,
    val dateText: String?,
    val vendor: String?,
    val createdAt: Long
)
