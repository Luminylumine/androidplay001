# Phase 4A Layout Spec

The workspace uses a light, near-black, blue-accent tool surface with 1dp
separators and no card stack or gradient.

```text
top bar: course tabs + compact Audio/ASR status
top:     PDF/material | rendered notes + TOC
bottom:  Lecture Logic | Agent Observable State | Human Scratchpad + rail
```

Initial ratios are approximately top 50%, PDF 50%, Logic 22%, Agent 34%, and
Scratchpad the remaining width. Split handles are 12dp hit areas with a 1.5s
long-press arm and haptic feedback. Ratios are clamped and persisted per course.

Human Attention owns the Scratchpad cursor, selection, keyboard, and text. Agent
Attention is represented only by observable target labels and suggestion state;
there is no chain-of-thought display or synthetic cursor.

Focus mode temporarily hides sibling panes and restores the previous workspace
on exit. The current implementation is intentionally functional/debug-oriented,
not final visual polish.
