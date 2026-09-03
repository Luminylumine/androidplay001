[CmdletBinding()]
param([string]$Serial = "")

$ErrorActionPreference = "Stop"
$repo = Split-Path $PSScriptRoot -Parent | Split-Path -Parent
$package = "com.androidplay.mdclient"
$modelName = "sherpa-onnx-streaming-paraformer-bilingual-zh-en"
$localModel = Join-Path $repo ".local-models\$modelName"
$adb = Get-Command adb.exe -ErrorAction SilentlyContinue
if (!$adb) { $adb = Get-Command (Join-Path $repo "tools\android-sdk\platform-tools\adb.exe") -ErrorAction SilentlyContinue }
if (!$adb) { throw "adb.exe was not found" }
$adbPath = $adb.Source

$lines = @(& $adbPath devices 2>&1)
$devices = @($lines | ForEach-Object { if ($_ -match '^([^\s]+)\s+device\s*$') { $Matches[1] } })
if (!$Serial -and $devices.Count -eq 1) { $Serial = $devices[0] }
if (!$Serial) { throw "No unique adb device; pass -Serial or connect one device" }
if ($devices -notcontains $Serial) { throw "Device is not ready: $Serial" }
foreach ($file in @("tokens.txt", "encoder.int8.onnx", "decoder.int8.onnx")) {
    if (!(Test-Path -LiteralPath (Join-Path $localModel $file))) { throw "Missing local model file: $file" }
}

$remote = "/data/local/tmp/mdclient-model"
& $adbPath -s $Serial shell rm -rf $remote
& $adbPath -s $Serial shell mkdir -p $remote
& $adbPath -s $Serial push $localModel "$remote/$modelName" | Out-Null
& $adbPath -s $Serial shell run-as $package mkdir -p "files/models/$modelName"
& $adbPath -s $Serial shell run-as $package sh -c "cp -R $remote/$modelName/. files/models/$modelName/"
if ($LASTEXITCODE -ne 0) { throw "run-as model copy failed" }
$files = & $adbPath -s $Serial shell run-as $package ls "files/models/$modelName"
foreach ($file in @("tokens.txt", "encoder.int8.onnx", "decoder.int8.onnx")) {
    if ($files -notcontains $file) { throw "Device model file missing: $file" }
}
Write-Host "MODEL_PATH=files/models/$modelName"
