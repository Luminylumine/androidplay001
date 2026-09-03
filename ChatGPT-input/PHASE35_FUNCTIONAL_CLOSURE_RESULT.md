# Phase 3.5 Functional Closure Result

## Build/Git

- Branch: `feature/mdclient`
- Base HEAD before this phase: `553882a`
- Working tree: pending Phase 3.5 changes
- AGP: `8.9.1`
- Gradle: `8.11.1`
- Kotlin plugin: `2.0.10`
- compileSdk: `36` with extension 19 for AndroidX PDF beta01
- targetSdk: `35`
- minSdk: `29`
- APK: `projects/mdclient/app/build/outputs/apk/debug/app-debug.apk`

## Verification

`clean assembleDebug test` passed with one worker after the AndroidX PDF and
Sherpa integration changes. No MatePad was available in ADB.

## Implemented

- AndroidX PDF text-layer adapter with bounded page cache and `needsOcr` fallback.
- Single-owner AudioRecord to bounded AudioFrameBus wiring.
- Sherpa-ONNX 1.13.7 provider and official bilingual Paraformer model downloader.
- Startup interrupted-session detection.
- Phase 3.5 smoke script and JVM AudioFrameBus test.
- Machine-readable full-loop trace test for page/transcript/edit/agent/document
  ordering.

## Not claimed

- Real model load, transcript output, partial/final behavior, or RTF.
- PDF fixture extraction on Android API 29/31.
- Activity recreate/process recovery on hardware.
- 20-30 minute audio soak.
- MatePad smoke or IME coexistence.

## Decision

`NOT_READY_FOR_UI`

## Maximum three blockers

1. Target-device real Sherpa model decode and RTF measurement.
2. API 29/31 PDF text extraction plus recovery runtime verification.
3. 20-30 minute AudioRecord soak and final device smoke.

## Pending human UI

Huawei Chinese composition, manual PDF gestures, visual layout, attention
visualization, and subjective classroom ASR remain Phase 4 or hardware work.
