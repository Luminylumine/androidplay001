$jar  = 'd:\study\androidplay\huawei_phone\akasha\libs\shizuku-api\classes.jar'
$jdk  = 'd:\study\androidplay\huawei_phone\tools\jdk17\bin'
$out  = @()
$out += '=== jar entries ==='
$out += (& "$jdk\jar.exe" -tf $jar 2>&1)
$out += '=== UserServiceArgs ==='
$out += (& "$jdk\javap.exe" -p -cp $jar 'rikka.shizuku.Shizuku$UserServiceArgs' 2>&1)
$out += '=== ShizukuServiceConnection ==='
$out += (& "$jdk\javap.exe" -p -cp $jar 'rikka.shizuku.ShizukuServiceConnection' 2>&1)
$out | Out-File -Encoding utf8 'd:\study\androidplay\huawei_phone\akasha\build\jarlist.txt'
Write-Host 'done'
