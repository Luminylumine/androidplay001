# mdclient

Standalone Human-Agent shared-attention lecture notebook client.

This project is intentionally independent from Akasha. It uses the repository's
local Android toolchain and a small hand-built Java Android app until the product
framework is stable enough to introduce a dependency-managed build.

## Build

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File app/build_mdclient.ps1
```

APK output: `app/build/mdclient.apk`

Package: `com.androidplay.mdclient`
