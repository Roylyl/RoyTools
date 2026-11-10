<#
.SYNOPSIS
从源码构建RoyTools的arm64-v8a代理核心和JNI桥，两库均成功后再更新预编译目录。
.EXAMPLE
.\scripts\build-native.ps1 -GoRoot "$env:LOCALAPPDATA\RoyToolsBuild\go" -AndroidSdk "$env:LOCALAPPDATA\Android\Sdk" -Api 24
.NOTES
需要已应用Android pidfd兼容补丁的MetaCubeX Go工具链和NDK29.0.14206865。
Go1.26.10的src/os/pidfd_linux.go中，pidfdWorks开头必须有：
    if runtime.GOOS == "android" { return false }
本脚本只检查该补丁，不修改Go工具链。arm64构建不需要旧32位补丁。
#>
[CmdletBinding()]
param(
    [string]$GoRoot = (Join-Path $env:LOCALAPPDATA 'RoyToolsBuild\go'),
    [string]$AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    [ValidateRange(21, 99)][int]$Api = 24,
    [ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version = '2.1.1',
    [ValidatePattern('^\d{8}$')][string]$BuildDate = (Get-Date -Format 'yyyyMMdd')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$GoRoot = (Resolve-Path -LiteralPath $GoRoot).Path
$AndroidSdk = (Resolve-Path -LiteralPath $AndroidSdk).Path
$ndkRoot = Join-Path $AndroidSdk 'ndk\29.0.14206865'
$llvmRoot = Join-Path $ndkRoot 'toolchains\llvm\prebuilt\windows-x86_64'
$clang = Join-Path $llvmRoot 'bin\clang.exe'
$clangCxx = Join-Path $llvmRoot 'bin\clang++.exe'
$sysroot = Join-Path $llvmRoot 'sysroot'
$go = Join-Path $GoRoot 'bin\go.exe'
$moduleDir = Join-Path $repoRoot 'core\src\foss\golang'
$nativeDir = Join-Path $repoRoot 'core\src\main\golang\native'
$cppDir = Join-Path $repoRoot 'core\src\main\cpp'
$outputDir = Join-Path $repoRoot 'core\build\native\meta\arm64-v8a'
$prebuiltDir = Join-Path $repoRoot 'core\src\main\jniLibs\arm64-v8a'
$target = "aarch64-linux-android$Api"

foreach ($required in @(
    $go, $clang, $clangCxx, (Join-Path $sysroot 'usr\include\jni.h'),
    (Join-Path $moduleDir 'go.mod'), (Join-Path $nativeDir 'bridge.h'),
    (Join-Path $cppDir 'main.c'), (Join-Path $cppDir 'jni_helper.c'),
    (Join-Path $cppDir 'bridge_helper.c')
)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "缺少原生构建文件：$required"
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $sysroot "usr\lib\aarch64-linux-android\$Api") -PathType Container)) {
    throw "当前NDK不支持AndroidAPI$Api。"
}
$goMod = [IO.File]::ReadAllText((Join-Path $moduleDir 'go.mod'))
if ($goMod -notmatch '(?m)^replace\s+cfa\s*=>\s*\.\./\.\./main/golang\s*$') {
    throw 'foss模块需要replace cfa => ../../main/golang，请先恢复源码中的本地模块映射。'
}
$pidfdFile = Join-Path $GoRoot 'src\os\pidfd_linux.go'
if (-not (Test-Path -LiteralPath $pidfdFile -PathType Leaf) -or
    [IO.File]::ReadAllText($pidfdFile) -notmatch 'func\s+pidfdWorks\(\)\s+bool\s*\{\s*if\s+runtime\.GOOS\s*==\s*"android"\s*\{\s*return\s+false') {
    throw 'Go工具链缺少Android pidfd兼容补丁，请按脚本开头说明使用专用已补丁工具链。'
}

