$jdk = 'd:\study\androidplay\huawei_phone\tools\jdk17\bin'
$jar = 'd:\study\androidplay\huawei_phone\akasha\libs\dhizuku-api\classes.jar'
$out = @()
$out += '=== entries ==='
$out += (& "$jdk\jar.exe" -tf $jar 2>&1)
$out += '=== UserServiceArgs refs ==='
$out += (& "$jdk\javap.exe" -c -p -cp $jar 'com.rosan.dhizuku.api.DhizukuUserServiceArgs' 2>&1 | Select-String 'BundleCompat|getParcelable|fromBundle')
$out | Out-File -Encoding utf8 'd:\study\androidplay\huawei_phone\akasha\build\dzjar.txt'
Write-Host 'done'
