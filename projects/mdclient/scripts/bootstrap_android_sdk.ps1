$ErrorActionPreference = "Stop"
$repo = Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
$tools = Join-Path $repo "tools"
$sdk = Join-Path $tools "android-sdk"
$downloads = Join-Path $tools "downloads"
$jdk = Join-Path $tools "jdk17"
if (!(Test-Path -LiteralPath (Join-Path $jdk "bin\java.exe"))) {
    $jdk = "D:\study\androidplay\worktrees\akasha\tools\jdk17"
}
if (!(Test-Path -LiteralPath (Join-Path $jdk "bin\java.exe"))) { throw "JDK 17 not found" }
New-Item -ItemType Directory -Force -Path $downloads | Out-Null
$zip = Join-Path $downloads "commandlinetools-win.zip"
if (!(Test-Path -LiteralPath $zip)) {
    curl.exe -L --fail --retry 3 --connect-timeout 30 -o $zip "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    if ($LASTEXITCODE -ne 0) { throw "command line tools download failed" }
}
$cmd = Join-Path $sdk "cmdline-tools\latest\bin\sdkmanager.bat"
if (!(Test-Path -LiteralPath $cmd)) {
    $tmp = Join-Path $sdk "cmdline-tools\tmp"
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $sdk "cmdline-tools") | Out-Null
    Move-Item (Join-Path $tmp "cmdline-tools") (Join-Path $sdk "cmdline-tools\latest") -Force
    Remove-Item $tmp -Recurse -Force
}
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;" + $env:PATH
"y`ny`ny`n" | & $cmd --sdk_root=$sdk --licenses | Out-Null
& $cmd --sdk_root=$sdk "platforms;android-35" "build-tools;35.0.0"
if ($LASTEXITCODE -ne 0) { throw "Android SDK install failed" }
Write-Host "SDK_ROOT=$sdk"
