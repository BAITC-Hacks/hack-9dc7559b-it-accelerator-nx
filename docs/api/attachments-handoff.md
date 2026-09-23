# Private attachment handoff (D2)

Implemented with the real D1 JWT `TrustedScope` and current conversation ownership. Originals live in PostgreSQL `bytea`, inserted atomically with metadata and a durable job. All status, source, review and deletion calls check both owner and conversation access. Source responses are downloads with `no-store`. No attachment path calls cart mutation.

## API

- `POST /api/conversations/{id}/attachments`, multipart field `file`, returns 202 `{attachmentId,jobId,version,status,deduplicated}`. Hash dedup is confined to the owner and conversation.
- `GET /api/attachments/capabilities` exposes formats, limits, and configured OCR/vision availability.
- `GET /api/attachments/{id}` returns string revision, status, per-row extraction/provenance, candidates, explicit selections, warnings and error code.
- `POST /api/attachments/{id}/review` accepts `{expectedVersion:"2",selections:[{rowId:"row-1",productId:"1001",quantity:"20",unit:"pcs",warehouse:"MAIN",selected:true}]}`. Use actual candidate IDs and eligible warehouses, not these illustrative values. Conflict is 409; foreign attachment/row/product is 404. The server validates unit, minimum and step against the current catalog. Review merges corrections and increments the revision. While reprocessing is queued/running, the reviewed-items port returns `attachment_not_ready`; after completion the new exact revision is required.
- `POST /api/attachments/{id}/reprocess` accepts `{expectedVersion:"3"}`, returns 202 and a new revision/job. It cancels older in-flight epochs and advances desired extraction version. Stale/foreign requests fail. Existing explicit corrections and their original evidence remain; new OCR rows use content/source-derived IDs so ordinal shifts cannot change what the user selected.
- `GET /api/attachments/{id}/source` returns only the authenticated owner's original.
- `DELETE /api/attachments/{id}` revokes and cleans originals/rows/reviews; repeated deletion by the owner succeeds. Late workers cannot restore it.

`ReviewedAttachmentPort` implements D1 `domain.port.AttachmentPort` outside `test` and `contract`. It returns only explicit selected rows for the requested exact revision, including source coordinates, and revalidates current catalog quantities. Existing contract fakes remain isolated. Review itself is not consent to add to cart.

## Processing

Worker claims and publications are fenced by job epoch, lease, desired version and tombstone. A reclaimed lease invalidates old output. PostgreSQL admission lock bounds the durable queue globally (100) and per principal (20). Three crashed attempts become a named failure. A single job runs per scheduler replica.

Allowlist: XLS/XLSX/DOC/DOCX/PDF/JPG/JPEG. MIME and signatures must agree. Maximum input 10 MiB; ZIP expanded content 30 MiB with entry/ratio limits; 1000 rows, 100 columns, 20 sheets/PDF pages, 20 million pixels. Original filenames never become storage paths. POI reads cached formulas with warnings, does not evaluate formulas or external links, and rejects VBA packages. Prices/totals do not become quantities; unknown quantity stays null; leading-zero articles and decimal commas are preserved. PDF pages without usable text go to OCR. Photo recognition is always reviewable. OCR TSV produces per-word pixel boxes, dimensions, line IDs and raw OCR confidence; rotated input boxes map back to the original image frame. PDF boxes use the bounded 144-DPI rendered page frame. A word belongs to at most one row, so provenance size remains bounded. OCR confidence is never a catalog-match probability.

Tesseract executes directly through ProcessBuilder with configured executable/languages, no shell, bounded subprocess time, discarded process output and cleanup of private temporary files. The optional OpenAI adapter receives a metadata-stripped image at most 1024px after OCR did not yield usable text; it requests visible evidence only and uses an output cap and a reactive deadline. It exposes structured visible attributes/category/quality flags, never verified compatibility. Disabled providers expose explicit `OCR_UNAVAILABLE` / `VISION_UNAVAILABLE` warnings.

Shared configuration required: POI `poi-ooxml`/`poi-scratchpad` 5.4.1, PDFBox 3.0.5; `app.attachments.worker-enabled`, `app.attachments.ocr.enabled`, `.ocr.executable`, `.ocr.languages`, `.vision.enabled`; multipart request size at least 10 MiB plus envelope. Root combines `db/drafts/attachments.sql` in reserved V5. Container must package Tesseract with requested language data. Existing ChatModel config supplies the vision model and must be vision-capable.

## Verification and remaining gates

Focused tests use actual XLS/XLSX/DOC/DOCX/PDF/JPEG fixtures and real D1 JWT sessions with isolated PostgreSQL/Redis Testcontainers. They cover cross-user HTTP denial, durable upload/dedup, stale review CAS, selected-only port, invalid quantity step, hard electrical rating conflict, expired-worker fencing, delete-before-publication, corrupt/signature/size rejection and cached formula handling. OCR fixture tests execute the installed Tesseract runtime when present; absent runtime is explicitly skipped and is not a quality pass.

External OpenAI vision quality, container-only OCR packaging, general real-world document/photo accuracy and throughput remain separate acceptance gates. Parser deadlines are cooperative between library operations; hard JVM memory/CPU isolation for malicious parser inputs requires an isolated worker process/container. Rotation fallback is heuristic; unknown/blurry observations require review. Stored review rows and reviewed original evidence remain separate from subsequent extractions; a late worker cannot replace them.


Follow-up verification: 22 attachment tests cover real OCR regions/rotation, explicit no-text→vision dispatch, HTTP 415, failed-job retry, stale/foreign reprocess denial, replacement of an in-flight desired version, review retention after reload, and changed-OCR evidence preservation. These tests do not claim external provider photo accuracy. The task text requires bounded work and memory, but does not mandate a subprocess for POI/PDFBox; current byte/ZIP/page/row/text/pixel limits and cooperative parser deadline are concrete bounds, not hard protection against a library operation that ignores interruption. Separate process/container isolation remains an explicit production hardening limit, not an implemented feature.


Live-evidence correction: a wholly blurred fixture caused a provider to invent markings, so the photo path now rejects near-absent detail before OCR/vision. A bounded grayscale Laplacian-variance gate at max 1024px returns empty rows plus `IMAGE_BLURRED_RETAKE`; the unreadable blurred fixture is below the conservative threshold and sharp product-only fixture remains eligible for vision. This heuristic is not a general image-quality guarantee. Model-only `rawText` is never parsed into article/quantity/unit; visual candidates retain `VISION_OBSERVATIONS_UNVERIFIED`. Regression tests explicitly return invented SKU/quantity from a fake provider and verify those extracted fields stay null.
