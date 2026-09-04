# Phase 4B Repair Audit

| Symptom | Actual implementation | Root cause | Repair |
|---|---|---|---|
| Old strokes changed color | The old inner `WhiteboardView` painted `strokes` with a mutable `colors[colorIndex]`; the active workspace now uses `WhiteboardSceneView` | Style was read from global pen state during rendering | `InkStrokeItem.color` is captured by `WhiteboardSceneController.addStroke` and rendered from the item |
| Color had no explicit selection | `LectureWorkspaceActivity.addScratchpad` previously called `nextColor()` | Palette state was a cycling integer | `showPenPalette` selects a named color and changes only the next stroke's color |
| PDF drag stopped immediately and narrow pages were left-aligned | `LectureWorkspaceActivity` used `PdfCanvasView`; it manually changed offsets and did not fling | No velocity state, no bounds/centering policy | `PdfContinuousView` owns transform, focal zoom, clamp, centering, and `OverScroller` fling |
| Preview was incorrect | `toggleNotesPreview` used `replace("# ", "")` and `replace("## ", "")` | Activity contained a line-based pseudo-parser | `MarkdownRenderEngine.render` is now the shared Notes/Sticker rendering boundary |
| Sticker formula was plain text | `WhiteboardView.insertText` stored one string and drew it with `drawText` | Sticker had no object or markdown representation | `StickerItem.markdown` is rendered through `MarkdownRenderEngine` |
| Second sticker/text box replaced the first | `WhiteboardView` had one `inserted` field | Singleton content state | `WhiteboardSceneController` stores an ordered list of independent items with UUID/z-index |
| Stroke eraser drew ink | The old `WhiteboardView` mixed `pixelErase`, `panning`, and `erasers` flags | Tool state was not authoritative and stroke hit-test was absent | `WhiteboardSceneView.Tool` is the sole tool state; stroke erase calls segment-distance hit-test and never adds a stroke |
| Pixel erase was white paint | The old view used a white `Paint` overlay | Eraser was implemented as background-colored ink | Pixel eraser is an `InkStrokeItem` ink-layer mask rendered with `PorterDuff.Mode.CLEAR` |

## Scope

The active workspace path is `LectureWorkspaceActivity -> PdfContinuousView`,
`LectureWorkspaceActivity -> WhiteboardSceneView`, and
`MarkdownRenderEngine`. The legacy inner `PdfCanvasView`/`WhiteboardView`
implementations remain unused fallback code and are not part of the active
render path; removal is a follow-up cleanup after device acceptance.
