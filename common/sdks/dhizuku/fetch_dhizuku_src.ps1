# Fetch Dhizuku-API sources from jsdelivr CDN (github.com direct is flaky)
# param: $args[0] = tag (default v2.6.0), $args[1] = out dir name, $args[2] = api module name
$ErrorActionPreference = "Stop"
$tag  = if ($args.Count -gt 0) { $args[0] } else { "v2.6.0" }
$dir  = if ($args.Count -gt 1) { $args[1] } else { "dz260" }
$mod  = if ($args.Count -gt 2) { $args[2] } else { "dhizuku-api" }
$base = "d:\study\androidplay\huawei_phone\clawphone\libs\$dir"
if (Test-Path $base) { Remove-Item $base -Recurse -Force }
$aidlDir = "$base\aidl\com\rosan\dhizuku\aidl"
$javaDir = "$base\java\com\rosan\dhizuku"
New-Item -ItemType Directory -Path $aidlDir, "$javaDir\api", "$javaDir\shared" | Out-Null

$aidls = @("IDhizuku","IDhizukuClient","IDhizukuRemoteProcess","IDhizukuRequestPermissionListener","IDhizukuUserServiceConnection")
$apis  = @("Dhizuku","DhizukuBinderWrapper","DhizukuClient","DhizukuRemoteProcess","DhizukuRequestPermissionListener","DhizukuServiceConnection","DhizukuServiceConnections","DhizukuUserServiceArgs")

foreach ($a in $aidls) {
    $u = "https://cdn.jsdelivr.net/gh/iamr0s/Dhizuku-API@$tag/$mod/src/main/aidl/com/rosan/dhizuku/aidl/$a.aidl"
    curl.exe -s --max-time 20 -o "$aidlDir\$a.aidl" $u
    if ($LASTEXITCODE -ne 0) { throw "aidl $a failed" }
}
foreach ($f in $apis) {
    $u = "https://cdn.jsdelivr.net/gh/iamr0s/Dhizuku-API@$tag/$mod/src/main/java/com/rosan/dhizuku/api/$f.java"
    curl.exe -s --max-time 20 -o "$javaDir\api\$f.java" $u
    if ($LASTEXITCODE -ne 0) { throw "api $f failed" }
}
$u = "https://cdn.jsdelivr.net/gh/iamr0s/Dhizuku-API@$tag/$mod/src/main/java/com/rosan/dhizuku/shared/DhizukuVariables.java"
curl.exe -s --max-time 20 -o "$javaDir\shared\DhizukuVariables.java" $u
if ($LASTEXITCODE -ne 0) { throw "shared failed" }
$u = "https://cdn.jsdelivr.net/gh/iamr0s/Dhizuku-API@$tag/hidden-api/src/main/java/android/app/ActivityThread.java"
curl.exe -s --max-time 20 -o "$base\ActivityThread.java" $u
if ($LASTEXITCODE -ne 0) { throw "ActivityThread failed" }

Get-ChildItem -Recurse -File $base | ForEach-Object { Write-Host ("{0,7} {1}" -f $_.Length, $_.FullName.Replace($base, "")) }
