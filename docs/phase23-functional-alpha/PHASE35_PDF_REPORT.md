# Phase 3.5 PDF Report

- Render fallback: platform `PdfRenderer`.
- Text backend: AndroidX PDF `pdf-core:1.0.0-beta01` plus
  `pdf-document-service:1.0.0-beta01`.
- Adapter: `AndroidxPdfTextExtractor` using `SandboxedPdfLoader`.
- Cache: `PdfMaterialController` caches text by zero-based page index and clears
  the cache when a document changes.
- Empty/null page content remains `needsOcr=true`.

The implementation compiles, but no runtime page-count or extracted-text result is
claimed today. The required three-page Fourier PDF fixture and API 29/31 runtime
check remain tomorrow's device task.
