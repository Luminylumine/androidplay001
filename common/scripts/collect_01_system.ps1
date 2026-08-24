# collect_01_system.ps1
# 用途: 通过 adb (非 root) 采集华为 EVE-AL00 的系统/硬件基础信息, 结果保存到 data\raw\
# 说明: 所有命令均为只读; 输出为 UTF-8 文本
$ErrorActionPreference = "Continue"
$SERIAL = "FEDBB23413006269"
$root = Split-Path -Parent $PSScriptRoot
$data = Join-Path $root "data\raw"
New-Item -ItemType Directory -Force -Path $data | Out-Null

function AdbOut([string]$name, [string]$cmd) {
    $out = & adb -s $SERIAL exec-out $cmd 2>&1
    $out | Out-File -FilePath (Join-Path $data $name) -Encoding utf8
    Write-Host "[ok] $name"
}

# --- 1. 系统属性 / 内核 ---
AdbOut "props.txt"              "getprop"
AdbOut "uname.txt"              "uname -a"
AdbOut "proc_version.txt"       "cat /proc/version"
AdbOut "proc_cmdline.txt"       "cat /proc/cmdline"
AdbOut "cpuinfo.txt"            "cat /proc/cpuinfo"
AdbOut "meminfo.txt"            "cat /proc/meminfo"

# --- 2. 运行身份 / SELinux / root 探测(只读) ---
AdbOut "id.txt"                 "id"
AdbOut "getenforce.txt"         "getenforce"
AdbOut "su_probe.txt"           "which su 2>&1; ls -la /system/bin/su /system/xbin/su /sbin/.magisk 2>&1; ls -la /data/adb 2>&1"
AdbOut "kallsyms_probe.txt"     "ls -la /proc/kallsyms 2>&1; head -5 /proc/kallsyms 2>&1"
AdbOut "dmesg_probe.txt"        "dmesg 2>&1 | tail -40"
AdbOut "setuid_bins.txt"        "ls -la /system/bin 2>/dev/null | grep '4755'"
AdbOut "mounts.txt"             "cat /proc/mounts"

# --- 3. 存储 / 硬件 ---
AdbOut "df.txt"                 "df -h"
AdbOut "block_devs.txt"         "ls -la /dev/block 2>&1"
AdbOut "wm_size_density.txt"    "wm size; wm density"
AdbOut "cpu_list.txt"           "ls /sys/devices/system/cpu/ | head -30"
AdbOut "soc_probe.txt"          "cat /sys/devices/soc0/soc_id 2>&1; cat /sys/devices/soc0/serial_number 2>&1; ls /sys/devices/soc0/ 2>&1 | head -20"
AdbOut "battery.txt"            "dumpsys battery"

# --- 4. 进程 / 服务 / 网络栈 ---
AdbOut "ps_list.txt"            "ps -A 2>&1 | head -150"
AdbOut "services.txt"           "service list 2>&1 | head -120"
AdbOut "net_tcp.txt"            "cat /proc/net/tcp 2>&1 | head -40; cat /proc/net/tcp6 2>&1 | head -20"
AdbOut "cpuinfo_dump.txt"       "dumpsys cpuinfo 2>&1 | head -40"

# --- 5. 显示 / 传感器 / 硬件能力 ---
AdbOut "display.txt"            "dumpsys display 2>&1 | head -80"
AdbOut "sensors.txt"            "dumpsys sensorservice 2>&1 | head -120"
AdbOut "wifi_dump.txt"          "dumpsys wifi 2>&1 | head -60"
AdbOut "bt_dump.txt"            "dumpsys bluetooth_manager 2>&1 | head -40"
AdbOut "connectivity.txt"       "dumpsys connectivity 2>&1 | head -80"

# --- 6. 鸿蒙/HMS 特征(用于对比原生安卓) ---
AdbOut "harmony_props.txt"      "getprop | grep -i -E 'harmony|hms|ohos|openharmony' 2>&1"
AdbOut "hw_apps.txt"            "pm list packages 2>&1 | grep -i -E 'huawei|hms|hw' 2>&1 | head -120"
AdbOut "lib_probe.txt"          "ls /system/lib64 2>/dev/null | grep -i -E 'hm|ohos|harmony' | head -40; ls /system/app 2>/dev/null | head -60; ls /system/priv-app 2>/dev/null | head -80"

# --- 7. 应用与设置(为任务2信息面审计准备) ---
AdbOut "pm_all.txt"             "pm list packages -f 2>&1"
AdbOut "pm_thirdparty.txt"      "pm list packages -3 2>&1"
AdbOut "pm_uid_map.txt"         "pm list users 2>&1"
AdbOut "settings_secure.txt"    "settings list secure 2>&1 | head -120"
AdbOut "settings_global.txt"    "settings list global 2>&1 | head -80"
AdbOut "hwid_pkg.txt"           "dumpsys package com.huawei.hwid 2>&1 | head -100"

# --- 8. ADB 权限能力测试(只读) ---
AdbOut "adb_shell_whoami.txt"   "whoami; id; getprop ro.product.cpu.abi; getprop ro.product.cpu.abilist"

Write-Host "DONE: files in $data"
