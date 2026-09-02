# Phase 1 Framework Result

## A. Build

- Branch: `feature/lecture-notebook-framework`
- Commit: working tree pending final commit
- Application ID: `com.akasha.app`
- `minSdk`: 29; `targetSdk`: 29
- Baseline: Java 8 source, hand-built aapt2/javac/d8; no Gradle or Compose dependency
- APK: `projects/akasha_android/app/build/Akasha-v1.apk`

## B. Device

- MatePad install: PASS
- `LectureActivity` launch: PASS
- Current IME: Huawei LatinIME; app did not change it
- Audio permission: PASS for smoke test
- Audio capture: PASS; AudioRecord and timestamp samples observed

## C. Architecture

| Component | Status |
| --- | --- |
| SessionClock | DONE |
| EventBus/EventStore | PARTIAL: synchronized append-only JSONL store; no Room/Flow bus yet |
| AudioCapture | DONE for foreground smoke path; foreground service/long-run recovery remains |
| TranscriptionProvider | DONE: Fake and Replay interfaces |
| DocumentModel | DONE: five stable blocks, revisions, Markdown export |
| HumanAttention | PARTIAL: focus/edit events; no pixel viewport model |
| AgentAttention | PARTIAL: revision-aware fake action; no separate UI state object yet |
| FakeAgent | DONE: delayed suggestion and historical block mutation |
| PDF/material | PARTIAL: page fixture placeholder, no PDF renderer |

## D. Human Input

Automated ADB input changed native block 5 while the delayed Agent updated block 1. The event log shows `HUMAN_ATTENTION block=4`, `HUMAN_EDIT block=4`, followed by Agent action events; no focus or scroll API is used. Huawei Chinese composition, selection, cursor movement, deletion, and 3-minute manual stability remain NOT_ATTEMPTED.

## E. Timeline

`raw/events-device-smoke-final.jsonl` contains one monotonic `seq` stream combining `SESSION_STARTED`, `AUDIO_STARTED`, `TRANSCRIPT_PARTIAL`, `TRANSCRIPT_FINAL`, `AUDIO_TIMESTAMP`, `HUMAN_ATTENTION`, `HUMAN_EDIT`, `AGENT_SUGGESTION`, `AGENT_ACTION_APPLIED`, and `AUDIO_STOPPED`. `raw/events-device-smoke-clock-aligned.jsonl` confirms the corrected shared Android elapsed-realtime clock for audio mappings.

## F. Audio Drift

Smoke duration: approximately 4 seconds; 2 timestamp samples; offsets were recorded. 20-30 minute statistics: NOT_ATTEMPTED. No discontinuity was observed in the short run.

## G. Voice Route

- App-owned AudioRecord: VERIFIED
- Sherpa independent ASR: NOT_ATTEMPTED
- Huawei hidden voice experiment: NOT_ATTEMPTED / NOT CORE

## H. Risks

1. Huawei IME composition stability has not had the required manual test.
2. Long-running AudioRecord lifecycle and drift remain unverified.
3. Material support is a page placeholder, not a PDF viewer.
4. Replay fixtures are caller-supplied and not packaged.
5. EventStore has no recovery/rotation policy beyond append-only files.

## I. Next Step

Phase 2 should implement a foreground `AudioCapture` service with a packaged Replay fixture and run the manual Huawei IME stability test against the existing shell.
