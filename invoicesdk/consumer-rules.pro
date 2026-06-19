# Keep the public SDK surface so consumers' R8/ProGuard builds don't strip it.
-keep public class com.invoicedetector.sdk.InvoiceDetector { *; }
-keep public class com.invoicedetector.sdk.InvoiceDetectorConfig { *; }
-keep public class com.invoicedetector.sdk.model.** { *; }

# Room generated classes.
-keep class * extends androidx.room.RoomDatabase { *; }
