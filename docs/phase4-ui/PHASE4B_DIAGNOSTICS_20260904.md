# Phase 4B Device Diagnostics

Captured from HUAWEI PCE-W30, serial `26H0223C22000213` after the user
reported a black screen following the Diagnostics sidebar click.

## Current State

- `mWakefulness=Awake`
- `mResumedActivity` was `com.androidplay.mdclient/.MdClientActivity`
- The mdclient process remained alive (`pid 13775` during capture).
- Window manager reported `mCurrentFocus=null` and the app window was not
  ready for display; the Splash Screen remained the top opaque window.
- No new `FATAL EXCEPTION` was present for this interaction.

## Existing Crash Buffer

The crash buffer only contained older drag/drop failures:

```text
java.lang.NullPointerException
 at java.util.Objects.requireNonNull
 at android.view.ContentInfo$Builder.build
 at android.widget.Editor.onDrop
 at android.widget.TextView.onDragEvent
```

Those entries were timestamped before the Diagnostics report and are not
evidence that the Diagnostics item itself crashed the app.

## Artifacts

- `screenshots/phase4b-diagnostics-black.png`
- Captured `logcat -b crash -d -v threadtime`
- Captured `dumpsys activity activities`
- Captured `dumpsys window windows`
- Captured `dumpsys activity exit-info com.androidplay.mdclient`
