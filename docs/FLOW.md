# How it works — processing flow

End-to-end, everything below runs **on the device** (no network calls).

## 1. Detection pipeline

```mermaid
flowchart TD
    A([User selects / captures a photo]) --> B[InvoiceDetector.process]
    B --> C[Decode bitmap<br/>apply EXIF orientation + downscale]
    C --> D{Sharp enough?<br/>Laplacian variance &ge; threshold}
    D -- No --> R1([Rejected: Blurry<br/>ask user to retake]):::warn
    D -- Yes --> E[OCR with ML Kit<br/>auto-orientation: try 0/90/180/270]
    E --> F{Enough readable text?}
    F -- No --> R2([Rejected: Unreadable<br/>ask user to retake]):::warn
    F -- Yes --> G[Parse fields<br/>layout-aware + multilingual]
    G --> H{Is it an invoice?<br/>classifier score &ge; threshold}
    H -- No --> R3([Rejected: NotAnInvoice<br/>discard & say so]):::err
    H -- Yes --> I[Build fingerprint<br/>perceptual dHash + content hash]
    I --> J{Seen this invoice before?<br/>check duplicate store}
    J -- Yes --> R4([Rejected: Duplicate<br/>cancel]):::warn
    J -- No --> K[(Save fingerprint to Room)]
    K --> L([Accepted<br/>+ extracted fields]):::ok

    classDef ok fill:#2E7D32,color:#fff,stroke:#1B5E20;
    classDef warn fill:#EF6C00,color:#fff,stroke:#E65100;
    classDef err fill:#C62828,color:#fff,stroke:#B71C1C;
```

Stages run **cheapest-first** so weak phones bail out early (blur check before OCR,
OCR before hashing, etc.).

## 2. Field extraction (how a value is found)

For each label (e.g. "Total", "TVA", "Gesamtbetrag") the parser uses OCR geometry to
locate the matching amount:

```mermaid
flowchart LR
    L[Label line found<br/>e.g. &quot;Total&quot;] --> S1{Amount on the<br/>same text line?}
    S1 -- Yes --> V([Use it]):::ok
    S1 -- No --> S2{Amount on the<br/>same row, to the right?}
    S2 -- Yes --> V
    S2 -- No --> S3{Amount on the<br/>next row down?}
    S3 -- Yes --> V
    S3 -- No --> S4([Fall back:<br/>largest amount near a currency symbol]):::warn

    classDef ok fill:#2E7D32,color:#fff;
    classDef warn fill:#EF6C00,color:#fff;
```

## 3. Components

```mermaid
flowchart TD
    subgraph App[Demo app]
        UI[MainActivity + verdict card]
        VM[MainViewModel]
    end
    subgraph SDK[invoicesdk]
        F[InvoiceDetector facade]
        P[InvoicePipeline]
        BL[BitmapLoader]
        BD[BlurDetector]
        TE[TextExtractor - ML Kit OCR]
        FP[InvoiceFieldParser]
        CL[InvoiceClassifier]
        LX[InvoiceLexicon - EU languages]
        DC[DuplicateChecker<br/>dHash + content hash]
        ST[(Room DuplicateStore)]
    end

    UI --> VM --> F --> P
    P --> BL & BD & TE & FP & CL & DC
    FP --> LX
    CL --> LX
    DC --> ST
```


## 4. Sequence (async / coroutine view)

Shows the round-trips over time, including the ML Kit OCR callback and the
auto-orientation retries. `process()` is a `suspend` function; the heavy work runs on
`Dispatchers.Default`, so the UI thread is never blocked.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant UI as MainActivity
    participant VM as MainViewModel
    participant Det as InvoiceDetector
    participant Pipe as InvoicePipeline
    participant OCR as ML Kit (TextExtractor)
    participant Store as Room DuplicateStore

    User->>UI: pick / capture photo
    UI->>VM: process(uri)
    VM->>Det: process(uri)  (suspend)
    Det->>Det: decode bitmap + EXIF rotate + downscale
    Det->>Pipe: run(bitmap)  [Dispatchers.Default]

    Pipe->>Pipe: blur check (Laplacian variance)
    alt too blurry
        Pipe-->>VM: Rejected.Blurry
        VM-->>UI: render "Too blurry"
    else sharp
        Pipe->>OCR: extract(bitmap, 0°)
        OCR-->>Pipe: text @ 0°
        opt text looks weak (< confidentTextLength)
            Pipe->>OCR: extract @ 90° / 180° / 270°
            OCR-->>Pipe: best-of rotations
        end

        alt not enough text
            Pipe-->>VM: Rejected.Unreadable
        else
            Pipe->>Pipe: parse fields + classify
            alt not an invoice
                Pipe-->>VM: Rejected.NotAnInvoice (discard)
            else is an invoice
                Pipe->>Pipe: fingerprint (dHash + content hash)
                Pipe->>Store: find duplicate?
                Store-->>Pipe: match? / none
                alt duplicate
                    Pipe-->>VM: Rejected.Duplicate (cancel)
                else new
                    Pipe->>Store: insert fingerprint
                    Store-->>Pipe: recordId
                    Pipe-->>VM: Accepted + fields
                end
            end
        end
        VM-->>UI: render verdict card
    end
    UI-->>User: show result
