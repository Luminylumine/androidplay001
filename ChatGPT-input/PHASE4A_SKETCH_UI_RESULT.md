# Phase 4A Sketch UI Result

## Build

- Branch: `feature/mdclient`
- Base: `eef4607`
- APK: `projects/mdclient/app/build/outputs/apk/debug/app-debug.apk`
- Verification: `clean assembleDebug test --offline --max-workers=1`
- Instrumentation compile: `assembleDebugAndroidTest` PASS
- Working tree: pending this Phase 4A commit

## Sketch mapping

- Course Tabs: DONE
- Sidebar/Drawer: DONE
- PDF Pane: DONE
- Material Human/Agent attention: PARTIAL, projection labels and independent default are present; runtime navigation pending
- Rendered Notes: DONE, demo projection with source-like block affordance
- TOC: PARTIAL, entry and non-focus behavior are present; detailed Notes scroll pending
- Lecture Logic: DONE, observable tree projection
- Agent Observable State: DONE, high-level work log only, no chain-of-thought
- Scratchpad: DONE, native typed IME path with session-keyed debounced persistence
- Suggestion Rail: DONE, accept and drag/drop into Scratchpad
- Resizable splits: DONE, long-press arm, clamp, haptic, persistence
- FocusPane: DONE

## Core reuse

The Activity reuses `PdfMaterialController` and the existing material/document,
session, EventStore, Agent, freshness, audio, and transcription core. UI state
is isolated in `WorkspaceUiState`, `WorkspaceRatioPolicy`, and `ScratchpadState`.

## Screenshots

`TARGET_DEVICE_UI_PENDING`: no MatePad or emulator was available, so no fake
screenshots are included. The required output directory and filenames are
documented under `docs/phase4-ui/screenshots/`.

## Known limitations

1. Target-device runtime, IME, touch, and responsive checks are pending.
2. Stylus ink is deferred; typed Scratchpad is implemented.
3. TOC and target chips have projection behavior but not complete navigation.
4. Course tabs are a first-pass static workspace list.
5. Visual polish and final typography remain Phase 4 follow-up work.

## Next Human acceptance

Focus on long-press resize, ratio restoration, PDF Human/Agent independence,
Scratchpad IME stability, suggestion insertion without cursor loss, FocusPane,
Drawer behavior, and screen readability at 2880x1920 and 1920x1200.
