# Phase 3.5 ASR Report

## Implementation

- Binding: official Sherpa-ONNX Android AAR `1.13.7`.
- Provider: `SherpaOnnxTranscriptionProvider`.
- Model route: official bilingual streaming Paraformer.
- PCM contract: 16 kHz, mono, signed PCM16 converted to float samples.
- Ownership: `LectureAudioService` remains the only AudioRecord owner;
  `AudioFrameBus` publishes frames to optional consumers.
- Backpressure: bounded queues and non-blocking producer; dropped consumer frames
  are reported as audio drop events.

## Today

- AAR was downloaded to the ignored `.local-deps` directory for compilation.
- Provider compiles and is wired into the debug Activity.
- Model was not downloaded or run because that requires a large external model and
  target-device validation.
- No real partial/final transcript, RTF, memory, or latency result is claimed.

## Tomorrow

- Run `tools/asr/download_sherpa_model.ps1`.
- Transfer the model to the app's `files/models` directory.
- Run the Phase 3.5 device smoke and measure RTF, queue drops, and transcript output.
