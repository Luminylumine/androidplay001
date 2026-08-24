# deploy_phone.ps1 - PC-side orchestrator: deploy OpenAkasha + ADB bridge to the phone
#
# Target: Huawei 畅享50z (EVE-AL00, Android 10, locked BL, no root)
# Chain : Termux (Node.js) runs OpenAkasha gateway; its ADB privileges come from
#         the local adbd (adb tcpip 5555). Shizuku is started as a second,
#         cable-free privileged channel (Android 10: via USB adb only).
#
# Usage:  powershell -ExecutionPolicy Bypass -File deploy\deploy_phone.ps1 [-Adb adb]
param(
    [string]$Adb = "adb",
    [string]$DeployDir = $PSScriptRoot
)
$ErrorActionPreference = "Stop"
$Apks  = Join-Path $DeployDir "apks"
$Phone = Join-Path $DeployDir "phone"
$SD    = "/sdcard/openAkasha"

function Step([string]$m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
# NOTE: adb writes progress to stderr; under EAP=Stop, 2>&1 turns that into a
# terminating NativeCommandError, so scope EAP down to Continue around adb.
function AdbOut([string[]]$a) {
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    $r = (& $Adb @a 2>&1 | Out-String).Trim()
    $ErrorActionPreference = $prev
    return $r
}
function AdbOk([string[]]$a) {
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    & $Adb @a 2>&1 | Out-String | Write-Verbose
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    if ($code -ne 0) { throw "adb failed: $($a -join ' ')" }
}
# Dump UI hierarchy (uiautomator) from the PC channel
function Get-UiNodes {
    $xml = AdbOut @("exec-out", "uiautomator", "dump", "/dev/tty")
    $nodes = @()
    foreach ($m in [regex]::Matches($xml, "<node\b[^>]*>")) {
        $attrs = @{}
        foreach ($a in [regex]::Matches($m.Value, '([\w-]+)="([^"]*)"')) { $attrs[$a.Groups[1].Value] = $a.Groups[2].Value }
        $b = $attrs["bounds"] -as [string]
        if ($b -match "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
            $attrs["cx"] = [int]$Matches[1] + ([int]$Matches[3] - [int]$Matches[1]) / 2
            $attrs["cy"] = [int]$Matches[2] + ([int]$Matches[4] - [int]$Matches[2]) / 2
        }
        $nodes += [pscustomobject]$attrs
    }
    return $nodes
}
# Tap the first node whose text/content-desc matches $pattern
function Tap-UiText([string]$pattern, [int]$waitMs = 2500) {
    Start-Sleep -Milliseconds $waitMs
    foreach ($n in (Get-UiNodes)) {
        $label = "$($n.text) $($n.'content-desc')"
        if ($label -match $pattern -and $n.cx) {
            Write-Host "    tap [$label] @ ($($n.cx),$($n.cy))"
            AdbOk @("shell", "input", "tap", "$($n.cx)", "$($n.cy)")
            return $true
        }
    }
    return $false
}
function Type-IntoFocus([string]$text, [bool]$enter = $true) {
    # device-side single quotes keep the whole text ONE arg of `input text`
    AdbOk @("shell", "input text '$text'")
    if ($enter) { AdbOk @("shell", "input", "keyevent", "66") }
}

# ---------------------------------------------------------------- 0. device
Step "0. waiting for device..."
$dev = $null
for ($i = 0; $i -lt 60; $i++) {
    $line = (AdbOut @("devices")) -split "`n" | Where-Object { $_ -match "\sdevice$" } | Select-Object -First 1
    if ($line) { $dev = ($line -split "\s+")[0]; break }
    Start-Sleep 5
}
if (-not $dev) { throw "no ADB device appeared in 5 min" }
Write-Host "device: $dev  ($((AdbOut @("shell","getprop","ro.product.model")).Trim()))"

# ---------------------------------------------------------------- 1. apks
Step "1. installing APKs (Termux + Shizuku)"
AdbOk @("install", "-r", (Join-Path $Apks "com.termux_1002.apk"))
AdbOk @("install", "-r", (Join-Path $Apks "shizuku-v13.6.0-release.apk"))

# ---------------------------------------------------------------- 2. push
Step "2. pushing setup files to $SD"
AdbOk @("shell", "mkdir", "-p", $SD)
foreach ($f in @("akashactl.sh","ui2text.py","setup_phone.sh","SKILL.md")) {
    AdbOk @("push", (Join-Path $Phone $f), "$SD/$f")
}
$gwToken = -join ((1..48) | ForEach-Object { "0123456789abcdef"[ (Get-Random 16) ] })
$cfgPath = Join-Path $env:TEMP "openAkasha_phone.json"
((Get-Content (Join-Path $Phone "openAkasha.json") -Raw) -replace "GATEWAY_TOKEN", $gwToken) |
    Set-Content $cfgPath -Encoding utf8
AdbOk @("push", $cfgPath, "$SD/openAkasha.json")
$androidHome = Join-Path $env:USERPROFILE ".android"
if (Test-Path (Join-Path $androidHome "adbkey")) {
    AdbOk @("push", (Join-Path $androidHome "adbkey"),     "$SD/adbkey")
    AdbOk @("push", (Join-Path $androidHome "adbkey.pub"), "$SD/adbkey.pub")
    Write-Host "    PC adb keypair pushed (phone-side adb will be pre-authorized)"
} else {
    Write-Warning "no $androidHome\adbkey on PC; first phone-side adb connect may need a dialog"
}
AdbOk @("shell", "chmod", "-R", "777", $SD)

# ---------------------------------------------------------------- 3. tcpip
Step "3. adb tcpip 5555 (adbd keeps listening on TCP after USB unplug)"
AdbOk @("shell", "tcpip", "5555")
Start-Sleep 4
$line = (AdbOut @("devices")) -split "`n" | Where-Object { $_ -match "\s(device|offline)$" } | Select-Object -First 1
if (-not $line -or $line -match "offline") {
    Write-Host "    device re-enumerating after adbd restart..."
    for ($i = 0; $i -lt 24; $i++) {
        Start-Sleep 5
        $line = (AdbOut @("devices")) -split "`n" | Where-Object { $_ -match "\sdevice$" } | Select-Object -First 1
        if ($line) { break }
    }
}
if (-not $line) { throw "device did not come back after 'tcpip 5555'" }

# ---------------------------------------------------------------- 4. shizuku
Step "4. Shizuku (cable-free privileged channel; optional for core flow)"
AdbOk @("shell", "monkey", "-p", "moe.shizuku.privileged.api", "1") | Out-Null
Start-Sleep 4
if ((AdbOut @("shell", "which", "shizuku")) -match "shizuku") {
    AdbOk @("shell", "shizuku", "start")
    Write-Host "    shizuku started via adb shell"
} elseif (Tap-UiText "启动|Start") {
    Start-Sleep 4
    Write-Host "    shizuku start tapped in UI (needs PC adb server on LAN; check app)"
} else {
    Write-Warning "could not auto-start Shizuku (no 'shizuku' cmd, no start button found). Core ADB channel still works; you can start Shizuku manually later."
}

# ---------------------------------------------------------------- 5. termux
Step "5. launching Termux and running setup (this takes 10-30 min)"
AdbOk @("shell", "am", "start", "-n", "com.termux/.app.TermuxActivity")
Start-Sleep 6
# F-Droid first-launch dialog (if any)
Tap-UiText "Continue|继续|OK|好|确定|Skip|跳过" | Out-Null
# request shared storage so Termux can read /sdcard/openAkasha
Type-IntoFocus "termux-setup-storage"
Start-Sleep 3
if (-not (Tap-UiText "允许|Allow|全部允许")) {
    Write-Warning "storage permission dialog not found/tapped - /sdcard access may fail"
}
Start-Sleep 2
Type-IntoFocus "sh $SD/setup_phone.sh"

# ---------------------------------------------------------------- 6. monitor
Step "6. monitoring setup (log: $SD/setup.log)"
$done = $false
for ($i = 0; $i -lt 54; $i++) {          # up to ~45 min
    Start-Sleep 50
    $st = AdbOut @("shell", "cat", "$SD/status")
    $tail = (AdbOut @("shell", "tail", "-n", "2", "$SD/setup.log")) -split "`n"
    Write-Host ("[{0,2}/54] status={1} | {2}" -f $i, $st, ($tail -join " / ").Trim())
    if ($st) { $done = ($st -match "OK"); break }
}
$st = AdbOut @("shell", "cat", "$SD/status")
if (-not $st) { throw "setup did not finish; inspect $SD/setup.log" }

# ---------------------------------------------------------------- 7. verify
Step "7. verification (from PC ADB: input tap / ui dump / screencap)"
AdbOk @("shell", "input", "tap", "360", "400")
$ui = AdbOut @("exec-out", "uiautomator", "dump", "/dev/tty")
$nodeCount = ([regex]::Matches($ui, "<node\b")).Count
Write-Host "    uiautomator nodes visible: $nodeCount"
$shot = Join-Path $DeployDir "verify_screenshot.png"
cmd /c "`"$Adb`" exec-out screencap -p > `"$shot`""
Write-Host "    screenshot: $shot ($((Get-Item $shot -ErrorAction SilentlyContinue).Length) bytes)"

Step "8. Control UI port forward"
AdbOk @("forward", "tcp:18789", "tcp:18789")
Write-Host @"

================ DEPLOYMENT SUMMARY ================
 status      : $st
 gateway     : http://127.0.0.1:18789   (token: $gwToken)
 open          : set DeepSeek API key in Control UI
                 (Settings -> Model Providers -> Test connection)
 adb channel : phone-side Termux adb -> 127.0.0.1:5555 (pre-authorized PC key)
 shizuku     : see step 4 output (reboot needs USB re-start)
 note        : keep Termux alive; com.termux is in the Doze whitelist.
================================================================
"@
