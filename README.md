# Invoice Detector (Android, Kotlin) — on-device SDK + demo app

A fully **offline** invoice scanner. Hand it a photo and it tells you whether the
image **is an invoice or not** (and discards it if not), whether it's blurry, and
whether it's a **duplicate** of one you've already captured — then extracts the key
fields. **No data ever leaves the device** (no external API calls).

Built to run on **old / low-end Android phones** (`minSdk 21`, Android 5.0+), with no
native OpenCV dependency and a small footprint.

---

## Why these design choices

| Requirement | How it's met |
|---|---|
| No external API | 100% on-device. Storage sits behind a `DuplicateStore` interface so you can *optionally* point it at a self-hosted home server later — detection still runs on the phone. |
| "Use models from the internet, not my custom one" | OCR uses **Google ML Kit Text Recognition v2**, a bundled, offline, pre-trained model. Invoice detection uses transparent, debuggable heuristics instead of a fragile custom model. |
| Runs on old phones | `minSdk 21`, pure-Kotlin image math, memory-safe bitmap decoding, cheapest checks run first. |
| Blurry → discard & retake | Laplacian-variance focus score; below threshold returns `Rejected.Blurry`. |
| Invoice or not → say so | Multilingual, receipt-aware classifier; if it isn't an invoice it returns `Rejected.NotAnInvoice` and the image is discarded. |
| Detect & cancel duplicates | Perceptual image hash (dHash + Hamming distance) **and** a content fingerprint of the OCR'd fields; returns `Rejected.Duplicate`. |
| Precise data extraction | Layout-aware parser pairs labels with values using OCR geometry, tuned for European invoices. |

---

## Project layout

```
InvoiceDetector/
├── invoicesdk/                     # The reusable SDK (Android library / .aar)
│   └── src/main/java/com/invoicedetector/sdk/
│       ├── InvoiceDetector.kt          # Public entry point (facade)
│       ├── InvoiceDetectorConfig.kt    # Tunable thresholds
│       ├── model/                      # InvoiceResult, reports, ExtractedInvoice…
│       ├── quality/BlurDetector.kt     # Laplacian-variance focus gate
│       ├── extract/                    # ML Kit OCR + layout-aware field parsing
│       ├── classify/InvoiceClassifier  # "Is this an invoice?" scorer
│       ├── text/InvoiceLexicon         # Multilingual keyword sets (EU languages)
│       ├── dedupe/                     # Perceptual + content hashing, DuplicateChecker
│       ├── storage/                    # DuplicateStore interface + Room implementation
│       └── pipeline/InvoicePipeline.kt # Orchestrates the stages
└── app/                            # Demo app that consumes the SDK
```

---

## Processing pipeline

Stages run cheapest-first so weak devices bail out early:

1. **Blur gate** — out-of-focus → `Rejected.Blurry` (ask user to retake)
2. **OCR** — ML Kit text recognition (auto-orientation); no text → `Rejected.Unreadable`
3. **Invoice classification** — not an invoice → `Rejected.NotAnInvoice` (discarded)
4. **Field extraction** — layout-aware parsing of vendor / number / dates / totals / VAT
5. **Duplicate check** — seen before → `Rejected.Duplicate` (cancel)
6. **Accept** — store fingerprint for future duplicate detection → `Accepted`

---

## Using the SDK

```kotlin
val detector = InvoiceDetector.create(context)            // uses on-device Room store

when (val result = detector.process(imageUri)) {          // suspend fun
    is InvoiceResult.Accepted ->
        save(result.invoice)                              // recordId, fields, scores

    is InvoiceResult.Rejected.Blurry ->
        askUserToRetake(result.message)

    is InvoiceResult.Rejected.Duplicate ->
        cancel(result.match)                              // existingRecordId, matchType

    is InvoiceResult.Rejected.NotAnInvoice ->
        discardNotAnInvoice(result.message)               // confidence in result.classification

    is InvoiceResult.Rejected.Unreadable,
    is InvoiceResult.Rejected.Error ->
        showError(result.message)
}

detector.close()                                          // releases the OCR engine
```

Tune behaviour with `InvoiceDetectorConfig` (e.g. `blurThreshold`,
`classificationThreshold`, `perceptualHashHammingThreshold`,
`autoDetectOrientation`). Every result also carries a `recommendedAction`
(`ACCEPT`, `REQUEST_NEW_IMAGE`, `CANCEL_DUPLICATE`, `REJECT`).

### Optional home-server backend

The duplicate index is reached only through the `DuplicateStore` interface. Implement
it against your own LAN/home server and pass it in — detection itself still runs on the
phone:

```kotlin
val detector = InvoiceDetector.create(context, config, MyHomeServerStore(...))
```

---

## Building

Requirements: **JDK 17 or 21** (the project compiles to Java 8 bytecode for old
devices), the **Android SDK** (platform 34, build-tools 34.0.0), and the included
Gradle wrapper.

1. Create `local.properties` in the project root pointing at your Android SDK:
   ```properties
   sdk.dir=/absolute/path/to/Android/sdk
   ```
   (Android Studio creates this for you automatically.)

2. Build:
   ```bash
   ./gradlew :invoicesdk:assembleDebug     # builds the .aar
   ./gradlew :app:assembleDebug            # builds the demo APK
   ./gradlew :app:assembleRelease          # minified release APK
   ```

   Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

The easiest path is to open the project in **Android Studio**, let it sync, then Run
the `app` configuration on a device/emulator.

> Note: this repo was scaffolded and compiled in a headless environment. All three
> variants above (`:invoicesdk:assembleDebug`, `:app:assembleDebug`,
> `:app:assembleRelease`) build successfully.

---

## Photographed receipts, rotation & languages

Tuned for real **phone-camera photos** (not just clean scans), similar in spirit to
services like Veryfi:

- **Any rotation** — if the upright OCR pass looks weak, the pipeline retries the
  image at 90/180/270 and keeps the best result (ML Kit rotates internally, so no
  extra bitmap allocations). EXIF orientation is also applied on load.
- **Receipts, not just formal invoices** — the classifier scores on *structure*
  (a total, several money amounts, a tax line, a date, currency, qty/price columns),
  so a photographed shop receipt passes even when it never says the word "invoice".
- **European languages** — OCR uses ML Kit's Latin-script model, and the classifier +
  field parser understand keywords across EN/FR/DE/ES/IT/NL/PT/PL/SE/DK/NO/FI
  (e.g. `facture`/`rechnung`/`factura`, `total`/`gesamt`/`totale`,
  `tva`/`mwst`/`iva`/`btw`/`moms`). See `text/InvoiceLexicon.kt`.

## Limitations & next steps

- **Field extraction** is layout-aware and tuned for European invoices, but still
  heuristic — extend `InvoiceFieldParser` / `text/InvoiceLexicon.kt` for your formats.
- The **perceptual duplicate scan** is a linear comparison — fine for personal,
  on-device datasets; for very large indexes use an indexed/BK-tree store.
- The **fake / altered-invoice check was removed** for now (it was over-flagging).
  The pipeline focuses on invoice-or-not detection, duplicates, and extraction.
- OCR is **Latin script** (covers the listed European languages). Add other ML Kit
  script models (e.g. Cyrillic, Devanagari) if you need non-Latin invoices.
```
