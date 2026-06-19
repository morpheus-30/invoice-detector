# ML Kit text recognition keeps its own consumer rules; nothing extra required here.
# Keep SDK model classes referenced reflectively/serialized in the UI layer.
-keep class com.invoicedetector.sdk.model.** { *; }