$environmentNames = @(
    'GOROOT', 'GOOS', 'GOARCH', 'CGO_ENABLED', 'CC', 'CXX', 'AR',
    'CGO_CFLAGS', 'CGO_CXXFLAGS', 'CGO_CPPFLAGS', 'CGO_LDFLAGS',
    'GOTOOLCHAIN', 'GOWORK', 'GOFLAGS', 'GOPROXY', 'PATH'
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$locationPushed = $false
try {
    $env:GOROOT = $GoRoot
    $env:GOOS = 'android'
    $env:GOARCH = 'arm64'
    $env:CGO_ENABLED = '1'
    $clangForGo = $clang.Replace('\', '/')
    $clangCxxForGo = $clangCxx.Replace('\', '/')
    $env:CC = '"{0}" --target={1}' -f $clangForGo, $target
    $env:CXX = '"{0}" --target={1}' -f $clangCxxForGo, $target
    $env:AR = Join-Path $llvmRoot 'bin\llvm-ar.exe'
    $sourceMap = '-ffile-prefix-map={0}=.' -f $repoRoot.Replace('\', '/')
    $env:CGO_CFLAGS = '-O3 -fPIC "{0}"' -f $sourceMap
    $env:CGO_CXXFLAGS = $env:CGO_CFLAGS
    $env:CGO_CPPFLAGS = ''
    $env:CGO_LDFLAGS = '-Wl,-z,max-page-size=16384'
    $env:GOTOOLCHAIN = 'local'
    $env:GOWORK = 'off'
    $env:GOFLAGS = ''
    $env:PATH = (Join-Path $GoRoot 'bin') + ';' + (Join-Path $llvmRoot 'bin') + ';' + $env:PATH

    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    $versionHeader = Join-Path $outputDir 'version.h'
    $versionTag = 'RoyTools_{0}_{1}' -f $Version, $BuildDate
    $header = @"
#ifndef VERSION_H_IN
#define VERSION_H_IN
#define GIT_VERSION $versionTag
#define make_Str(x) #x
#define make_String(x) make_Str(x)
#endif
"@
    [IO.File]::WriteAllText($versionHeader, $header, [Text.UTF8Encoding]::new($false))

    Push-Location -LiteralPath $moduleDir
    $locationPushed = $true
    Write-Host '正在构建arm64-v8a/libclash.so……'
    & $go build -tags 'foss,with_gvisor,cmfa' -trimpath -buildvcs=false -buildmode=c-shared `
        -ldflags '-s -w -linkmode=external -extldflags=-Wl,-soname,libclash.so,-z,max-page-size=16384' `
        -o (Join-Path $outputDir 'libclash.so') 'cfa/native'
    if ($LASTEXITCODE -ne 0) { throw 'libclash.so构建失败，预编译库未更新。' }

    Write-Host '正在构建arm64-v8a/libbridge.so……'
    # Force the generated guard first so a previous CMake version.h cannot override it.
    $bridgeArguments = @(
        "--target=$target", "--sysroot=$sysroot", '-shared', '-fPIC', '-O3', $sourceMap,
        '-include', $versionHeader, '-I', $outputDir, '-I', $cppDir, '-I', $nativeDir,
        (Join-Path $cppDir 'main.c'), (Join-Path $cppDir 'jni_helper.c'),
        (Join-Path $cppDir 'bridge_helper.c'),
        '-L', $outputDir, '-lclash', '-llog', '-Wl,--no-undefined',
        '-Wl,-soname,libbridge.so', '-Wl,-z,max-page-size=16384',
        '-o', (Join-Path $outputDir 'libbridge.so')
    )
    & $clang @bridgeArguments
    if ($LASTEXITCODE -ne 0) { throw 'libbridge.so构建失败，预编译库未更新。' }

    # Stage both copies before replacing either library; retain a rollback copy of old files.
    New-Item -ItemType Directory -Path $prebuiltDir -Force | Out-Null
    $backupDir = Join-Path $outputDir 'previous-prebuilt'
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    $libraries = @('libclash.so', 'libbridge.so')
    $originals = @{}
    $promotionStarted = $false
    try {
        foreach ($library in $libraries) {
            $destination = Join-Path $prebuiltDir $library
            $originals[$library] = Test-Path -LiteralPath $destination -PathType Leaf
            if ($originals[$library]) {
                Copy-Item -LiteralPath $destination -Destination (Join-Path $backupDir $library) -Force
            }
            Copy-Item -LiteralPath (Join-Path $outputDir $library) -Destination ($destination + '.new') -Force
        }
        $promotionStarted = $true
        foreach ($library in $libraries) {
            $destination = Join-Path $prebuiltDir $library
            Copy-Item -LiteralPath ($destination + '.new') -Destination $destination -Force
        }
    } catch {
        $promotionError = $_
        foreach ($library in $libraries) {
            if (-not $promotionStarted) { break }
            if (-not $originals.ContainsKey($library)) { continue }
            $destination = Join-Path $prebuiltDir $library
            if ($originals[$library]) {
                Copy-Item -LiteralPath (Join-Path $backupDir $library) -Destination $destination -Force
            } elseif (Test-Path -LiteralPath $destination -PathType Leaf) {
                Remove-Item -LiteralPath $destination -Force
            }
        }
        throw $promotionError
    } finally {
        foreach ($library in $libraries) {
            Remove-Item -LiteralPath ((Join-Path $prebuiltDir $library) + '.new') -Force -ErrorAction SilentlyContinue
        }
    }
    Write-Host "两个原生库已更新至：$prebuiltDir"
    Write-Host '接下来运行.\构建.ps1，将新库打包进APK。'
} finally {
    if ($locationPushed) { Pop-Location }
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
