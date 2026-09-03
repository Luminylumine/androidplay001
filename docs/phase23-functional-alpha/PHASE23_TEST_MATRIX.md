# Phase 2-3 Test Matrix

| Area | Result | Evidence/command |
| --- | --- | --- |
| Gradle assembleDebug | DONE | `./gradlew.bat assembleDebug --offline` |
| JVM unit tests | DONE | `./gradlew.bat test --offline` |
| Event seq and JSON serialization | DONE | `EventStore`, `EventStore.exportJsonl` |
| Revision/freshness guard | DONE | `FreshnessValidatorTest` |
| Context budget/layers | DONE | `ContextAssemblerTest` |
| Replay fixture ordering | DONE | `ReplayFixtureOrderingTest`, `assets/fixtures/fourier-class.jsonl` |
| FGS manifest/API guards | DONE | `LectureAudioService`, manifest target 35/min 29 |
| AudioFrameBus non-blocking fan-out | DONE | `AudioFrameBusTest` |
| Audio WAV/timestamp runtime | PENDING_HUMAN_FINAL | requires device |
| PDF open/render/page events | PENDING_HUMAN_FINAL | requires a local PDF and device |
| Huawei IME coexistence | PENDING_HUMAN_FINAL | manual device test |
| ADB device smoke | BLOCKED_EXTERNAL | MatePad absent from `adb devices` |
| 20-30 minute soak | BLOCKED_EXTERNAL | depends on ADB device |
| PDF AndroidX text adapter compile | DONE | `AndroidxPdfTextExtractor` |
| Sherpa real local ASR code path | DONE | `SherpaOnnxTranscriptionProvider`, official AAR route |
| Sherpa model/device decode | BLOCKED_EXTERNAL | model transfer and MatePad required |
