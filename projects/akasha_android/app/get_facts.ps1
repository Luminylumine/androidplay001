$adb = 'D:\software\Androidtools\adb.exe'
$s   = 'FEDBB23413006269'
$out = @()
$out += '=== adb shell id -u ==='
$out += (& $adb -s $s shell 'id -u' 2>&1)
$out += '=== dhizuku pkg userId ==='
$out += (& $adb -s $s shell 'dumpsys package com.rosan.dhizuku' 2>&1 | Select-String 'userId')
$out += '=== shizuku pkg userId ==='
$out += (& $adb -s $s shell 'dumpsys package moe.shizuku.privileged.api' 2>&1 | Select-String 'userId')
$out += '=== shizuku external dir ==='
$out += (& $adb -s $s shell 'ls /sdcard/Android/data/moe.shizuku.privileged.api/' 2>&1)
$out += '=== procs (shizuku/dhizuku/claw) ==='
$out += (& $adb -s $s shell 'ps -A' 2>&1 | Select-String 'shizuku|dhizuku|claw')
$out += '=== akasha pkg userId ==='
$out += (& $adb -s $s shell 'dumpsys package com.akasha.app' 2>&1 | Select-String 'userId')
$out += '=== dhizuku granted perms for akasha ==='
$out += (& $adb -s $s shell 'dumpsys package com.akasha.app' 2>&1 | Select-String 'dhizuku')
$out | Out-File -Encoding utf8 'd:\study\androidplay\huawei_phone\akasha\facts.txt'
Write-Host 'facts written'
