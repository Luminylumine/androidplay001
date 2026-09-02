# Phase 2-3 Risks

1. `LectureAudioService` has not completed the required 20-30 minute hardware
   soak or process-death recovery test.
2. The current PDF controller renders pages but does not extract text; OCR and
   text-layer extraction remain explicit `needsOcr`/pending behavior.
3. A real independent local streaming ASR is blocked until a compatible
   sherpa-onnx binding and model are supplied; system SpeechRecognizer is not
   treated as an equivalent.
4. The debug Activity reconnects to the persisted session ID, but full process
   death and Activity recreate behavior still requires hardware validation.
5. The current UI is deliberately functional/debug-oriented and is not Phase 4
   UX acceptance material.
