package com.invoicedetector.sdk.storage

import android.content.Context
import androidx.room.Room
import com.invoicedetector.sdk.storage.room.InvoiceDao
import com.invoicedetector.sdk.storage.room.InvoiceDatabase
import com.invoicedetector.sdk.storage.room.InvoiceEntity

/**
 * Default on-device [DuplicateStore] backed by Room/SQLite. All data stays in the
 * host app's private storage; nothing leaves the device.
 */
class RoomDuplicateStore private constructor(
    private val dao: InvoiceDao
) : DuplicateStore {

    override suspend fun findByContentFingerprint(fingerprint: String): StoredInvoice? =
        dao.findByContentFingerprint(fingerprint)?.toStored()

    override suspend fun findByExactImageHash(hash: String): StoredInvoice? =
        dao.findByExactImageHash(hash)?.toStored()

    override suspend fun allPerceptualHashes(): List<PerceptualEntry> =
        dao.allPerceptualHashes().map { PerceptualEntry(it.id, it.perceptualHash) }

    override suspend fun getById(id: Long): StoredInvoice? =
        dao.getById(id)?.toStored()

    override suspend fun contentCandidates(): List<StoredInvoice> =
        dao.contentCandidates().map { it.toStored() }

    override suspend fun insert(fingerprint: InvoiceFingerprint): Long =
        dao.insert(fingerprint.toEntity())

    override suspend fun delete(id: Long): Boolean = dao.deleteById(id) > 0

    override suspend fun clear() = dao.clear()

    companion object {
        @JvmStatic
        fun create(context: Context): RoomDuplicateStore {
            val db = Room.databaseBuilder(
                context.applicationContext,
                InvoiceDatabase::class.java,
                "invoice_detector.db"
            ).fallbackToDestructiveMigration().build()
            return RoomDuplicateStore(db.invoiceDao())
        }
    }
}

private fun InvoiceEntity.toStored() = StoredInvoice(
    id = id,
    perceptualHash = perceptualHash,
    exactImageHash = exactImageHash,
    contentFingerprint = contentFingerprint,
    invoiceNumber = invoiceNumber,
    total = total,
    dateText = dateText,
    vendor = vendor,
    createdAt = createdAt
)

private fun InvoiceFingerprint.toEntity() = InvoiceEntity(
    perceptualHash = perceptualHash,
    exactImageHash = exactImageHash,
    contentFingerprint = contentFingerprint,
    invoiceNumber = invoiceNumber,
    total = total,
    dateText = dateText,
    vendor = vendor,
    createdAt = createdAt
)
