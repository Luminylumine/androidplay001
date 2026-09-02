# Vendored SDK Artifacts

This directory contains only the binary artifacts required by the local Android
build scripts. The large upstream source snapshots and generated reference files
are intentionally not tracked in project branches.

| Component | Upstream | License | Use |
| --- | --- | --- | --- |
| Shizuku API/provider/AIDL | https://github.com/RikkaApps/Shizuku | Apache-2.0 | Compile/runtime API artifacts |
| Dhizuku API | https://github.com/iamr0s/Dhizuku | Apache-2.0 | Compile/runtime API artifact |
| scrcpy | https://github.com/Genymobile/scrcpy | Apache-2.0 | Optional external executable; not bundled |

The exact artifact files are under `shizuku/` and `dhizuku/`. See the repository
root `THIRD_PARTY_NOTICES.md` for attribution and scope details.
