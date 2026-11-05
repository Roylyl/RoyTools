<#
.SYNOPSIS
使用JDK21与Android SDK35构建RoyTools，不安装或操作手机。
.EXAMPLE
.\构建.ps1 -Version 1.1.2 -BuildType Release -OutputDirectory ([Environment]::GetFolderPath('Desktop'))
.NOTES
Release需要仓库外密钥，使用signing.properties或ROYTOOLS_KEYSTORE_* / ROYTOOLS_KEY_*环境变量。
Debug沿用本机Android调试密钥，请勿将其误认为新生成的正式发行密钥。
#>
[CmdletBinding()]
param(
    [ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version = '1.1.2',
    [ValidateSet('Debug', 'Release')][string]$BuildType = 'Debug',
    [string]$OutputDirectory = $PSScriptRoot,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' })
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Test-Jdk21([string]$Path) {
    if (-not $Path -or -not (Test-Path -LiteralPath (Join-Path $Path 'bin\javac.exe') -PathType Leaf)) { return $false }
    $release = Join-Path $Path 'release'
    return (Test-Path -LiteralPath $release) -and ([IO.File]::ReadAllText($release) -match '(?m)^JAVA_VERSION="21(?:\.|\+|")')
}
if (-not (Test-Jdk21 $JavaHome)) {
    $candidates = @()
    if ($env:USERPROFILE) { $candidates += Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle\jdks') -Directory -ErrorAction SilentlyContinue | ForEach-Object FullName }
    if ($env:ProgramFiles) {
        $candidates += Get-ChildItem -Path (Join-Path $env:ProgramFiles 'Java\*'),(Join-Path $env:ProgramFiles 'Eclipse Adoptium\*') -Directory -ErrorAction SilentlyContinue | ForEach-Object FullName
        $candidates += Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    }
    $JavaHome = $candidates | Where-Object { Test-Jdk21 $_ } | Select-Object -First 1
}
if (-not $JavaHome) { throw '未找到JDK21，请设置JAVA_HOME或传入-JavaHome。' }
if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms\android-35\android.jar') -PathType Leaf)) {
    throw '未找到Android SDK35，请设置ANDROID_HOME或传入-AndroidSdk。'
}
foreach ($library in @('libclash.so','libbridge.so')) {
    if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot "core\src\main\jniLibs\arm64-v8a\$library") -PathType Leaf)) {
        throw '缺少arm64原生库，请先按docs/BUILDING.md运行scripts/build-native.ps1。'
    }
}
if ($BuildType -eq 'Release' -and -not $env:ROYTOOLS_KEYSTORE_PATH -and -not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'signing.properties'))) {
    throw 'Release尚未配置签名。请使用仓库外密钥和签名环境变量，或本机signing.properties。'
}
$previousJava = $env:JAVA_HOME
$previousAndroid = $env:ANDROID_HOME
Push-Location -LiteralPath $PSScriptRoot
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidSdk
    & .\gradlew.bat ":app:assembleMeta$BuildType" "-ProytoolsVersion=$Version" --console plain
    if ($LASTEXITCODE -ne 0) { throw '构建失败，请检查上方Gradle错误。' }
    $variant = $BuildType.ToLowerInvariant()
    $apks = @(Get-ChildItem -LiteralPath "app\build\outputs\apk\meta\$variant" -Filter "RoyTools-$Version-meta-arm64-v8a-$variant*.apk" -File)
    if ($apks.Count -ne 1) { throw '未找到唯一的arm64安装包，请检查构建输出。' }
    if ($BuildType -eq 'Release' -and $apks[0].Name -match 'unsigned') { throw 'Release没有成功签名，未导出unsigned安装包。' }
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $destination = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) "RoyTools-$Version.apk"
    Copy-Item -LiteralPath $apks[0].FullName -Destination $destination -Force
    Write-Host "已生成${BuildType}安装包：$destination"
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJava
    $env:ANDROID_HOME = $previousAndroid
}
