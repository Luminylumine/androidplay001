# Phase 4A UI Status

Status: PARTIAL

The sketch-driven workspace is implemented as a native Java/View projection in
`LectureWorkspaceActivity`. The legacy `MdClientActivity` remains available
from the Drawer as a debug fallback. No backend core was rewritten.

## Done

- Tablet landscape workspace with top course tabs and overlay Drawer.
- PDF, rendered notes, Lecture Logic, Agent Observable State, Scratchpad, and
  suggestion rail panes.
- Long-press armed split handles with ratio clamping and course-keyed storage.
- Session-keyed Scratchpad autosave with debounce.
- Independent Human/Agent labels and no Agent focus/selection/scroll takeover.
- Formula suggestion accept and drag/drop insertion into Scratchpad.
- Pane focus entry/exit and Demo content.
- Android instrumentation PDF fixture entry remains available.

## Pending

- `TARGET_DEVICE_UI_PENDING`: no MatePad/emulator was available for screenshots
  or runtime gesture/IME checks.
- Stylus ink is deferred; typed Scratchpad is the stable path for this pass.
- Notes TOC and Agent target chips are functional projection entries; detailed
  navigation actions remain device validation work.
