[CmdletBinding()]
param(
    [string]$Serial = "",
    [switch]$SkipSoak,
    [ValidateRange(1, 1440)] [int]$SoakMinutes = 30
)

$ErrorActionPreference = "Continue"
$results = [ordered]@{}
function Run-Gate([string]$name, [scriptblock]$action) {
    try { & $action; if ($LASTEXITCODE -ne 0) { throw "exit code $LASTEXITCODE" }; $results[$name] = "PASS" }
    catch { Write-Host "$name FAIL: $($_.Exception.Message)"; $results[$name] = "FAIL" }
}

Run-Gate "ASR_FILE" { & (Join-Path $PSScriptRoot "run_phase35_device_smoke.ps1") -Serial $Serial }
Run-Gate "MODEL_DEPLOY" { & (Join-Path (Split-Path $PSScriptRoot -Parent) "..\..\tools\asr\deploy_sherpa_model_to_device.ps1") -Serial $Serial }
Run-Gate "RECOVERY" { & (Join-Path $PSScriptRoot "run_phase35_recovery_test.ps1") -Serial $Serial }
if (!$SkipSoak) { Run-Gate "SOAK" { & (Join-Path $PSScriptRoot "run_phase35_soak.ps1") -Serial $Serial -Minutes $SoakMinutes } }
else { $results["SOAK"] = "SKIPPED" }

Write-Host "PHASE35_DEVICE_GATE_SUMMARY"
$results.GetEnumerator() | ForEach-Object { Write-Host ("{0}={1}" -f $_.Key, $_.Value) }
