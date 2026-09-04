# Phase 4B UI Repair Result

| Issue | Root cause | Code fix | Automated evidence | Device evidence | Status |
|---|---|---|---|---|---|
| Whiteboard objects | Singleton `inserted` state | Scene controller with N strokes/text boxes/stickers | Test source added; Gradle test executor unavailable in this environment | Latest APK installed and workspace launched | PENDING_HUMAN_FEEL |
| Stroke colors | Renderer used current color | Color stored on `InkStrokeItem` | Test source added; Gradle test executor unavailable in this environment | Huawei pen/color pass pending | PENDING_HUMAN_FEEL |
| Stroke/pixel eraser | Mixed flags and white overlay | Single tool enum, segment hit-test, CLEAR ink mask | Test source added; Gradle test executor unavailable in this environment | Huawei erase pass pending | PENDING_HUMAN_FEEL |
| Markdown/formula | Activity string replacement | Shared `MarkdownRenderEngine` for Notes and Sticker | Clean APK compile/package passed | `phase4b-formula-preview-fixed.png`; Notes ScrollView is `scrollable=true` | VERIFIED_AUTOMATED |
| Notes drag/permission safety | Drop target only covered editor; concurrent permission requests | ScrollView drop targets; serial permission flow; guarded audio start | Clean APK compile/package passed | Old Debug Activity resumed without `FATAL EXCEPTION`; full permission dialog still needs manual confirmation | PENDING_HUMAN_FEEL |
| PDF geometry/fling | Manual offsets without velocity/clamp | `PdfContinuousView` focal transform, centering, clamp, `OverScroller` | Clean APK compile/package passed | Real multi-page PDF fling/zoom pending | PENDING_HUMAN_FEEL |
| Independent gestures | Shared view-local gesture state | PDF and whiteboard own detectors and transforms | Clean APK compile/package passed | Simultaneous multi-touch pending | PENDING_HUMAN_FEEL |

## Remaining Human Checks

- Notes Sticker drop into the blank area of the scroll container
- PDF fast fling feel
- Huawei Pen drawing and both erase modes
- Simultaneous PDF/whiteboard multi-touch
- Sticker/TextBox drag feel

## Top Remaining UX Issues

1. The offline project has no cached mature Android Markdown/LaTeX dependency;
   the shared renderer currently supports the demonstrated Markdown subset and
   common math spans, not full LaTeX.
2. PDF pages are rendered eagerly when a document opens; visible-page caching
   is still needed for large documents.
3. Legacy unused inner canvas code should be removed after device acceptance.
