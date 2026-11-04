<#
.SYNOPSIS
通过ADB安装本机生成的RoyTools，并按需激活刷新率后台。
.EXAMPLE
.\一键启动.ps1 -Apk 'D:\Downloads\RoyTools-1.1.0.apk' -Serial 'YOUR_DEVICE_SERIAL' -InstallOnly
.NOTES
不卸载旧版、不清除数据，签名不匹配时安装失败并保留手机原应用。
#>
[CmdletBinding()]
param(
    [string]$Apk = (Join-Path $PSScriptRoot 'RoyTools-1.1.0.apk'),
    [string]$Serial = $env:ANDROID_SERIAL,
    [switch]$InstallOnly
)
$ErrorActionPreference = 'Stop'
$adb = Get-Command adb -ErrorAction SilentlyContinue
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adbPath = if ($adb) { $adb.Source } else { Join-Path $sdk 'platform-tools\adb.exe' }
if (-not (Test-Path -LiteralPath $adbPath -PathType Leaf)) { throw '请先安装Android Platform Tools，并将adb加入PATH。' }
$phones = @(& $adbPath devices | Select-Object -Skip 1 | Where-Object { $_ -match '^\S+\s+device$' })
if ($Serial) { $phones = @($phones | Where-Object { ($_ -split '\s+')[0] -eq $Serial }) }
if ($phones.Count -ne 1) { throw '请连接一台已允许USB调试的手机，多台设备时传入-Serial。' }
$Serial = ($phones[0] -split '\s+')[0]
function Adb { & $adbPath -s $Serial @args; if ($LASTEXITCODE -ne 0) { throw 'ADB操作失败，已停止，不会卸载旧应用或清除数据。' } }
$pkg = 'com.roylyl.tools'
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw '未找到安装包，请先运行构建.ps1或传入-Apk。' }
Adb install -r ([IO.Path]::GetFullPath($Apk))
if ($InstallOnly) { Write-Host '安装完成。'; return }
Adb shell am start -W -n "$pkg/com.github.kr328.clash.MainActivity"
Adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS
$deviceSdk = [int](Adb shell getprop ro.build.version.sdk)
if ($deviceSdk -ge 33) { Adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS }
Adb shell dumpsys deviceidle whitelist "+$pkg"
Adb shell appops set $pkg RUN_IN_BACKGROUND allow
Adb shell appops set $pkg RUN_ANY_IN_BACKGROUND allow
Adb push (Join-Path $PSScriptRoot 'refresh/src/main/assets/start.sh') /data/local/tmp/roytools-start.sh
Adb shell sh /data/local/tmp/roytools-start.sh
Write-Host '已完成安装与激活，请在手机上查看运行状态。'
