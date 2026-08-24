$ErrorActionPreference = "Stop"
$root = "d:\study\androidplay\huawei_phone"
$jdk  = Join-Path $root "tools\jdk17\bin"
$plat = Join-Path $root "tools\android-sdk\platforms\android-29"
$stub = Join-Path $root "clawphone\libs\dz260\stubs\androidx\core\os\BundleCompat.java"
$out  = Join-Path $root "clawphone\libs\dz260\stubout"
$jar  = Join-Path $root "clawphone\libs\dhizuku-api\classes.jar"

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null

& "$jdk\javac.exe" -source 1.8 -target 1.8 -nowarn `
    -classpath (Join-Path $plat "android.jar") -d $out $stub
if ($LASTEXITCODE -ne 0) { throw "javac BundleCompat failed" }

& "$jdk\jar.exe" uf $jar -C $out androidx
if ($LASTEXITCODE -ne 0) { throw "jar update failed" }

& "$jdk\jar.exe" tf $jar | Select-String 'androidx'
Write-Host "[fix] BundleCompat added, jar size:" (Get-Item $jar).Length
