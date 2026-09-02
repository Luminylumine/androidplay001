# Akasha local build: aapt2 -> javac -> d8 -> zip dex -> apksigner
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File build_akasha.ps1
$ErrorActionPreference = "Stop"

# Resolve repo root robustly — walk up from PSScriptRoot looking for .git/
$root = $PSScriptRoot
while ($root -and !(Test-Path (Join-Path $root ".git"))) {
    $parent = Split-Path $root -Parent
    if (-not $parent -or $parent -eq $root) { throw "cannot find repo root (.git dir) from $PSScriptRoot" }
    $root = $parent
}
Write-Host "[build] repo root resolved: $root"

# Project lives at repo/projects/akasha_android/app (PSScriptRoot is app/)
$proj  = $PSScriptRoot
$bt    = Join-Path $root "tools\android-sdk\build-tools\30.0.3"
$plat  = Join-Path $root "tools\android-sdk\platforms\android-29"
$java  = Join-Path $root "tools\jdk17\bin"
# d8.bat / apksigner.bat rely on find_java.bat -> make sure it can find java
$env:JAVA_HOME = Join-Path $root "tools\jdk17"
$env:PATH = "$java;" + $env:PATH
$build = Join-Path $proj "build"
$obj   = Join-Path $build "obj"
$gen   = Join-Path $build "gen"

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Path $obj, $gen, (Join-Path $build "apks") | Out-Null

function Log($m) { Write-Host "[build] $m" }

# 1. resources -> flat
Log "aapt2 compile"
& "$bt\aapt2.exe" compile --dir "$proj\res" -o "$obj\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

# 2. link -> base.apk + R.java
Log "aapt2 link"
& "$bt\aapt2.exe" link -o "$build\base.apk" --manifest "$proj\AndroidManifest.xml" `
    -I "$plat\android.jar" --min-sdk-version 26 --target-sdk-version 29 `
    --java "$gen" "$obj\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# 3. javac (classpath uses Shizuku/Dhizuku api jars FROM COMMON SDK LAYER)
Log "javac — classpath uses common/sdks/shizuku & common/sdks/dhizuku"
$srcs = @(Get-ChildItem -Recurse -Path (Join-Path $proj "src") -Filter *.java | ForEach-Object { $_.FullName })
$srcs += (Get-ChildItem -Recurse -Path $gen -Filter *.java | ForEach-Object { $_.FullName })
$libs = @(
    (Join-Path $proj "libs\Java-WebSocket-1.6.0.jar"),
    (Join-Path $root "common\sdks\shizuku\shizuku-api\classes.jar"),
    (Join-Path $root "common\sdks\shizuku\shizuku-provider\classes.jar"),
    (Join-Path $root "common\sdks\shizuku\shizuku-aidl\classes.jar"),
    (Join-Path $root "common\sdks\dhizuku\dhizuku-api\classes.jar")
)
foreach ($l in $libs) {
    if (!(Test-Path $l)) { throw "missing SDK jar: $l (did you populate common/sdks/ on main branch?)" }
}
$cp = ($plat + '\android.jar;' + ($libs -join ';'))
& "$java\javac.exe" -source 1.8 -target 1.8 -nowarn -encoding UTF-8 -classpath $cp -d "$obj\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# 4. d8 -> classes.dex (app classes + dependency jars)
#    app classes are packed into one jar first so the command line stays short
Log "d8"
Push-Location $obj
try {
    & "$java\jar.exe" cf app_classes.jar -C classes .
    if ($LASTEXITCODE -ne 0) { throw "jar cf app_classes failed" }
    $inputs = (@("app_classes.jar") + $libs) -join ' '
    cmd /c "`"$java\java.exe`" -Xmx1024M -cp `"$bt\lib\d8.jar`" com.android.tools.r8.D8 --release --min-api 26 --lib `"$plat\android.jar`" --output `"$build`" $inputs"
    if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
} finally {
    Pop-Location
}

# 5. add classes.dex into apk
Log "package dex"
$apk = Join-Path $build "unsigned.apk"
Copy-Item "$build\base.apk" $apk
cmd /c "`"$java\jar.exe`" uf `"$apk`" -C `"$build`" classes.dex"
if ($LASTEXITCODE -ne 0) { throw "jar add dex failed" }
Write-Host ("[build] classes.dex = {0} bytes, unsigned.apk = {1} bytes" -f (Get-Item (Join-Path $build "classes.dex")).Length, (Get-Item $apk).Length)

# 6. sign
Log "sign"
$ks = Join-Path $proj "akasha.keystore"
if (-not (Test-Path $ks)) {
    & "$java\keytool.exe" -genkeypair -v -keystore $ks -alias akasha `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass akasha123 -keypass akasha123 `
        -dname "CN=Akasha, OU=Dev, O=Akasha, L=NA, ST=NA, C=CN" | Out-Null
}
$final = Join-Path $build "Akasha-v1.apk"
cmd /c "`"$java\java.exe`" -cp `"$bt\lib\apksigner.jar`" com.android.apksigner.ApkSignerTool sign --ks `"$ks`" --ks-pass pass:akasha123 --key-pass pass:akasha123 --ks-key-alias akasha --out `"$final`" `"$apk`""
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
cmd /c "`"$java\java.exe`" -cp `"$bt\lib\apksigner.jar`" com.android.apksigner.ApkSignerTool verify --print-certs `"$final`""

Log "DONE: $final ($((Get-Item $final).Length) bytes)"
