[CmdletBinding()]
param(
    [string]$Serial = "",
    [ValidateRange(1, 1440)] [int]$Minutes = 30
)

$ErrorActionPreference = "Stop"
$scriptPath = Join-Path $PSScriptRoot "run_phase23_soak.ps1"
& $scriptPath -Serial $Serial -DurationSeconds ($Minutes * 60)
exit $LASTEXITCODE
