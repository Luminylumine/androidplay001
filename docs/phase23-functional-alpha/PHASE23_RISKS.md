# Phase 2-3 Risks

1. `LectureAudioService` has not completed the required 20-30 minute hardware
   soak or process-death recovery test.
2. AndroidX PDF text-layer extraction is wired and compile-verified, but runtime
   behavior on API 29/31 still requires a local PDF and hardware validation.
3. A real independent local streaming ASR path is wired to the official Sherpa
   AAR, but model transfer, decode, and the RTF gate remain open.
4. The debug Activity reconnects to the persisted session ID, but full process
   death and Activity recreate behavior still requires hardware validation.
5. The current UI is deliberately functional/debug-oriented and is not Phase 4
   UX acceptance material.
