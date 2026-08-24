# Download Dhizuku v2.12.0 APK: try direct github then mirrors
$ErrorActionPreference = "Continue"
$out = "d:\study\androidplay\huawei_phone\clawphone\libs\Dhizuku_v2.12.0.apk"
if (Test-Path $out) { Remove-Item $out -Force }
$u0 = "https://github.com/iamr0s/Dhizuku/releases/download/v2.12.0/Dhizuku_v2.12.0.apk"
$urls = @(
    $u0,
    "https://gh-proxy.com/$u0",
    "https://ghfast.top/$u0",
    "https://mirror.ghproxy.com/$u0",
    "https://ghproxy.net/$u0",
    "https://gh.llkk.cc/$u0"
)
foreach ($u in $urls) {
    Write-Host "trying: $u"
    curl.exe -L --retry 1 --max-time 60 -s -o $out $u
    if ($LASTEXITCODE -eq 0 -and (Test-Path $out) -and (Get-Item $out).Length -gt 2000000) {
        $h = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
        Write-Host "OK size=$((Get-Item $out).Length) sha256=$h"
        if ($h -eq "243ce26a2dad20e660452072e8891303870b85f7e9bd18c5db65f71dbe12027c") { Write-Host "HASH MATCH"; exit 0 }
        else { Write-Host "HASH MISMATCH - not the real APK"; Remove-Item $out -Force }
    }
}
Write-Host "ALL MIRRORS FAILED"
exit 1
