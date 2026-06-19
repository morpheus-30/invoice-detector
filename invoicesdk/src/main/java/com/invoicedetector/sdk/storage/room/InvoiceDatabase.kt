package com.invoicedetector.sdk.storage.room

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [InvoiceEntity::class],
    version = 2,
    exportSchema = false
)
internal abstract class InvoiceDatabase : RoomDatabase() {
    abstract fun invoiceDao(): InvoiceDao
}
