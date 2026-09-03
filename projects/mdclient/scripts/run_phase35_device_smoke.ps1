param([string]$Serial = "")
$ErrorActionPreference = "Stop"
$project = Split-Path $PSScriptRoot -Parent
$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" } else { "adb.exe" }
$devices = & $adb devices | Where-Object { $_ -match "^\S+\s+device$" }
if (!$devices) { throw "No adb device is online; Phase 3.5 smoke remains BLOCKED_EXTERNAL." }
if (!$Serial -and $devices.Count -gt 1) { throw "Multiple adb devices; rerun with -Serial." }
$adbArgs = @(); if ($Serial) { $adbArgs += @("-s", $Serial) }

$jdk = "D:\study\androidplay\worktrees\akasha\tools\jdk17"
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;C:\Windows\System32;C:\Windows"
Push-Location $project
try {
    & .\gradlew.bat :app:assembleDebug --no-daemon --max-workers=1
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
    & $adb @adbArgs install -r .\app\build\outputs\apk\debug\app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw "APK install failed" }
    & $adb @adbArgs shell am force-stop com.androidplay.mdclient
    & $adb @adbArgs shell monkey -p com.androidplay.mdclient 1
    & $adb @adbArgs shell dumpsys package com.androidplay.mdclient | Select-String "RECORD_AUDIO|POST_NOTIFICATIONS"
    Write-Host "Installed and launched mdclient; continue manual PDF/ASR/recovery checks on the device."
} finally { Pop-Location }
