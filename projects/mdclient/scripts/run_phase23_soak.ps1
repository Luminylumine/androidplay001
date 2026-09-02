[CmdletBinding()]
param(
    [string]$Serial = "",
    [ValidateRange(1, 86400)]
    [int]$DurationSeconds = 60
)

$ErrorActionPreference = "Stop"
$Package = "com.androidplay.mdclient"
$Project = Split-Path $PSScriptRoot -Parent
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$OutDir = Join-Path ([IO.Path]::GetTempPath()) "mdclient-phase23-soak-$Stamp"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function A { param([string[]]$Args) $o = @(& $script:Adb -s $script:Serial @Args 2>&1); if ($LASTEXITCODE -ne 0) { throw "adb failed: $($o -join ' ')" }; $o -join [Environment]::NewLine }
function AO { param([string[]]$Args) $o = @(& $script:Adb -s $script:Serial @Args 2>&1); [pscustomobject]@{ Code = $LASTEXITCODE; Text = $o -join [Environment]::NewLine } }
function Pick {
    $d = @(& $script:Adb devices 2>&1 | ForEach-Object { if ($_ -match '^([^\s]+)\s+device\s*$') { $Matches[1] } })
    if ($script:Serial) { if ($d -notcontains $script:Serial) { throw "requested device is not ready: $script:Serial" } }
    elseif ($d.Count -eq 1) { $script:Serial = $d[0] }
    elseif ($d.Count -eq 0) { throw "no unique adb device" }
    else { throw "multiple adb devices; pass -Serial" }
}
function ClickText {
    param([string]$Text)
    $remote = "/sdcard/mdclient-soak-ui.xml"
    $r = AO @("shell", "uiautomator", "dump", $remote)
    if ($r.Code -ne 0) { return $false }
    $raw = @(& $script:Adb -s $script:Serial exec-out cat $remote 2>&1) -join [Environment]::NewLine
    try { $ui = [xml]$raw } catch { return $false }
    [IO.File]::WriteAllText((Join-Path $OutDir "uiautomator.xml"), $raw)
    foreach ($n in $ui.SelectNodes('//node')) {
        if (([string]$n.GetAttribute('text') -eq $Text -or [string]$n.GetAttribute('text') -like "*$Text*") -and [string]$n.GetAttribute('bounds') -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $x = [int](($Matches[1] + $Matches[3]) / 2); $y = [int](($Matches[2] + $Matches[4]) / 2)
            A @("shell", "input", "tap", "$x", "$y") | Out-Null; return $true
        }
    }
    return $false
}
function Collect {
    (AO @("logcat", "-d", "-v", "threadtime")).Text | Set-Content -Encoding UTF8 (Join-Path $OutDir "logcat.txt")
    (AO @("shell", "dumpsys", "cpuinfo")).Text | Set-Content -Encoding UTF8 (Join-Path $OutDir "cpuinfo.txt")
    (AO @("shell", "dumpsys", "meminfo", $Package)).Text | Set-Content -Encoding UTF8 (Join-Path $OutDir "meminfo.txt")
    (AO @("shell", "dumpsys", "media.audio_flinger")).Text | Set-Content -Encoding UTF8 (Join-Path $OutDir "audio-flinger.txt")
    $runAs = AO @("shell", "run-as", $Package, "ls", "files")
    if ($runAs.Code -eq 0) {
        $runAs.Text | Set-Content -Encoding UTF8 (Join-Path $OutDir "run-as-files.txt")
        $tar = Join-Path $OutDir "app-files.tar"
        & $script:Adb -s $script:Serial exec-out run-as $Package tar -cf - files > $tar 2>$null
        if ($LASTEXITCODE -ne 0) { Remove-Item -LiteralPath $tar -Force -ErrorAction SilentlyContinue }
    }
}

try {
    $script:Adb = Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
    if (!$script:Adb) {
        $repo = Split-Path (Split-Path $Project -Parent) -Parent
        $script:Adb = Join-Path $repo "tools\android-sdk\platform-tools\adb.exe"
    }
    if (!(Test-Path -LiteralPath $script:Adb)) { throw "adb.exe was not found in PATH or tools\android-sdk\platform-tools" }
    Pick
    Push-Location $Project
    try { & .\gradlew.bat :app:assembleDebug; if ($LASTEXITCODE -ne 0) { throw "Gradle assembleDebug failed" } } finally { Pop-Location }
    $apk = Join-Path $Project "app\build\outputs\apk\debug\app-debug.apk"
    A @("install", "-r", $apk) | Out-Null
    A @("shell", "am", "force-stop", $Package) | Out-Null
    A @("shell", "monkey", "-p", $Package, "1") | Out-Null
    Start-Sleep -Seconds 2
    ClickText "Allow" | Out-Null
    if (!(ClickText "Start Session")) { throw "Start Session button not found" }
    Start-Sleep -Milliseconds 500
    if (!(ClickText "Start Audio")) { throw "Start Audio button not found" }
    Start-Sleep -Seconds $DurationSeconds
    Collect
    $audioStarted = (AO @("shell", "run-as", $Package, "sh", "-c", "grep -R 'AUDIO_STARTED' files/sessions")).Text
    $timestamps = (AO @("shell", "run-as", $Package, "sh", "-c", "grep -R 'AUDIO_TIMESTAMP' files/sessions")).Text
    ClickText "Stop Audio" | Out-Null
    if ($audioStarted -notmatch 'AUDIO_STARTED' -or $timestamps -notmatch 'AUDIO_TIMESTAMP') { throw "AudioRecord/timestamp evidence missing" }
    Write-Host "PASS phase23 soak duration=${DurationSeconds}s ($OutDir)"
    exit 0
} catch {
    Write-Host "FAIL phase23 soak: $($_.Exception.Message)"
    Write-Host "diagnostics: $OutDir"
    try { if ($script:Adb -and $script:Serial) { Collect } } catch { }
    exit 1
}
