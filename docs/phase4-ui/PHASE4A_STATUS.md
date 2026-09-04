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
- Formula suggestion accept and drag/drop insertion into the Human whiteboard.
- Agent suggestions are transient five-second stickers; they can be tapped or
  dragged onto the Human whiteboard for insertion.
- Human Attention is now a pen-compatible whiteboard projection; PDF paging is
  continuous vertical page flow with independent pinch zoom, and the Notes
  header retains the TOC action.
- Whiteboard two-finger pan/zoom is isolated from PDF gestures; dropped Agent
  stickers remain visible as rendered yellow cards.
- A `生成贴纸` control is available in Agent Observable State so the transient
  sticker can be repeatedly generated during device acceptance.
- Split handles are 6dp and compact pane headers are 28dp.
- Pane focus entry/exit and Demo content.
- Android instrumentation PDF fixture entry remains available.

## Pending

- MatePad PCE-W30 runtime smoke is now available: the workspace launches at
  2880x1920, the Drawer opens, the formula suggestion can be accepted into the
  Scratchpad, and the typed IME path shows successfully without a crash.
- Stylus ink is deferred; typed Scratchpad is the stable path for this pass.
- Notes TOC and Agent target chips are functional projection entries; detailed
  navigation actions remain device validation work.

## Device Evidence

- Device: HUAWEI PCE-W30, serial `26H0223C22000213`.
- Main workspace screenshot: `screenshots/phase4a-device-main.png`.
- Drawer screenshot: `screenshots/phase4a-device-drawer.png`.
- Activity remained `LectureWorkspaceActivity`; the checked logcat window had
  no `FATAL EXCEPTION` or `AndroidRuntime` crash.
