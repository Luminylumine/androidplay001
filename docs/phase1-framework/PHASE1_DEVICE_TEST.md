# Phase 1 Device Test

## Procedure

1. Build and install the APK produced by `build_akasha.ps1`.
2. Launch `LectureActivity`, grant microphone permission, and exercise session/audio start and stop.
3. Edit each block, change material pages, run fake transcript and fake agent, then export Markdown.
4. Inspect `files/sessions/<id>/events.jsonl` and verify increasing `seq` and all three timestamps.

## Results

Device: HUAWEI MatePad Pro `PCE-W30`, Android 12/API 31, serial `26H0223C22000213`. The APK installed and the explicit `LectureActivity` launch succeeded. The current IME remained Huawei LatinIME; no IME switch was requested by the app. Microphone permission was granted for this test APK.

- Session start/stop: VERIFIED.
- AudioRecord 16 kHz mono PCM: VERIFIED; `AUDIO_STARTED`, `AUDIO_TIMESTAMP`, and `AUDIO_STOPPED` are present.
- Audio timestamp mapping: VERIFIED for the short smoke run; `offsetNs` is recorded for each sample. Long-term statistics: NOT_ATTEMPTED.
- Fake partial/final transcript: VERIFIED.
- Native Human edit in block 5 while Agent updated block 1: VERIFIED in the event log; focus remained on block 5 during the automated edit.
- Delayed Agent suggestion/action with target revision: VERIFIED.
- Page change: the page controls are present and event-backed; a separate tap was NOT_ATTEMPTED in the final run.
- Markdown export: code path implemented; exported file was observed in the app-private session directory. Content inspection: NOT_ATTEMPTED.
- Huawei Chinese IME composition, selection, deletion, and 3-minute stability: NOT_ATTEMPTED; requires manual actions.

Evidence: `raw/events-device-smoke-final.jsonl`, `raw/events-device-smoke-clock-aligned.jsonl`, `raw/uiautomator-after-fix-unlocked.xml`, and `raw/lecture-final.png`.

The corrected clock sample contains two timestamp mappings over approximately four seconds:

- offset minimum: `-1708904849423012 ns`
- offset maximum: `-1708904849302178 ns`
- observed range: `120834 ns`
- discontinuity: not observed in this short run
