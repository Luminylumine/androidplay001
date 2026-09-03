# Phase 2-3 Architecture

`MdClientActivity` is a debug projection only. Course/session state and document
mutations are owned by `MdClientDatabase` and `DocumentService`; event evidence
is appended through `EventStore` and can be exported as JSONL.

`LectureAudioService` is the sole microphone owner. It runs as a microphone
foreground service, writes bounded PCM chunks to `audio.pcm.part`, publishes
frames through the bounded `AudioFrameBus`, emits one timestamp mapping about
every five seconds, and finalizes a WAV on normal stop. Audio service lifecycle
is independent from the Activity. Fake/Replay and Sherpa providers consume the
same provider-neutral PCM contract; the Sherpa model remains a device gate.

`PdfMaterialController` uses SAF plus platform `PdfRenderer` for rendering and
AndroidX PDF `pdf-core`/`pdf-document-service` for page text. It never loads a
whole document into the Agent context. Page text is cached by page index; empty
content remains `needsOcr=true`.

Document blocks use stable IDs and revisions. Human updates and Agent updates
pass through `DocumentService`; Agent actions carry expected revision and are
validated before mutation. Human attention is observational only: Agent code
does not request focus, reset selection, or scroll the Human editor.

Agent context is delta-first and bounded by characters/tokens across L0-L5
layers. Fast and slow controllers use replaceable `AgentBackend` interfaces;
Fake and Replay backends keep the pipeline deterministic without a secret or
network dependency.
