$ErrorActionPreference = "Stop"

$repo = Split-Path $PSScriptRoot -Parent | Split-Path -Parent
$modelRoot = Join-Path $repo ".local-models"
$depsRoot = Join-Path $repo "projects\mdclient\.local-deps"
$downloads = Join-Path $repo "tools\downloads"
$version = "1.13.7"
$modelName = "sherpa-onnx-streaming-paraformer-bilingual-zh-en"
$modelArchive = Join-Path $downloads "$modelName.tar.bz2"
$modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$modelName.tar.bz2"
$modelArchiveBytes = 1047319737
$aar = Join-Path $depsRoot "sherpa-onnx-$version.aar"
$aarUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar"

New-Item -ItemType Directory -Force -Path $modelRoot, $depsRoot, $downloads | Out-Null

if (!(Test-Path -LiteralPath $aar)) {
    Write-Host "Downloading Sherpa Android AAR $version"
    curl.exe -L --fail --retry 5 --retry-delay 5 -o $aar $aarUrl
    if ($LASTEXITCODE -ne 0) { throw "AAR download failed" }
}

$modelDir = Join-Path $modelRoot $modelName
if (!(Test-Path -LiteralPath (Join-Path $modelDir "tokens.txt"))) {
    if (!(Test-Path -LiteralPath $modelArchive) -or (Get-Item -LiteralPath $modelArchive).Length -lt $modelArchiveBytes) {
        Write-Host "Downloading $modelName"
        curl.exe -L --fail --retry 5 --retry-delay 5 -C - -o $modelArchive $modelUrl
        if ($LASTEXITCODE -ne 0) { throw "model download failed" }
    }
    New-Item -ItemType Directory -Force -Path $modelRoot | Out-Null
    tar.exe -xjf $modelArchive -C $modelRoot
    if ($LASTEXITCODE -ne 0) { throw "model extraction failed" }
}

$required = @("tokens.txt", "encoder.int8.onnx", "decoder.int8.onnx")
foreach ($file in $required) {
    if (!(Test-Path -LiteralPath (Join-Path $modelDir $file))) { throw "missing model file: $file" }
}

Write-Host "SHERPA_VERSION=$version"
Write-Host "AAR=$aar"
Write-Host "MODEL=$modelDir"
