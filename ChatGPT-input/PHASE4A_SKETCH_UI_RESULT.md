# Phase 4A Sketch UI Result

## Build

- Branch: `feature/mdclient`
- Base: `eef4607`
- APK: `projects/mdclient/app/build/outputs/apk/debug/app-debug.apk`
- Verification: `clean assembleDebug test --offline --max-workers=1`
- Instrumentation compile: `assembleDebugAndroidTest` PASS
- Phase 4A commit: `f43fa8d`

## Sketch mapping

- Course Tabs: DONE
- Sidebar/Drawer: DONE
- PDF Pane: DONE
- Material Human/Agent attention: PARTIAL, projection labels and independent default are present; runtime navigation pending
- Rendered Notes: DONE, demo projection with source-like block affordance
- TOC: PARTIAL, entry and non-focus behavior are present; detailed Notes scroll pending
- Lecture Logic: DONE, observable tree projection
- Agent Observable State: DONE, high-level work log only, no chain-of-thought
- Scratchpad: replaced by Human whiteboard with native touch/stylus strokes
- Suggestion Rail: replaced by transient Agent stickers with whiteboard drag/drop
- Resizable splits: DONE, long-press arm, clamp, haptic, persistence
- FocusPane: DONE
- Agent suggestion stickers: DONE, five-second expiry and whiteboard drag/drop
- Human Attention whiteboard: DONE, finger/stylus stroke path
- PDF paging: DONE, continuous vertical page flow with independent pinch zoom;
  Notes TOC remains in the header
- Whiteboard gestures: DONE, two-finger pan/zoom and single-pointer/stylus ink
- Sticker rendering: DONE, dropped suggestions remain visible as cards
- Sticker test trigger: DONE, Agent panel exposes `生成贴纸` and each sticker
  expires five seconds after generation

## Core reuse

The Activity reuses `PdfMaterialController` and the existing material/document,
session, EventStore, Agent, freshness, audio, and transcription core. UI state
is isolated in `WorkspaceUiState`, `WorkspaceRatioPolicy`, and `ScratchpadState`.

## Screenshots

MatePad PCE-W30 runtime evidence is available under
`docs/phase4-ui/screenshots/`:

- `phase4a-device-main.png`: 2880x1920 workspace launch state.
- `phase4a-device-drawer.png`: opened overlay Drawer.

The next device pass should verify Huawei Pen strokes, sticker drag/drop, and
vertical PDF swipes. No `FATAL EXCEPTION` or `AndroidRuntime` crash was
observed in the previous checked logcat window.

## Known limitations

1. Long-press resize and ratio restoration were previously verified; continuous
   PDF scrolling, pinch zoom, and Huawei Pen input need a targeted device pass.
2. Stylus ink is deferred; typed Scratchpad is implemented.
3. TOC and target chips have projection behavior but not complete navigation.
4. Course tabs are a first-pass static workspace list.
5. Visual polish and final typography remain Phase 4 follow-up work.

## Next Human acceptance

Focus on long-press resize, ratio restoration, PDF Human/Agent independence,
Scratchpad IME stability, suggestion insertion without cursor loss, FocusPane,
Drawer behavior, and screen readability at 2880x1920 and 1920x1200.