```

## 5. Decision thresholds (from `InvoiceDetectorConfig`)

Each decision point in the pipeline maps to a tunable config value:

| Stage / decision | Config field | Default | Meaning |
|---|---|---|---|
| Pre-OCR downscale | `ocrMaxDim` | `1024` px | Longest edge before OCR (memory-safe on old phones). |
| Blur downscale | `blurAnalysisMaxDim` | `600` px | Longest edge for the focus computation. |
| **Sharp enough?** | `blurThreshold` | `90.0` | Min Laplacian variance; below it → `Blurry`. Lower if valid photos get rejected. |
| Orientation retry | `autoDetectOrientation` | `true` | Retry OCR at 90/180/270 when upright text is weak. |
| Skip-retry shortcut | `confidentTextLength` | `40` chars | If upright OCR already yields this much text, don't try other rotations. |
| **Enough text?** | `minTextLength` | `12` chars | Below it → `Unreadable`. |
| **Is it an invoice?** | `classificationThreshold` | `0.45` (0–1) | Min classifier score; below it → `NotAnInvoice`. Raise to be stricter. |
| **Duplicate?** | `perceptualHashHammingThreshold` | `10` (of 64 bits) | Max hash distance to treat two photos as the same invoice. Lower = stricter. |

Override any of them via the builder, e.g.:

```kotlin
val config = InvoiceDetectorConfig.Builder()
    .blurThreshold(70.0)               // accept slightly softer photos
    .classificationThreshold(0.40f)    // be a bit more lenient about "is it an invoice"
    .perceptualHashHammingThreshold(8) // stricter duplicate matching
    .build()

val detector = InvoiceDetector.create(context, config)
```


## 6. Duplicate detection strategy

A second *photo* of the same invoice has a different image hash and usually a
different perceptual hash, so image-based checks alone miss it. Duplicate detection
therefore tries four signals in order and stops at the first hit:

```mermaid
flowchart TD
    A[New invoice fingerprint] --> B{Exact content fingerprint<br/>number+total hash matches?}
    B -- Yes --> D([Duplicate: CONTENT_FINGERPRINT]):::warn
    B -- No --> C{Fuzzy field match?<br/>number ~matches + total close}
    C -- Yes --> E([Duplicate: CONTENT_FUZZY<br/>another photo of same bill]):::warn
    C -- No --> T{OCR text overlap high?<br/>shared tokens / numbers}
    T -- Yes --> TE([Duplicate: TEXT_SIMILARITY<br/>survives cropping / re-shooting]):::warn
    T -- No --> F{Exact image hash matches?}
    F -- Yes --> G([Duplicate: EXACT_IMAGE]):::warn
    F -- No --> H{Perceptual hash within<br/>Hamming threshold?}
    H -- Yes --> I([Duplicate: PERCEPTUAL_IMAGE]):::warn
    H -- No --> J([New invoice - store it]):::ok

    classDef ok fill:#2E7D32,color:#fff;
    classDef warn fill:#EF6C00,color:#fff;
```

**Why text overlap matters (cropping):** a crop changes every pixel, so the image
and perceptual hashes differ, and OCR may not even extract the same fields. But the
crop's text is a *subset* of the original's. We compare OCR **token sets** with the
overlap coefficient `|A ∩ B| / min(|A|, |B|)`, which stays near 1.0 when one is a
subset of the other. To avoid matching two different invoices from the same vendor
(shared boilerplate), the decision is anchored on overlap of **numeric** tokens
(amounts, dates, invoice numbers), which genuinely differ between invoices.

**Why fuzzy field matching matters:** the invoice number and total are the same
across two photos of one bill. The fuzzy step compares the *extracted fields* with
tolerance — invoice numbers by edit-distance (so `INV-001` still matches `INV-0O1`
from an OCR slip), confirmed by the total within ~1%, or by total + date + vendor
when no number was read.
