# Invoice Detector (Android, Kotlin) — on-device SDK + demo app

A fully **offline** invoice scanner. Hand it a photo and it decides whether the image
is a real invoice, whether it's blurry, whether it's a **duplicate** of one you've
already captured, and whether it looks like a **fake / altered** invoice — then
extracts the key fields. **No data ever leaves the device** (no external API calls).

Built to run on **old / low-end Android phones** (`minSdk 21`, Android 5.0+), with no
native OpenCV dependency and a small footprint.

---

## Why these design choices

| Requirement | How it's met |
|---|---|
| No external API | 100% on-device. Storage sits behind a `DuplicateStore` interface so you can *optionally* point it at a self-hosted home server later — detection still runs on the phone. |
| "Use models from the internet, not my custom one" | OCR uses **Google ML Kit Text Recognition v2**, a bundled, offline, pre-trained model. Invoice/fake detection use transparent, debuggable heuristics instead of a fragile custom model. |
| Runs on old phones | `minSdk 21`, pure-Kotlin image math, memory-safe bitmap decoding, cheapest checks run first. |
| Blurry → discard & retake | Laplacian-variance focus score; below threshold returns `Rejected.Blurry`. |
| Detect & cancel duplicates | Perceptual image hash (dHash + Hamming distance) **and** a content fingerprint of the OCR'd fields; returns `Rejected.Duplicate`. |
| Detect false invoices | Arithmetic check (subtotal + tax = total), required-field and date-sanity checks, optional image-tampering (ELA) signal; returns `Rejected.SuspectedFake`. |
| Data extraction (later) | Best-effort field parser included now; treated as secondary. |

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
│       ├── extract/                    # ML Kit OCR + field/amount parsing
│       ├── classify/InvoiceClassifier  # "Is this an invoice?" scorer
│       ├── authenticity/               # Fake/altered-invoice checks (+ optional ELA)
│       ├── dedupe/                     # Perceptual + content hashing, DuplicateChecker
│       ├── storage/                    # DuplicateStore interface + Room implementation
│       └── pipeline/InvoicePipeline.kt # Orchestrates the stages
└── app/                            # Demo app that consumes the SDK
```

---

## Processing pipeline

Stages run cheapest-first so weak devices bail out early:

1. **Blur gate** — out-of-focus → `Rejected.Blurry` (ask user to retake)
2. **OCR** — ML Kit text recognition; no text → `Rejected.Unreadable`
3. **Invoice classification** — not an invoice → `Rejected.NotAnInvoice`
4. **Duplicate check** — seen before → `Rejected.Duplicate` (cancel)
5. **Authenticity check** — looks fake → `Rejected.SuspectedFake` (manual review)
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

    is InvoiceResult.Rejected.SuspectedFake ->
        routeForReview(result.authenticity.flags)

    is InvoiceResult.Rejected.NotAnInvoice,
    is InvoiceResult.Rejected.Unreadable,
    is InvoiceResult.Rejected.Error ->
        showError(result.message)
}

detector.close()                                          // releases the OCR engine
```

Tune behaviour with `InvoiceDetectorConfig` (e.g. `blurThreshold`,
`perceptualHashHammingThreshold`, `authenticityThreshold`,
`enableImageTamperingCheck`). Every result also carries a `recommendedAction`
(`ACCEPT`, `REQUEST_NEW_IMAGE`, `CANCEL_DUPLICATE`, `REVIEW_MANUALLY`, `REJECT`).

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

- **Field extraction** is heuristic/best-effort by design (the stated priority was
  detection/duplicate/fake). Improve `InvoiceFieldParser` for your invoice formats.
- The **perceptual duplicate scan** is a linear comparison — fine for personal,
  on-device datasets; for very large indexes use an indexed/BK-tree store.
- **Image-tampering (ELA)** is a heuristic signal, off by default; it contributes to
  the authenticity score rather than hard-failing on its own.
- OCR is **Latin script** (covers the listed European languages). Add other ML Kit
  script models (e.g. Cyrillic, Devanagari) if you need non-Latin invoices.
```
