$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$repo = $root
while ($repo -and !(Test-Path (Join-Path $repo ".git"))) { $parent = Split-Path $repo -Parent; if ($parent -eq $repo) { throw "repo root not found" }; $repo = $parent }
$bt = Join-Path $repo "tools\android-sdk\build-tools\30.0.3"
$plat = Join-Path $repo "tools\android-sdk\platforms\android-29"
$java = Join-Path $repo "tools\jdk17\bin"
$env:JAVA_HOME = Join-Path $repo "tools\jdk17"
$env:PATH = "$java;" + $env:PATH
$build = Join-Path $PSScriptRoot "build"
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Path "$build\gen", "$build\obj", "$build\apks" | Out-Null
& "$bt\aapt2.exe" compile --dir "$PSScriptRoot\res" -o "$build\obj\res.zip"
& "$bt\aapt2.exe" link -o "$build\base.apk" --manifest "$PSScriptRoot\AndroidManifest.xml" -I "$plat\android.jar" --min-sdk-version 29 --target-sdk-version 31 --java "$build\gen" "$build\obj\res.zip"
$src = @(Get-ChildItem "$PSScriptRoot\src" -Recurse -Filter *.java | ForEach-Object FullName)
$src += @(Get-ChildItem "$build\gen" -Recurse -Filter *.java | ForEach-Object FullName)
& "$java\javac.exe" -source 8 -target 8 -encoding UTF-8 -classpath "$plat\android.jar" -d "$build\obj\classes" $src
Push-Location "$build\obj"
try { & "$java\jar.exe" cf app.jar -C classes .; cmd /c "`"$java\java.exe`" -cp `"$bt\lib\d8.jar`" com.android.tools.r8.D8 --release --min-api 29 --lib `"$plat\android.jar`" --output `"$build`" app.jar" } finally { Pop-Location }
Copy-Item "$build\base.apk" "$build\unsigned.apk"
cmd /c "`"$java\jar.exe`" uf `"$build\unsigned.apk`" -C `"$build`" classes.dex"
$aligned = Join-Path $build "aligned.apk"
& "$bt\zipalign.exe" -f 4 "$build\unsigned.apk" $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
$ks = Join-Path $PSScriptRoot "probe.keystore"
if (!(Test-Path $ks)) { & "$java\keytool.exe" -genkeypair -keystore $ks -alias probe -keyalg RSA -keysize 2048 -validity 3650 -storepass phase0probe -keypass phase0probe -dname "CN=CapabilityProbe" | Out-Null }
cmd /c "`"$java\java.exe`" -cp `"$bt\lib\apksigner.jar`" com.android.apksigner.ApkSignerTool sign --ks `"$ks`" --ks-pass pass:phase0probe --key-pass pass:phase0probe --ks-key-alias probe --out `"$build\CapabilityProbe.apk`" `"$aligned`""
Write-Host "APK=$build\CapabilityProbe.apk"
