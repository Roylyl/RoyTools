<#
修改根目录routing-rules.json中的规则及version后运行本脚本，生成配套版本信息。
两个JSON文件需要一起发布到main分支。脚本不提交或推送。
#>
[CmdletBinding()]
param([string]$Notes = '更新AI与规则模式的分流规则')
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$bundlePath = Join-Path $repo 'routing-rules.json'
# Git normalizes text to LF. Hash the exact UTF-8/LF bytes that GitHub will serve.
$text = [IO.File]::ReadAllText($bundlePath).Replace("`r`n", "`n").Replace("`r", "`n").TrimStart([char]0xfeff)
[IO.File]::WriteAllText($bundlePath, $text, [Text.UTF8Encoding]::new($false))
$bundle = $text | ConvertFrom-Json
if ($bundle.version -notmatch '^(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})$') { throw 'version必须为x.x.x' }
if ($bundle.ai.rules[-1] -ne 'MATCH,DIRECT') { throw 'AI模式必须以MATCH,DIRECT兜底' }
if (-not $bundle.rule.rules[-1].StartsWith('MATCH,')) { throw '规则模式缺少兜底规则' }
foreach ($section in @($bundle.ai, $bundle.rule)) {
    if ($section.rules.Count -lt 1 -or $section.rules.Count -gt 10000) { throw '主规则数量无效' }
    if (@($section.PSObject.Properties.Name | Where-Object { $_ -notin @('rules','providers') }).Count) { throw '禁止包含节点、订阅等配置' }
}
$manifest = [ordered]@{
    version = $bundle.version
    bytes = (Get-Item -LiteralPath $bundlePath).Length
    sha256 = (Get-FileHash -LiteralPath $bundlePath -Algorithm SHA256).Hash.ToLowerInvariant()
    notes = $Notes
}
[IO.File]::WriteAllText((Join-Path $repo 'routing-version.json'), (($manifest | ConvertTo-Json).Replace("`r`n", "`n")) + "`n")
Write-Host "规则发布文件已生成：$($bundle.version)。请将routing-rules.json和routing-version.json一起发布。"
