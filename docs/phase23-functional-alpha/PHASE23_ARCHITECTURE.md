# Phase 2-3 Architecture

`MdClientActivity` is a debug projection only. Course/session state and document
mutations are owned by `MdClientDatabase` and `DocumentService`; event evidence
is appended through `EventStore` and can be exported as JSONL.

`LectureAudioService` is the sole microphone owner. It runs as a microphone
foreground service, writes bounded PCM chunks to `audio.pcm.part`, emits one
timestamp mapping about every five seconds, and finalizes a WAV on normal stop.
Audio service lifecycle is independent from the Activity. ASR providers are
expected to consume future AudioFrameBus data; the current production-safe
fallback is Fake/Replay because no sherpa model/binding is present.

`PdfMaterialController` uses SAF plus platform `PdfRenderer` and never loads a
whole document into the Agent context. Page changes are callback-backed. Text
extraction currently returns empty with `needsOcr=true`, preventing an unsafe
claim that scanned or text-layer extraction is available.

Document blocks use stable IDs and revisions. Human updates and Agent updates
pass through `DocumentService`; Agent actions carry expected revision and are
validated before mutation. Human attention is observational only: Agent code
does not request focus, reset selection, or scroll the Human editor.

Agent context is delta-first and bounded by characters/tokens across L0-L5
layers. Fast and slow controllers use replaceable `AgentBackend` interfaces;
Fake and Replay backends keep the pipeline deterministic without a secret or
network dependency.
