$ErrorActionPreference = "Stop"
$app = $PSScriptRoot
$root = Split-Path (Split-Path (Split-Path $app -Parent) -Parent) -Parent
$bt = Join-Path $root "tools\android-sdk\build-tools\30.0.3"
$plat = Join-Path $root "tools\android-sdk\platforms\android-29"
$java = Join-Path $root "tools\jdk17\bin"
$env:JAVA_HOME = Join-Path $root "tools\jdk17"
$build = Join-Path $app "build"
$obj = Join-Path $build "obj"
$gen = Join-Path $build "gen"
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Path $obj, $gen | Out-Null
& "$bt\aapt2.exe" compile --dir (Join-Path $app "res") -o (Join-Path $obj "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
& "$bt\aapt2.exe" link -o (Join-Path $build "base.apk") --manifest (Join-Path $app "AndroidManifest.xml") -I (Join-Path $plat "android.jar") --min-sdk-version 29 --target-sdk-version 29 --java $gen (Join-Path $obj "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }
$src = @(Get-ChildItem (Join-Path $app "src") -Recurse -Filter *.java | ForEach-Object FullName)
$src += @(Get-ChildItem $gen -Recurse -Filter *.java | ForEach-Object FullName)
& "$java\javac.exe" -source 1.8 -target 1.8 -encoding UTF-8 -classpath (Join-Path $plat "android.jar") -d (Join-Path $obj "classes") $src
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
Push-Location $obj
try {
    & "$java\jar.exe" cf app.jar -C classes .
    cmd /c "`"$java\java.exe`" -cp `"$bt\lib\d8.jar`" com.android.tools.r8.D8 --release --min-api 29 --lib `"$plat\android.jar`" --output `"$build`" app.jar"
    if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
} finally { Pop-Location }
$unsigned = Join-Path $build "unsigned.apk"
Copy-Item (Join-Path $build "base.apk") $unsigned
cmd /c "`"$java\jar.exe`" uf `"$unsigned`" -C `"$build`" classes.dex"
$aligned = Join-Path $build "aligned.apk"
& "$bt\zipalign.exe" -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
$ks = Join-Path $app "mdclient.keystore"
if (!(Test-Path $ks)) { & "$java\keytool.exe" -genkeypair -keystore $ks -alias mdclient -keyalg RSA -keysize 2048 -validity 3650 -storepass mdclient -keypass mdclient -dname "CN=mdclient" | Out-Null }
$apk = Join-Path $build "mdclient.apk"
cmd /c "`"$java\java.exe`" -cp `"$bt\lib\apksigner.jar`" com.android.apksigner.ApkSignerTool sign --ks `"$ks`" --ks-pass pass:mdclient --key-pass pass:mdclient --ks-key-alias mdclient --out `"$apk`" `"$aligned`""
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
cmd /c "`"$java\java.exe`" -cp `"$bt\lib\apksigner.jar`" com.android.apksigner.ApkSignerTool verify --print-certs `"$apk`""
Write-Host "APK=$apk"
