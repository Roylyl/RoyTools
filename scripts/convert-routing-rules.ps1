<#
将两份Shadowrocket配置的[Rule]转换为仓库根目录规则包。
只读取规则段，不读取节点或订阅。连续直连域名按原位置打包。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AIConfig,
    [Parameter(Mandatory)][string]$RuleConfig,
    [Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path -Parent $PSScriptRoot

function Convert-Section([string]$Path, [string]$Prefix) {
    $source = [Collections.Generic.List[string]]::new()
    $inside = $false
    foreach ($line in [IO.File]::ReadLines((Resolve-Path -LiteralPath $Path).Path)) {
        $rule = $line.Trim()
        if ($rule.StartsWith('[')) { $inside = $rule -eq '[Rule]'; continue }
        if (-not $inside -or -not $rule -or $rule.StartsWith('#') -or $rule.StartsWith(';')) { continue }
        if ($rule.StartsWith('FINAL,')) { $rule = 'MATCH,' + $rule.Substring(6) }
        if ($rule -notmatch '^(DOMAIN|DOMAIN-SUFFIX|DOMAIN-KEYWORD|DOMAIN-WILDCARD|IP-CIDR|IP-CIDR6|GEOIP|MATCH),') {
            throw "不支持的规则类型：$($rule.Split(',')[0])"
        }
        $source.Add($rule)
    }
    if ($source.Count -eq 0 -or -not $source[$source.Count-1].StartsWith('MATCH,')) { throw '缺少规则或最终策略' }
    if ($Prefix -eq 'roytools-ai' -and $source[$source.Count-1] -ne 'MATCH,DIRECT') { throw 'AI规则必须以直连兜底，请先检查源文件' }
    $rules = [Collections.Generic.List[string]]::new()
    $providers = [ordered]@{}
    for ($index = 0; $index -lt $source.Count;) {
        $end = $index
        while ($end -lt $source.Count -and $source[$end] -match '^DOMAIN-SUFFIX,([^,]+),DIRECT$') { $end++ }
        if ($end - $index -ge 16) {
            $name = 'direct-{0:d2}' -f ($providers.Count + 1)
            $domains = [Collections.Generic.List[string]]::new()
            for ($n = $index; $n -lt $end; $n++) { $domains.Add('+.' + $source[$n].Split(',')[1]) }
            $providers["$name.txt"] = ($domains -join "`n") + "`n"
            $rules.Add("RULE-SET,$Prefix-$name,DIRECT")
            $index = $end
        } else { $rules.Add($source[$index]); $index++ }
    }
    if ($rules.Count -gt 10000 -or $providers.Count -gt 100) { throw '规则包超过应用支持的大小' }
    # Expand each provider in place and compare with the source, including order.
    $position = 0
    foreach ($rule in $rules) {
        if ($rule.StartsWith("RULE-SET,$Prefix-")) {
            $name = $rule.Split(',')[1].Substring($Prefix.Length + 1) + '.txt'
            foreach ($domain in $providers[$name].Split("`n", [StringSplitOptions]::RemoveEmptyEntries)) {
                $expanded = 'DOMAIN-SUFFIX,' + $domain.Substring(2) + ',DIRECT'
                if ($source[$position] -cne $expanded) { throw "规则顺序校验失败：$position" }
                $position++
            }
        } else {
            if ($source[$position] -cne $rule) { throw "规则转换校验失败：$position" }
            $position++
        }
    }
    if ($position -ne $source.Count) { throw '转换后规则数量不一致' }
    Write-Host "$Prefix：$($source.Count)条源规则，$($rules.Count)条主规则，$($providers.Count)个域名表；顺序一致。"
    return [ordered]@{ rules = $rules.ToArray(); providers = $providers }
}

$bundle = [ordered]@{
    version = $Version
    ai = Convert-Section $AIConfig 'roytools-ai'
    rule = Convert-Section $RuleConfig 'roytools-rule'
}
$json = ($bundle | ConvertTo-Json -Depth 8).Replace("`r`n", "`n")
[IO.File]::WriteAllText((Join-Path $repo 'routing-rules.json'), $json, [Text.UTF8Encoding]::new($false))
& (Join-Path $PSScriptRoot 'update-routing-manifest.ps1') -Notes "AI与规则更新至$Version；保留源规则顺序，AI未匹配流量直连，沿用当前所选节点。"
