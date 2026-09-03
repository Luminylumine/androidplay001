[CmdletBinding()]
param([string]$Serial = "")

$ErrorActionPreference = "Stop"
$smoke = Join-Path $PSScriptRoot "run_phase35_device_smoke.ps1"
& $smoke -Serial $Serial
if ($LASTEXITCODE -ne 0) { throw "device smoke prerequisite failed" }
$adb = (Get-Command adb.exe -ErrorAction Stop).Source
$package = "com.androidplay.mdclient"
& $adb -s $Serial shell am force-stop $package
& $adb -s $Serial shell monkey -p $package 1 | Out-Null
Start-Sleep -Seconds 2
$files = @(& $adb -s $Serial shell run-as $package find files/sessions -maxdepth 2 -type f 2>&1)
if ($LASTEXITCODE -ne 0 -or $files.Count -eq 0) { throw "persisted session files were not visible after relaunch" }
Write-Host "PASS recovery relaunch; persisted session files remain visible."
