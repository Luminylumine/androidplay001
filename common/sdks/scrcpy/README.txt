# Scrcpy SDK / Tool Path
This project depends on the open-source scrcpy tool (https://github.com/Genymobile/scrcpy).

Scrcpy is NOT bundled in this repository. Download the official Windows release from:
  https://github.com/Genymobile/scrcpy/releases

Expected layout (configure in AdbHelper.cs or ScrcpySession.cs if path differs):
  tools/scrcpy/scrcpy.exe
  tools/scrcpy/adb.exe     (bundled with scrcpy, or reuse the ADB from tools/android-sdk/)
  tools/scrcpy/ScrcpyServer.apk

The ADB binary path fallback: ../../common/tools/android-sdk/platform-tools/adb.exe
