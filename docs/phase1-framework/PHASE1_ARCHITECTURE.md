# Phase 1 Architecture

`LectureActivity` owns the traditional landscape View shell. `DocumentModel` owns five stable blocks; native `EditText` values are authoritative. Human edits and attention changes are sent to the event store. `FakeLectureAgent` emits delayed suggestions with a captured target revision, and stale agent writes are rejected by `DocumentModel.agentUpdate`. The agent updates a non-focused historical block without requesting focus or scrolling.

`EventStore` writes one JSON object per line to app-private `files/sessions/<id>/events.jsonl`. Its synchronized append method assigns `seq` and records event, arrival, and wall-clock timestamps. `SessionClock` uses `SystemClock.elapsedRealtimeNanos()` for the shared monotonic basis. `AudioCapture` runs `AudioRecord` on a worker thread and records lifecycle, errors, frames, audio timestamp, elapsed timestamp, and offset mapping. Transcription providers are replaceable through `TranscriptionProvider`; Phase 1 uses Fake and includes a JSONL Replay implementation.

The material panel is a page fixture/placeholder rather than a PDF renderer. Markdown export writes the current block model to the app-private session directory. No Gradle, Compose, focus requests, or automatic scrolling are used.
