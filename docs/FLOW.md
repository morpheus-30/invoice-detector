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
