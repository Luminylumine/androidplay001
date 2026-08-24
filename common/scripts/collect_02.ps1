# collect_02.ps1
# 用途: 第二轮只读采集 - 分区表/setuid/安全启动/内核日志/账号与存储面
$ErrorActionPreference = "Continue"
$SERIAL = "FEDBB23413006269"
$root = Split-Path -Parent $PSScriptRoot
$data = Join-Path $root "data\raw"

function AdbOut([string]$name, [string]$cmd) {
    $out = & adb -s $SERIAL exec-out $cmd 2>&1
    $out | Out-File -FilePath (Join-Path $data $name) -Encoding utf8
    Write-Host "[ok] $name"
}

AdbOut "setuid_bins2.txt"   "ls -la /system/bin /vendor/bin /system/xbin /odm/bin 2>/dev/null | grep rws | head -40"
AdbOut "partitions.txt"     "ls -la /dev/block/by-name 2>&1"
AdbOut "kmsg_probe.txt"     "ls -la /dev/kmsg 2>&1; head -30 /dev/kmsg 2>&1"
AdbOut "cpufreq.txt"        "cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq 2>&1; cat /sys/devices/system/cpu/cpu4/cpufreq/cpuinfo_max_freq 2>&1; cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>&1"
AdbOut "secureboot.txt"     "getprop | grep -i -E 'verifiedbootstate|flash.locked|verity|avb|secure|lock'"
AdbOut "kconfig_probe.txt"  "ls -la /proc/config.gz 2>&1; zcat /proc/config.gz 2>&1 | head -20"
AdbOut "account_dump.txt"   "dumpsys account 2>&1 | head -60"
AdbOut "biometric_dump.txt" "dumpsys biometric 2>&1 | head -40"
AdbOut "media_files.txt"    "ls -la /data/media/0 2>&1 | head -40"
AdbOut "storage_emulated.txt" "ls -la /storage/emulated/0 2>&1 | head -40"
AdbOut "local_tmp.txt"      "ls -la /data/local 2>&1"
AdbOut "thermal.txt"        "ls /sys/class/thermal 2>&1 | head -20"
AdbOut "cameras.txt"        "dumpsys media.camera 2>&1 | head -60"

Write-Host "DONE"
