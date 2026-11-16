# 规则更新

代理首页“局域网共享”下面提供“检查规则更新”。应用从本仓库main分支读取版本信息，确认有新版本后弹窗，点击“更新”下载完整规则包。没有新版时提示“目前已是最新版本x.x.x”。

## 仓库根目录文件

|文件|用途|
|---|---|
|[routing-rules.json](../routing-rules.json)|完整规则包，当前规则版本3.0.1，包含AI、规则两套主规则及配套域名表。|
|[routing-version.json](../routing-version.json)|版本号、更新说明、规则包字节数及下载校验值。应用检查更新时只读取这个小文件。|

固定下载源为`https://raw.githubusercontent.com/Roylyl/RoyTools/main/`。首次发布时必须将两个JSON文件一起上传到main分支；仅修改电脑上的文件不会改变手机收到的版本。

## 格式

`routing-rules.json`采用JSON封装Mihomo规则，结构如下：

```json
{
  "version": "3.0.1",
  "ai": {
    "rules": [
      "RULE-SET,roytools-ai-direct-01,DIRECT",
      "DOMAIN-SUFFIX,chatgpt.com,AI-AutoFallback",
      "MATCH,DIRECT"
    ],
    "providers": {"direct-01.txt": "+.example.com\n"}
  },
  "rule": {
    "rules": ["MATCH,Other-AutoFallback"],
    "providers": {}
  }
}
```

这是结构示例，不是完整规则。`rules`保持从上到下匹配；`providers`保存域名表正文，不引用外部URL。AI模式的规则集名称以`roytools-ai-direct-`开头，规则模式以`roytools-rule-direct-`开头，对应`direct-01.txt`等文件名。

AI模式必须以`MATCH,DIRECT`结束，未匹配流量直连。规则中的非直连、非拒绝策略会映射到用户当前选择的节点或代理组，规则包不包含代理商、订阅地址、节点或凭据。音乐模式不由这个规则包控制。

## 发布新规则

1. 编辑根目录`routing-rules.json`，修改两套规则或域名表，将`version`递增，例如从`3.0.1`改为`3.0.2`。规则版本独立于应用版本。
2. 在仓库根目录运行：

```powershell
.\scripts\update-routing-manifest.ps1 -Notes '本次规则变化说明'
```

3. 将`routing-rules.json`和生成的`routing-version.json`一起提交并推送到main分支。同版本已发布后不要修改内容，需要再次递增版本。
4. 在应用代理首页点击“检查规则更新”，确认窗口中的版本后更新，无需重新安装APK。

## 下载与生效

下载窗口显示百分比，保存前可取消。完整下载后检查大小、校验值、规则版本和结构，通过后一次性启用。网络失败、404、下载不完整或规则无效时保留原规则；404会明确提示规则文件尚未发布。

当前处于AI或规则模式时，更新后重新应用对应分流规则，保留选中的节点。其他模式继续保持原状，下次切换AI或规则模式时使用新版。更新数据保存在应用私有目录，断网沿用已下载规则，首次安装使用内置3.0.1。

旧版本的域名表使用独立文件名保留，避免更新过程中影响仍在使用原规则的代理服务。
