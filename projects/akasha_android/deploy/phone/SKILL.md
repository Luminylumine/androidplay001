---
name: phone-control
description: Operate this Android phone (Huawei 畅享50z, 720x1600 px) through ADB. Use akashactl to read the screen (ui/shot), tap, swipe, type, and launch apps.
---

# Phone Control via ADB (akashactl)

You are running ON the phone itself (Termux). Use the `exec` tool with `akashactl`
to operate the phone with ADB-level privileges (input injection, screen capture,
UI hierarchy, app launch, shell commands).

## Commands

| Command | Effect |
|---|---|
| `akashactl ui` | Compact text of the visible UI. Lines: `[x1,y1][x2,y2] Class.FLAGS label`. FLAGS: C=clickable, S=scrollable, F=focused. **Primary way to read the screen.** |
| `akashactl shot [file]` | Screenshot PNG (default `~/.openAkasha/shot.png`). Use your image tool to view it. |
| `akashactl tap X Y` | Tap pixel coordinates. |
| `akashactl swipe X1 Y1 X2 Y2 [ms]` | Swipe. Scroll a list up = Y2 < Y1. |
| `akashactl text "..."` | Type text into the focused field. |
| `akashactl key KEYCODE_BACK` | Press a key (`KEYCODE_BACK`, `KEYCODE_HOME`, `KEYCODE_WAKEUP`). |
| `akashactl wake` | Wake the screen (safe to run blindly). |
| `akashactl back` / `akashactl home` | Navigation. |
| `akashactl open <package>` | Launch app by package name (list via `akashactl apps`). |
| `akashactl current` | Foreground activity. |
| `akashactl size` | Screen size (720x1600 px). |
| `akashactl battery` / `akashactl wifi` | Device status. |

## Workflow (follow every time)

1. `akashactl wake` (safe no-op if already on).
2. `akashactl ui` — read the current screen. Find the target node and compute its
   center: X=(x1+x2)/2, Y=(y1+y2)/2 from the bounds.
3. Perform ONE action (`tap` / `swipe` / `text`).
4. Wait ~1s, then re-run `akashactl ui` to verify the effect.
5. Repeat until the task is complete, then report exactly what you did.

When `ui` text is ambiguous (icon-only buttons), use `akashactl shot` and analyze
the image to pick coordinates.

## Safety rules

- NEVER tap payment, purchase, delete, "确认支付/确认删除" or send-message buttons
  without first stating your intent to the user and getting explicit consent.
- If a screen looks unexpected (paywall, error, unknown dialog), stop and ask.
- Coordinates are absolute pixels — do not scale or convert to dp.
- If an action does not change the screen after 2 attempts, take a screenshot and
  reconsider instead of repeating blindly.
- Never enter passwords or secrets you do not already have from the user.
