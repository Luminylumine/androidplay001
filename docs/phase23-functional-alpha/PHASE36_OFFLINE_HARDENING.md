# Phase 3.6 Offline Hardening

Decision: `OFFLINE_WORK_COMPLETE_DEVICE_GATES_PENDING`

## Model

- Downloaded: yes, locally under ignored `.local-models/`.
- Exact model: `sherpa-onnx-streaming-paraformer-bilingual-zh-en`.
- Archive: 1,047,319,737 bytes; SHA256 recorded in `PHASE36_MODEL_SANITY.md`.
- Host decode: PASS with real ONNX and official WAV files.
- Host RTF: `0.2012`, explicitly `HOST_RTF_ONLY`.

## Android ASR readiness

- Official Sherpa Android AAR `1.13.7` is downloaded locally under ignored
  `.local-deps/` and included as an optional local dependency.
- `SherpaOnnxTranscriptionProvider` compiles, uses the exact Paraformer files,
  normalizes PCM16 to float, decodes on its own consumer thread, handles endpoint
  reset, and releases recognizer/stream resources.
- `tools/asr/deploy_sherpa_model_to_device.ps1` verifies and deploys model files.
- Android file decode and live mic decode are prepared but require a device.

## PDF

- A small three-page text-layer fixture exists at
  `app/src/androidTest/assets/phase35_fourier_text_fixture.pdf`.
- Host page-count/text-layer verification: PASS, 3 pages.
- Android instrumentation test is present and `assembleDebugAndroidTest` passes.
- AndroidX PDF API 29/31 runtime remains pending.

## Recovery and soak

- Recovery entry: `scripts/run_phase35_recovery_test.ps1`.
- Soak entry: `scripts/run_phase35_soak.ps1 -Minutes 30`.
- Device umbrella: `scripts/run_phase35_all_device_gates.ps1 -SoakMinutes 30`.
- No device was available, so no hardware result is claimed.

## Build

`clean assembleDebug test --no-daemon --max-workers=1` passed. The full offline
test task also passed after dependencies were cached. Runtime minimum remains
API 29; compileSdk 36 plus extension 19 is required by AndroidX PDF beta01.

No model, archive, AAR, WAV, APK, API key, or device output is tracked.

After this offline phase, core backend code is frozen except for device-driven
bug fixes. Next action is either device gates or Phase 4 UI preparation.
