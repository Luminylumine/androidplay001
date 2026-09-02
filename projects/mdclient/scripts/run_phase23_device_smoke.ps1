[CmdletBinding()]
param(
    [string]$Serial = "",
    [ValidateRange(1, 86400)]
    [int]$DurationSeconds = 30
)

$ErrorActionPreference = "Stop"
$Package = "com.androidplay.mdclient"
$Activity = "$Package/.MdClientActivity"
$Project = Split-Path $PSScriptRoot -Parent
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$OutDir = Join-Path ([IO.Path]::GetTempPath()) "mdclient-phase23-smoke-$Stamp"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Find-Adb {
    $candidate = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($candidate) { return $candidate.Source }
    $repo = Split-Path (Split-Path $Project -Parent) -Parent
    $local = Join-Path $repo "tools\android-sdk\platform-tools\adb.exe"
    if (Test-Path -LiteralPath $local) { return $local }
    throw "adb.exe was not found in PATH or tools\android-sdk\platform-tools"
}

function Run-Adb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $result = @(& $script:Adb -s $script:Serial @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "adb $($Arguments -join ' ') failed: $($result -join ' ')" }
    return ($result -join [Environment]::NewLine)
}

function Run-AdbOptional {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $result = @(& $script:Adb -s $script:Serial @Arguments 2>&1)
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Text = ($result -join [Environment]::NewLine) }
}

function Select-Device {
    $lines = @(& $script:Adb devices 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "adb devices failed: $($lines -join ' ')" }
    $devices = @($lines | ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device\s*$') { $Matches[1] }
    })
    if ($script:Serial) {
        if ($devices -notcontains $script:Serial) { throw "requested device is not in adb state=device: $script:Serial" }
    } elseif ($devices.Count -eq 1) {
        $script:Serial = $devices[0]
    } elseif ($devices.Count -eq 0) {
        throw "no adb device in state=device"
    } else {
        throw "more than one adb device; pass -Serial (found: $($devices -join ', '))"
    }
}

function Dump-Ui {
    $remote = "/sdcard/mdclient-phase23-ui.xml"
    $dump = Run-AdbOptional @("shell", "uiautomator", "dump", $remote)
    if ($dump.ExitCode -ne 0) { return $null }
    $xml = @(& $script:Adb -s $script:Serial exec-out cat $remote 2>&1) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($xml)) { return $null }
    $path = Join-Path $OutDir "uiautomator.xml"
    [IO.File]::WriteAllText($path, $xml)
    try { return [xml]$xml } catch { return $null }
}

function Click-Text {
    param([string]$Text)
    $ui = Dump-Ui
    if ($null -ne $ui) {
        foreach ($node in $ui.SelectNodes('//node')) {
            $value = [string]$node.GetAttribute('text')
            if ($value -eq $Text -or $value -like "*$Text*") {
                if ([string]$node.GetAttribute('bounds') -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
                    $x = [int](($Matches[1] + $Matches[3]) / 2)
                    $y = [int](($Matches[2] + $Matches[4]) / 2)
                    Run-Adb @("shell", "input", "tap", "$x", "$y") | Out-Null
                    return $true
                }
            }
        }
    }
    return $false
}

function Tap-Fallback {
    param([int]$Ordinal)
    $size = Run-Adb @("shell", "wm", "size")
    $width = 1920; $height = 1080
    if ($size -match '(\d+)x(\d+)') { $width = [int]$Matches[1]; $height = [int]$Matches[2] }
    $x = [int]($width * 0.86)
    $y = [int]($height * (0.16 + (($Ordinal - 1) * 0.105)))
    Run-Adb @("shell", "input", "tap", "$x", "$y") | Out-Null
}

function Press-Button {
    param([string]$Text, [int]$FallbackOrdinal)
    if (Click-Text $Text) { Write-Host "UI: $Text"; return }
    Write-Host "UI fallback coordinate: $Text"
    Tap-Fallback $FallbackOrdinal
}

function Collect-Diagnostics {
    Run-AdbOptional @("logcat", "-d", "-v", "threadtime") | ForEach-Object { $_.Text | Set-Content -Encoding UTF8 (Join-Path $OutDir "logcat.txt") }
    Dump-Ui | Out-Null
    $runAs = Run-AdbOptional @("shell", "run-as", $Package, "ls", "files")
    if ($runAs.ExitCode -eq 0) {
        $runAs.Text | Set-Content -Encoding UTF8 (Join-Path $OutDir "run-as-files.txt")
        $tar = Join-Path $OutDir "app-files.tar"
        & $script:Adb -s $script:Serial exec-out run-as $Package tar -cf - files > $tar 2>$null
        if ($LASTEXITCODE -eq 0) { Write-Host "diagnostics: $tar" } else { Write-Host "diagnostics: run-as listing plus logcat/uiautomator" }
    } else { Write-Host "diagnostics: logcat/uiautomator (run-as unavailable)" }
}

try {
    $script:Adb = Find-Adb
    Select-Device
    Write-Host "device: $Serial"
    Push-Location $Project
    try {
        & .\gradlew.bat :app:assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "Gradle assembleDebug failed" }
    } finally { Pop-Location }
    $apk = Join-Path $Project "app\build\outputs\apk\debug\app-debug.apk"
    if (!(Test-Path -LiteralPath $apk)) { throw "debug APK was not produced" }
    Run-Adb @("install", "-r", $apk) | Out-Null
    Run-Adb @("shell", "am", "force-stop", $Package) | Out-Null
    Run-Adb @("shell", "monkey", "-p", $Package, "1") | Out-Null
    Start-Sleep -Seconds 2
    Click-Text "Allow" | Out-Null
    Press-Button "Start Session" 1
    Start-Sleep -Milliseconds 500
    Press-Button "Start Audio" 3
    Start-Sleep -Seconds $DurationSeconds
    Press-Button "Fake Transcript" 5
    Press-Button "Fake Agent" 6
    Press-Button "Export Markdown" 7
    Press-Button "Export JSONL" 8
    Start-Sleep -Seconds 1
    Press-Button "Stop Audio" 4
    Press-Button "Stop Session" 2
    Collect-Diagnostics
    $evidence = (Run-AdbOptional @("shell", "run-as", $Package, "sh", "-c", "grep -R -E 'AUDIO_STARTED|AUDIO_TIMESTAMP|TRANSCRIPT_FINAL|AGENT_ACTION|MARKDOWN_EXPORTED' files/sessions") ).Text
    if ($evidence -notmatch 'AUDIO_STARTED' -or $evidence -notmatch 'TRANSCRIPT_FINAL' -or $evidence -notmatch 'AGENT_ACTION' -or $evidence -notmatch 'MARKDOWN_EXPORTED') { throw "phase23 smoke evidence is incomplete" }
    Write-Host "PASS phase23 device smoke ($OutDir)"
    exit 0
} catch {
    Write-Host "FAIL phase23 device smoke: $($_.Exception.Message)"
    Write-Host "diagnostics: $OutDir"
    try { if ($script:Adb -and $script:Serial) { Collect-Diagnostics } } catch { Write-Host "diagnostics collection failed: $($_.Exception.Message)" }
    exit 1
}
