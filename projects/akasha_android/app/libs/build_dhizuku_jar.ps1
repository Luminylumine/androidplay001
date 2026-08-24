# Build Dhizuku-API classes.jar from v2.4 sources (class-65 AAR is unreadable by javac 17)
$ErrorActionPreference = "Stop"
$root  = "d:\study\androidplay\huawei_phone"
$bt    = Join-Path $root "tools\android-sdk\build-tools\30.0.3"
$aidl  = Join-Path $root "tools\android-sdk\build-tools\34.0.0\android-14\aidl.exe"
$plat  = Join-Path $root "tools\android-sdk\platforms\android-29"
$java  = Join-Path $root "tools\jdk17\bin"
$src   = Join-Path $root ("akasha\libs\" + $(if ($args.Count -gt 0) { $args[0] } else { "dz24" }))
$gen   = Join-Path $src "gen"
$out   = Join-Path $src "classes"
$decls = Join-Path $src "decls"

if (Test-Path $gen) { Remove-Item $gen -Recurse -Force }
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
if (Test-Path $decls) { Remove-Item $decls -Recurse -Force }
New-Item -ItemType Directory -Path $gen, $out, $decls | Out-Null

# 0. expand framework.aidl into per-class declaration files (this aidl.exe ignores -m)
$fw = Join-Path $plat "framework.aidl"
Get-Content $fw | ForEach-Object {
    if ($_ -match '^parcelable\s+([\w.]+)\s*;') {
        $parts = $Matches[1] -split '\.'
        $pkgDir = Join-Path $decls ($parts[0..($parts.Length - 2)] -join '/')
        New-Item -ItemType Directory -Force -Path $pkgDir | Out-Null
        Set-Content -Path (Join-Path $pkgDir ("$($parts[-1]).aidl")) -Value "parcelable $($Matches[1]);"
    }
}

# 1. AIDL -> java stubs (use build-tools 34 aidl; needs package-matching paths + decls for android.* types)
$aidls = Get-ChildItem -Recurse -Path (Join-Path $src "aidl") -Filter *.aidl
foreach ($a in $aidls) {
    & $aidl --lang=java -o $gen -I $decls -I (Join-Path $src "aidl") $a.FullName
    if ($LASTEXITCODE -ne 0) { throw "aidl failed: $($a.Name)" }
}

# 2. javac (sources: aidl stubs + api + shared + compile-only stubs)
$srcs = @(Get-ChildItem -Recurse -Path $gen -Filter *.java | ForEach-Object { $_.FullName })
$srcs += (Get-ChildItem -Recurse -Path (Join-Path $src "java") -Filter *.java | ForEach-Object { $_.FullName })
$srcs += (Get-ChildItem -Recurse -Path (Join-Path $src "stubs") -Filter *.java | ForEach-Object { $_.FullName })
$srcs += (Join-Path $src "ActivityThread.java")
& "$java\javac.exe" -source 1.8 -target 1.8 -nowarn -encoding UTF-8 `
    -classpath (Join-Path $plat "android.jar") -d $out $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# 3. jar (only com/rosan/dhizuku, stubs excluded)
$jar = Join-Path $src "dhizuku-api-classes.jar"
& "$java\jar.exe" cf $jar -C $out com
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

# 4. verify class version + list
& "$java\javap.exe" -v -cp $jar com.rosan.dhizuku.api.Dhizuku | Select-String "major version"
& "$java\jar.exe" tf $jar

# 5. install into libs
Copy-Item $jar (Join-Path $root "akasha\libs\dhizuku-api\classes.jar") -Force
Write-Host "[dz] installed:" (Get-Item (Join-Path $root "akasha\libs\dhizuku-api\classes.jar")).Length "bytes"
