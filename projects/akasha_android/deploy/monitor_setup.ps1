# Independent setup monitor: polls phone status/log every 60s, appends to setup_progress.log
$log = "d:\study\androidplay\huawei_phone\deploy\setup_progress.log"
for ($i = 0; $i -lt 45; $i++) {
    $out = & adb -s FEDBB23413006269 exec-out "cat /sdcard/openAkasha/status 2>/dev/null; echo ---TAIL---; tail -n 4 /sdcard/openAkasha/setup.log 2>/dev/null; ls /sdcard/openAkasha/setup.done 2>/dev/null" 2>&1
    Add-Content $log ("[$i] " + (Get-Date -Format HH:mm:ss) + " :: " + (($out -join ' | ').Trim()))
    if (($out -join '') -match "SETUP_OK|SETUP_FAILED|setup\.done") {
        Add-Content $log ("MONITOR_EXIT at [$i] " + (Get-Date -Format HH:mm:ss))
        break
    }
    Start-Sleep 60
}
Add-Content $log ("MONITOR_EXIT " + (Get-Date -Format HH:mm:ss))
