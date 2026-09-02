# setup_toolchain.ps1
# 用途: 下载并安装 JDK 17 (Temurin, 清华TUNA镜像) 与 Android SDK (platform-29 + build-tools 30.0.3)
# 仅 PC 端使用; 产物全部位于工作区 tools\ 下 (已被 .gitignore 排除, 不进入 git)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $root "tools"
$dl = Join-Path $tools "downloads"
New-Item -ItemType Directory -Force -Path $dl | Out-Null
$JDK_VERSION = "17.0.20+8"
$jdkFile = "OpenJDK17U-jdk_x64_windows_hotspot_17.0.20_8.zip"

# 1. JDK 17 (Temurin) - GitHub Adoptium releases
$jdkZip = Join-Path $dl "jdk17.zip"
if (-not ((Test-Path $jdkZip) -and (Get-Item $jdkZip).Length -gt 100MB)) {
    Write-Host "[1/4] Downloading JDK17 from Adoptium GitHub ..."
    Remove-Item $jdkZip -Force -ErrorAction SilentlyContinue
    curl.exe -L --fail --retry 3 --connect-timeout 30 -o $jdkZip "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20%2B8/$jdkFile"
    if ($LASTEXITCODE -ne 0) { throw "JDK download failed" }
    Write-Host "JDK downloaded: $((Get-Item $jdkZip).Length) bytes"
}
$jdkDir = Join-Path $tools "jdk17"
if (-not (Test-Path (Join-Path $jdkDir "bin\java.exe"))) {
    Write-Host "[2/4] Extracting JDK17 ..."
    Remove-Item $jdkDir -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Archive -Path $jdkZip -DestinationPath $tools -Force
    $extracted = Get-ChildItem $tools -Directory | Where-Object { $_.Name -like "jdk-17*" } | Select-Object -First 1
    if (-not $extracted) { throw "JDK extraction folder not found" }
    Rename-Item $extracted.FullName "jdk17"
}

# 2. Android cmdline-tools (dl.google.com 直连可达)
$cmdZip = Join-Path $dl "cmdtools.zip"
if (-not ((Test-Path $cmdZip) -and (Get-Item $cmdZip).Length -gt 100MB)) {
    Write-Host "[3/4] Downloading cmdline-tools ..."
    Remove-Item $cmdZip -Force -ErrorAction SilentlyContinue
    curl.exe -L --fail --retry 3 --connect-timeout 30 -o $cmdZip "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    if ($LASTEXITCODE -ne 0) { throw "cmdline-tools download failed" }
    Write-Host "cmdtools downloaded: $((Get-Item $cmdZip).Length) bytes"
}
$cmdBase = Join-Path $tools "android-sdk\cmdline-tools"
if (-not (Test-Path (Join-Path $cmdBase "latest\bin\sdkmanager.bat"))) {
    New-Item -ItemType Directory -Force -Path $cmdBase | Out-Null
    $tmp = Join-Path $cmdBase "tmp"
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Expand-Archive -Path $cmdZip -DestinationPath $tmp -Force
    Move-Item (Join-Path $tmp "cmdline-tools") (Join-Path $cmdBase "latest")
    Remove-Item -Recurse -Force $tmp
}

# 3. sdkmanager 安装 platform-29 + build-tools 30.0.3
$env:JAVA_HOME = $jdkDir
$env:PATH = "$jdkDir\bin;" + $env:PATH
$sdkRoot = Join-Path $tools "android-sdk"
$sdkmgr = Join-Path $cmdBase "latest\bin\sdkmanager.bat"
Write-Host "[4/4] sdkmanager: licenses + platform-29 + build-tools;30.0.3 ..."
cmd /c "y | `"$sdkmgr`" --sdk_root=`"$sdkRoot`" --licenses" 2>&1 | Out-Null
cmd /c "y | `"$sdkmgr`" --sdk_root=`"$sdkRoot`" `"platforms;android-29`" `"build-tools;30.0.3`""
if ($LASTEXITCODE -ne 0) { throw "sdkmanager install failed" }

Write-Host "TOOLCHAIN READY"
"JAVA_HOME=$jdkDir`nSDK_ROOT=$sdkRoot" | Out-File (Join-Path $tools "env.txt") -Encoding utf8
