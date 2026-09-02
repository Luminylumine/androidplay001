# mdclient

Standalone Human-Agent shared-attention lecture notebook client.

This project is intentionally independent from Akasha. It is a single Android
app module with a Gradle wrapper, Java/Kotlin-ready AndroidX build, and no
dependency on Akasha code.

## Build

```powershell
.\gradlew.bat assembleDebug test
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

Package: `com.androidplay.mdclient`
