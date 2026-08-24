# collect_03_personal_surface.ps1
# 任务2: ADB shell 上下文 (uid=2000, 无 root, 无 SELinux u:r:shell:s0) 下能够看到的"个人信息"最大面.
# 用于和之后的"零权限 App"作对比, 并标记哪些信息可以在"无任何用户授权, 无 adb root"下被获取.
$ErrorActionPreference = "Continue"
$SERIAL = "FEDBB23413006269"
$root = Split-Path -Parent $PSScriptRoot
$data = Join-Path $root "data\personal_surface"
New-Item -ItemType Directory -Force -Path $data | Out-Null

function A([string]$name, [string]$cmd) {
    $out = & adb -s $SERIAL exec-out $cmd 2>&1
    $out | Out-File -FilePath (Join-Path $data $name) -Encoding utf8
    Write-Host "[ok] $name"
}

# 1. 账号体系
A "dumpsys_accounts.txt" "dumpsys account 2>&1"
A "content_query_accounts.txt" "content query --uri content://com.android.contacts/raw_contacts 2>&1 | head -50; content query --uri content://com.android.contacts/contacts 2>&1 | head -50; content query --uri content://com.android.contacts/data 2>&1 | head -50"
A "dumpsys_biometric.txt" "dumpsys biometric 2>&1"

# 2. 已安装应用列表 (包括第三方支付宝/抖音/银行/网盘等敏感应用)
A "pm_all_full.txt" "pm list packages -U 2>&1"
A "pm_3p.txt" "pm list packages -3 2>&1"
A "dumpsys_packages.txt" "dumpsys package 2>&1 | head -500"
A "appops_all.txt" "appops query-op --user 0 LEGACY_STORAGE 2>&1 | head -30; appops get 2>&1 | head -100"

# 3. 文件系统: shell (uid=2000, groups=1015 sdcard_rw, 1028 sdcard_r) 能访问的目录.
# 注意 shell 属于 sdcard_rw, 因此理论上可读取 /storage/emulated/0 的大部分照片/视频/下载.
A "sdcard_ls_tree1.txt" "ls -la /storage/emulated/0 2>&1 | head -60"
A "sdcard_dcim.txt" "ls -lacR /storage/emulated/0/DCIM 2>&1 | head -120"
A "sdcard_download.txt" "ls -la /storage/emulated/0/Download 2>&1 | head -60"
A "sdcard_pictures.txt" "ls -la /storage/emulated/0/Pictures 2>&1 | head -60"
A "sdcard_movies.txt" "ls -la /storage/emulated/0/Movies 2>&1 | head -40"
A "sdcard_tencent.txt" "ls -la /storage/emulated/0/Android/data 2>&1 | grep -i -E 'tencent|alipay|taobao|baidu|huawei|aweme|netease|ctrip|xunlei|quark' | head -40"
A "data_local_tmp.txt" "ls -laR /data/local/tmp 2>&1 | head -60"

# 4. 系统设置 (secure/global/system) - 包含已连接过的 Wi-Fi SSID, VPN, 输入法, accessibility 服务等.
A "settings_secure_full.txt" "settings list secure 2>&1"
A "settings_global_full.txt" "settings list global 2>&1"
A "settings_system_full.txt" "settings list system 2>&1"

# 5. 网络面 (本机已开启飞行模式/WLAN/蓝牙关闭, 但历史 Wi-Fi SSID 仍存在 settings_secure)
A "dumpsys_wifi_full.txt" "dumpsys wifi 2>&1"
A "dumpsys_bt_full.txt" "dumpsys bluetooth_manager 2>&1"
A "dumpsys_connectivity_full.txt" "dumpsys connectivity 2>&1"
A "netstat.txt" "cat /proc/net/tcp 2>&1 | head -40; echo ===; cat /proc/net/tcp6 2>&1 | head -20"
A "iptables.txt" "iptables -L 2>&1 | head -20; ip6tables -L 2>&1 | head -10"

# 6. 位置/基站信息 (已关闭GPS, 但可能保留上次 known location / 基站列表)
A "dumpsys_location.txt" "dumpsys location 2>&1 | head -80"
A "telephony.txt" "dumpsys telephony.registry 2>&1 | head -80; dumpsys phone 2>&1 | head -60"

# 7. 媒体文件元数据索引 (通过 MediaStore ContentProvider, 但 shell 可通过数据库或 content query)
A "content_media_audio.txt" "content query --uri content://media/external/audio/media 2>&1 | head -100"
A "content_media_video.txt" "content query --uri content://media/external/video/media 2>&1 | head -100"
A "content_media_images.txt" "content query --uri content://media/external/images/media 2>&1 | head -100"

# 8. 短信 / 通话记录 (shell 通常仍可通过 content provider!) - 敏感!
A "content_calllog.txt" "content query --uri content://call_log/calls 2>&1 | head -100"
A "content_sms_inbox.txt" "content query --uri content://sms 2>&1 | head -100; content query --uri content://sms/inbox 2>&1 | head -100"

# 9. 日历事件 / 闹钟
A "content_calendar.txt" "content query --uri content://com.android.calendar/events 2>&1 | head -100"
A "content_alarm.txt" "dumpsys alarm 2>&1 | head -80"

# 10. 剪贴板 (最近复制内容)
A "dumpsys_clipboard.txt" "dumpsys clipboard 2>&1"

# 11. 最近运行进程 / 后台任务 (侧面反映用户兴趣/行为)
A "ps_all.txt" "ps -A -o USER,PID,PPID,VSZ,RSS,WCHAN,NAME 2>&1 | head -200"
A "activity_stack.txt" "dumpsys activity activities 2>&1 | head -150"
A "usage_stats.txt" "dumpsys usagestats 2>&1 | head -100"

# 12. 输入设备 + 安装包路径 (可用于侧面判断用户装了什么银行/理财/健康类App)
A "dumpsys_input.txt" "dumpsys input 2>&1 | head -80"

# 13. 通知历史 (含社交/金融/隐私相关推送内容) - 飞行模式下可能为空
A "dumpsys_notification.txt" "dumpsys notification --noredact 2>&1 | head -150"

# 14. shell 能读的 /proc 下的敏感信息 (其他进程) - 受组 3009 readproc 限制
A "proc_meminfo.txt" "cat /proc/meminfo 2>&1"
A "proc_net_arp.txt" "cat /proc/net/arp 2>&1"

# 15. 开发者选项状态 (USB 调试/Stay Awake etc.)
A "dumpsys_settings_global_secure_tbl.txt" "content query --uri content://settings/global 2>&1 | head -80; content query --uri content://settings/secure 2>&1 | head -80"

Write-Host "DONE: $data"
