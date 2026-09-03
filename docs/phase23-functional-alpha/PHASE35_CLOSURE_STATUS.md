# Phase 3.5 Closure Status

Status: NOT_READY_FOR_UI

Offline closure work completed on 2026-09-03. Device-only gates are intentionally
open because the MatePad is unavailable today.

## Closed in code

- AndroidX PDF `pdf-core` and `pdf-document-service` beta01 integration.
- Bounded `AudioFrameBus` with non-blocking fan-out from the sole AudioRecord owner.
- Real Sherpa-ONNX provider wiring for PCM, partial/final events, endpoint reset,
  and explicit model-load/decode errors.
- Sherpa AAR/model downloader with local-only model and dependency directories.
- Incomplete `.pcm.part` detection and interrupted session marking on app startup.
- Phase 3.5 device smoke script with automatic single-device selection.
- Machine-readable replay trace covering page, transcript, human edit, fast/slow
  agent, and document update ordering.
- AudioFrameBus JVM test and complete Gradle build/test verification.

## Open gates

- Real Sherpa model must be downloaded and decoded on the target arm64 device.
- PDF text-layer extraction and current-page context integration need a real PDF
  and runtime verification on API 29/31 hardware.
- Activity/process recovery and 20-30 minute audio soak need hardware.

Compile SDK is 36 only because AndroidX PDF beta01 requires it. Runtime minimum
remains API 29 and target SDK remains 35.
