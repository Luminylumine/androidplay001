# Phase 4A Interaction Matrix

| Interaction | Result | Status |
| --- | --- | --- |
| Course tab tap | Selects course workspace label | DONE |
| Drawer toggle | Opens overlay without compressing workspace | DONE |
| Import PDF | Uses existing SAF/PdfMaterialController | DONE |
| PDF continuous pages | All rendered pages are vertically stacked and independently scrollable | DONE |
| PDF pinch zoom | Pinch zoom is consumed only by the PDF scroll region | DONE |
| Split drag before long press | No resize | DONE |
| Split drag after 1.5s hold | Adjusts and clamps ratio | DONE |
| Ratio persistence | SharedPreferences keyed by course | DONE |
| Human whiteboard stroke | Single finger or stylus draws ink; two fingers do not create ink | DONE |
| Human whiteboard pan/zoom | Two fingers pan and pinch only the whiteboard | DONE |
| Suggestion accept | Renders a visible sticker on the whiteboard | DONE |
| Suggestion drag | Dropping a sticker on the whiteboard renders it as a sticker | DONE |
| Focus pane | Hides siblings and restores workspace | DONE |
| Agent historical update | Observable label only, no Human editor mutation | DONE |
| MD editor | Human can directly edit source; preview toggle leaves Human in control | DONE |
| Agent background update | Ignored while Human editor has focus; updates while unfocused | DONE |
| Agent front reveal | Can reveal a selected MD line without requesting focus | DONE |
| Stylus Pen/Eraser | Pen stroke path implemented; Huawei device verification remains | PENDING_DEVICE |
| Activity recreate/IME gestures | Requires target device | PENDING_DEVICE |
