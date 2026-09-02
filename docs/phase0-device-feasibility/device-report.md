# Phase 0 Device Feasibility Report

Date: 2026-09-02  
Device: HUAWEI MatePad Pro (`PCE-W30`, `HWPCE-L`)  
ADB serial: `26H0223C22000213`

## Summary

| Capability | Status | Evidence |
| --- | --- | --- |
| Android/API baseline | VERIFIED | Android 12, `SDK_INT=31`, `arm64-v8a` |
| Display baseline | VERIFIED | `2880x1920`, density `320`, approximately 120 Hz, no cutout |
| Huawei WebView | VERIFIED | `com.huawei.webview` `114.0.5.302`, versionCode `21705` |
| Native EditText focus | VERIFIED | `FOCUS_CHANGE native=true` in `events-input-page-agent-append.jsonl` |
| WebView focus/selection | VERIFIED | `focus`, `selectionchange`, and `blur` events observed |
| WebView text/composition input | UNKNOWN | `adb input text` did not produce usable input/composition events; requires manual hardware/software IME entry |
| Recognition availability | VERIFIED | `Speech available=true`, `on-device=false` |
| Recognition services | VERIFIED | Xiaomi `com.xiaomi.mibrain.speech/.asr.AsrService`; Huawei fake service |
| AudioRecord | VERIFIED | `AUDIO_START`, `AUDIO_STOP`, and six timestamp samples with `rc=0` |
| Audio timestamp mapping | LIKELY | Monotonic elapsed and audio timestamps advanced during the 6-second run; longer drift test remains |
| Page timeline event | VERIFIED | `PAGE_CHANGE` advanced from page 1 to page 2/3 |
| Agent timeline event | VERIFIED | `FAKE_AGENT_ACTION` records `basedOnSeq` and `basedOnEventTime` |
| Background DOM append | VERIFIED | `Page 1 [agent append] [agent append]` visible in final UI hierarchy |
| Third-party IME voice event path | UNKNOWN | Input-method inventory is collected, but voice dictation was not manually exercised |

## Recommended Architecture Constraint

The device can support the Phase 0 interaction model at the primitive level: native input focus, WebView focus/selection, DOM block mutation, page events, and audio capture with a monotonic timestamp source are all available. Keep the first implementation conservative: native input as the authoritative editor path, WebView as a rendering/interaction surface, and append-only event records carrying sequence plus monotonic time.

Do not treat speech recognition as an on-device capability on this firmware. Recognition-service behavior and IME voice injection still require a manual test with the actual installed keyboard.

## Raw Evidence

- `raw/getprop.txt`
- `raw/dumpsys-display.txt`
- `raw/dumpsys-webviewupdate.txt`
- `raw/ime-list.txt`
- `raw/events-input-page-agent-append.jsonl`
- `raw/uiautomator-final.xml`
- `raw/probe-after-ui-audio-input.png`
