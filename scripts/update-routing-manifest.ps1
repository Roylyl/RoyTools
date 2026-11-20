<#
修改规则包、音乐域名和规则版本后运行本脚本，生成配套版本信息。
两个JSON文件需要一起发布到main分支。脚本不提交或推送。
#>
[CmdletBinding()]
param(
    [string]$Notes = '更新音乐、AI与规则模式的分流规则',
    [string]$BundleSource
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$bundlePath = Join-Path $repo 'routing-rules.json'
$musicPath = Join-Path $repo 'shared/routing/music.json'
$musicText = [IO.File]::ReadAllText($musicPath)
if (-not $musicText.TrimStart().StartsWith('{')) { throw '音乐配置必须是JSON对象' }
$music = $musicText | ConvertFrom-Json
$musicKeys = @($music.PSObject.Properties.Name)
if ($musicKeys.Count -ne 1 -or $musicKeys[0] -cne 'domainSuffixes' -or $music.domainSuffixes -isnot [array]) {
    throw '音乐配置必须只包含domainSuffixes数组'
}
$suffixes = $music.domainSuffixes
if ($suffixes.Count -lt 1 -or $suffixes.Count -gt 32) { throw '音乐域名后缀必须有1至32项' }
$seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($suffix in $suffixes) {
    if ($suffix -isnot [string] -or $suffix.Length -gt 253) { throw '音乐域名后缀必须是有效的DNS域名' }
    $labels = $suffix.Split('.')
    if ($labels.Count -lt 2 -or $labels[-1] -cnotmatch '\A(?:[a-z]+|xn--[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)\z') {
        throw '音乐域名后缀必须是有效的DNS域名'
    }
    foreach ($label in $labels) {
        if ($label -cnotmatch '\A[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\z') { throw '音乐域名后缀必须是小写ASCII标签' }
    }
    if (-not $seen.Add($suffix)) { throw '音乐域名后缀不能重复' }
}
# Git normalizes text to LF. Hash the exact UTF-8/LF bytes that GitHub will serve.
$inputPath = if ($BundleSource) { (Resolve-Path -LiteralPath $BundleSource).Path } else { $bundlePath }
$text = [IO.File]::ReadAllText($inputPath).Replace("`r`n", "`n").Replace("`r", "`n").TrimStart([char]0xfeff)
$bundle = $text | ConvertFrom-Json
if ($bundle.version -notmatch '\A(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})\z') { throw 'version必须为x.x.x' }
if ($bundle.ai.rules[-1] -ne 'MATCH,DIRECT') { throw 'AI模式必须以MATCH,DIRECT兜底' }
if (-not $bundle.rule.rules[-1].StartsWith('MATCH,')) { throw '规则模式缺少兜底规则' }
foreach ($section in @($bundle.ai, $bundle.rule)) {
    if ($section.rules.Count -lt 1 -or $section.rules.Count -gt 10000) { throw '主规则数量无效' }
    if (@($section.PSObject.Properties.Name | Where-Object { $_ -notin @('rules','providers') }).Count) { throw '禁止包含节点、订阅等配置' }
}
$utf8 = [Text.UTF8Encoding]::new($false)
$bytes = $utf8.GetBytes($text)
$stream = [IO.MemoryStream]::new($bytes)
try {
    $manifest = [ordered]@{
        version = $bundle.version
        bytes = $bytes.Length
        sha256 = (Get-FileHash -InputStream $stream -Algorithm SHA256).Hash.ToLowerInvariant()
        notes = $Notes
        music = [ordered]@{ domainSuffixes = $suffixes }
    }
} finally {
    $stream.Dispose()
}
$manifestText = ($manifest | ConvertTo-Json -Depth 4).Replace("`r`n", "`n") + "`n"
[IO.File]::WriteAllBytes($bundlePath, $bytes)
[IO.File]::WriteAllText((Join-Path $repo 'routing-version.json'), $manifestText, $utf8)
Write-Host "规则发布文件已生成：$($bundle.version)。请将routing-rules.json和routing-version.json一起发布。"
