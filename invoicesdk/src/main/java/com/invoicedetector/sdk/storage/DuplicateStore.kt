package com.invoicedetector.sdk.storage

/**
 * Storage-agnostic index of previously seen invoices, used for duplicate detection.
 *
 * The default implementation ([RoomDuplicateStore]) keeps everything on-device in
 * SQLite. Because detection logic depends only on this interface, you can provide a
 * different implementation (e.g. one that talks to a self-hosted "home server" on the
 * local network) without changing the pipeline - the detection itself still runs
 * entirely on the phone.
 */
interface DuplicateStore {

    /** Look up an invoice by its content fingerprint (normalized number+total+...). */
    suspend fun findByContentFingerprint(fingerprint: String): StoredInvoice?

    /** Look up an invoice by an exact (byte-level) image hash. */
    suspend fun findByExactImageHash(hash: String): StoredInvoice?

    /**
     * Returns every stored perceptual hash so the caller can compute Hamming
     * distances. For personal/on-device datasets this linear scan is fine; a
     * server-backed implementation may override with an indexed search.
     */
    suspend fun allPerceptualHashes(): List<PerceptualEntry>

    /**
     * Returns stored records that carry an invoice number and/or total, so the
     * caller can do fuzzy field-based matching (catching a *different photo* of the
     * same invoice, where image hashes differ). Linear scan is fine for personal,
     * on-device datasets; a server-backed store may override with an index.
     *
     * Defaults to empty so existing custom implementations keep compiling; override
     * it to enable fuzzy duplicate detection.
     */
    suspend fun contentCandidates(): List<StoredInvoice> = emptyList()

    /** Fetch a full record by id (used to describe a perceptual-hash match). */
    suspend fun getById(id: Long): StoredInvoice?

    /** Persist a new fingerprint; returns the generated row id. */
    suspend fun insert(fingerprint: InvoiceFingerprint): Long

    /** Remove a record by id; returns true if something was deleted. */
    suspend fun delete(id: Long): Boolean

    /** Remove every record. */
    suspend fun clear()
}

/** A fully stored invoice fingerprint (with id). */
data class StoredInvoice(
    val id: Long,
    val perceptualHash: Long,
    val exactImageHash: String,
    val contentFingerprint: String?,
    val invoiceNumber: String?,
    val total: Double?,
    val dateText: String?,
    val vendor: String?,
    val createdAt: Long
)

/** A new invoice fingerprint to persist (id is assigned by the store). */
data class InvoiceFingerprint(
    val perceptualHash: Long,
    val exactImageHash: String,
    val contentFingerprint: String?,
    val invoiceNumber: String?,
    val total: Double?,
    val dateText: String?,
    val vendor: String?,
    val createdAt: Long = System.currentTimeMillis()
)

/** Lightweight (id, perceptual-hash) pair used for Hamming-distance scans. */
data class PerceptualEntry(
    val id: Long,
    val perceptualHash: Long
)
