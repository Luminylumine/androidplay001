# Phase 2-3 Functional Alpha Status

Status: PARTIAL

The mdclient branch now has a standard Gradle single-app project (`AGP 8.5.2`,
Kotlin plugin `2.0.10`, compile/target 35, min 29) and a functional Java/View
debug shell. The original Phase 1 handoff remains historical evidence and was
not regenerated.

## DONE

- Standard Gradle wrapper and CLI build.
- SQLite persistence schema for courses, sessions, events, blocks, transcripts,
  agent actions/suggestions, lecture logic, materials, diagnostics, unresolved
  items, and evidence links.
- Synchronized append-only EventStore with monotonic event/arrival timestamps
  and JSONL export.
- Foreground microphone service with one AudioRecord owner, PCM16 audio part
  file, WAV finalization, bounded PCM queue, timestamp offset events, and errors.
- Native PdfRenderer material controller with SAF URI persistence, page controls,
  bitmap rendering, and explicit `needsOcr` behavior when no text extractor is
  available.
- Fake/Replay agent and transcript primitives, stable document blocks, revision
  guard, freshness validator, context budget, FTS5/LIKE search, Markdown import/
  export, and deterministic Fourier fixture.
- Debug UI integration for session, audio service, PDF, native blocks, fake
  transcript/agent, Markdown, JSONL export, and Android 13 notification guard.
- JVM unit tests pass.

## PENDING_HUMAN_FINAL

- Huawei Chinese IME composition, selection, deletion, and multi-minute focus
  stability.
- Subjective PDF zoom/pan and keyboard ergonomics.

## BLOCKED_EXTERNAL

- Device smoke and soak are prepared but the MatePad was not present in ADB at
  the last attempt.
- Sherpa-ONNX independent local streaming ASR cannot be verified because this
  workspace has no native binding or model; no fake result is presented as real.

## NOT_STARTED

- Full PDF text extraction for text-layer PDFs (current platform renderer does
  not expose page text).
- Activity-recreate/process-death recovery validation on hardware.
