# 规则更新

代理首页“局域网共享”下面提供“检查规则更新”。应用从本仓库main分支读取版本信息，确认有新版本后弹窗，点击“更新”下载完整规则包。没有新版时提示“目前已是最新版本x.x.x”。

## 仓库根目录文件

|文件|用途|
|---|---|
|[routing-rules.json](../routing-rules.json)|AI及规则模式的主规则和配套域名表，规则版本与版本清单一致。|
|[routing-version.json](../routing-version.json)|版本号、更新说明、规则包字节数、校验值和音乐模式的域名策略。应用检查更新时读取此文件。|
|[shared/routing/music.json](../shared/routing/music.json)|音乐模式的可编辑域名源文件。发布脚本将它写入版本清单。|

客户端从`https://raw.githubusercontent.com/Roylyl/RoyTools/main/`读取根目录的两个发布文件。这两个文件随每次规则发布一起更新，目录迁移也保留原地址。

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

AI模式必须以`MATCH,DIRECT`结束，未匹配流量直连。规则中的非直连、非拒绝策略映射到用户当前选择的节点或代理组。客户端的订阅和节点在本地管理。

`routing-version.json`支持可选的`music`字段。3.1.4清单沿用原格式，客户端使用内置音乐域名。3.1.5开始随清单发布音乐域名策略，发布脚本从音乐域名源文件生成这个对象：

```json
{
  "domainSuffixes": ["roylyl.github.io", "raw.githubusercontent.com"]
}
```

`domainSuffixes`包含1至32个互不重复的小写ASCII域名后缀。每项至少有两个DNS标签，匹配域名本身及其子域名。音乐策略与AI规则使用清单中的同一个`version`。旧版Android可以跳过新增字段并继续更新AI与规则模式。支持音乐热更新的客户端读取旧清单时，使用内置的两个音乐域名。

### 平台接入

- Android代理的“规则”档使用规则包的`rule`段，“AI”档使用`ai`段，两者按原有顺序匹配，沿用选中的节点。音乐域名规则限制在RoyTools自身的流量，未匹配时直连。“默认”档仍使用当前订阅或导入配置的原有规则。
- iOS代理页从同一地址获取配置规则、AI规则和音乐策略。确认下载内容有效后，通过系统消息请求Packet Tunnel扩展重新加载。扩展确认成功后更新“生效版本”，加载失败时尝试恢复上一份配置。默认档保持订阅的原有规则，音乐档仅用认证后的本地代理转发RoyTools音乐请求。扩展启动时可从共享目录读取上一份生效配置。

## 发布新规则

如果源文件是Shadowrocket配置，可以直接转换。转换器只提取两份配置的`[Rule]`段，自动生成规则包和版本信息，并展开配套域名表核对规则顺序：

```powershell
.\scripts\convert-routing-rules.ps1 -AIConfig '路径\AIforHK-3.1.5.conf' -RuleConfig '路径\Roylyl-3.1.5.conf' -Version 3.1.5
```

上例展示如何从3.1.5的原始配置生成对应格式。已发布的同版本规则文件应保持原样。

手动编辑已转换的JSON时：

1. 更新AI或规则模式时，编辑根目录`routing-rules.json`。更新音乐模式时，编辑`shared/routing/music.json`。发布新版时，将`routing-rules.json`中的`version`递增。规则版本独立于应用版本。
2. 在仓库根目录运行：

```powershell
.\scripts\update-routing-manifest.ps1 -Notes '本次规则变化说明'
```

3. 将`routing-rules.json`和生成的`routing-version.json`放进同一次main分支发布。如果改了音乐域名，同时提交`shared/routing/music.json`供后续生成使用。修改已发布的内容时递增版本号。
4. 在应用代理首页点击“检查规则更新”，确认窗口中的版本后更新，无需重新安装APK。

## 下载与生效

下载窗口显示百分比，保存前可取消。完整下载后检查大小、校验值、规则版本和规则结构。支持音乐热更新的客户端同时检查音乐策略，校验通过后保存同版本的AI、规则及音乐数据。如果本地已保存同版本的AI规则包，只缺音乐策略，可以直接从清单补齐。下载失败或数据无效时继续使用上次可用的规则，404会提示规则文件尚未发布。

当前处于音乐、AI或规则模式时，更新后重新应用对应分流规则，沿用选中的节点。其他模式可在下次切换到这些模式时使用新版。启动或断网时读取最近一次成功保存的规则和版本号，首次安装使用客户端内置规则。

旧版本的域名表使用独立文件名保留，避免更新过程中影响仍在使用原规则的代理服务。
