$adb = 'D:\software\Androidtools\adb.exe'
$s   = 'FEDBB23413006269'
$dir = 'd:\study\androidplay\huawei_phone\akasha'

$out = @()
$out += '=== procs ==='
$out += (& $adb -s $s shell 'ps -A' 2>&1 | Select-String 'shizuku|dhizuku|claw')
$out += '=== app storage perms ==='
$out += (& $adb -s $s shell 'dumpsys package com.akasha.app' 2>&1 | Select-String 'EXTERNAL_STORAGE' -Context 0,1)
$out += '=== pull app log ==='
$out += (& $adb -s $s pull '/sdcard/Android/data/com.akasha.app/files/logs/akasha.log' "$dir\akasha.log" 2>&1)
$out += '=== pull shell log ==='
$out += (& $adb -s $s pull '/sdcard/akasha_shell.log' "$dir\akasha_shell.log" 2>&1)
$out += '=== logcat Akasha (last 200) ==='
$out += (& $adb -s $s logcat -d -s Akasha 2>&1 | Select-Object -Last 200)
$out | Out-File -Encoding utf8 "$dir\pull_result.txt"
Write-Host 'pull done'
